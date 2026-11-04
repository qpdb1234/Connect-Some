/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Base64
import com.limelight.util.Log
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import com.limelight.R
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.localsend.LsFavorites
import com.limelight.localsend.LsManager
import com.limelight.model.ServerProfile
import com.limelight.util.LiveEvent
import com.limelight.util.debugCheck
import com.limelight.util.exportKnownHosts
import com.limelight.util.exportTrustedCerts
import com.limelight.util.importKnownHosts
import com.limelight.util.importTrustedCerts
import com.limelight.util.isTrue
import com.limelight.viewmodel.service.Discovery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Viewmodel for preferences activity.
 */
class PrefsViewModel(app: Application) : BaseViewModel(app) {

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        /**
         * Preference keys a backup file is not allowed to set.
         *
         * Everything else in here is a look-and-feel choice, and a file the user picked is
         * trusted to carry those. This one is a gate: switching it off removes the device
         * authentication that stands between anyone holding the phone and the stored
         * password of every saved server. A backup that quietly disagrees with the device is
         * the wrong way to find that out.
         */
        val IMPORT_BLOCKED_PREF_KEYS = setOf("lock_saved_server", "backup_password_enabled")

        /**
         * Key prefixes used by the imported Moonlight client for its own settings.
         *
         * [com.limelight.preferences.PreferenceConfiguration] reads about forty keys straight
         * off the default `SharedPreferences` with `getString`/`getInt`/`getBoolean`, and that
         * code is not tolerant of a type it did not expect: a `ClassCastException` there is not
         * caught anywhere on the GameStream start path, so one wrongly typed entry in a backup
         * file turns every later connection into a crash - and since the bad value is now on
         * disk, reimporting a clean backup does not necessarily heal it (`list_fps` is even
         * parsed with `Integer.parseInt`, which throws on a perfectly valid *string*). Nothing
         * in this app ever writes those keys: the Moonlight settings are not exposed in its UI,
         * and the only writer is Moonlight's own migration code, so there is nothing worth
         * restoring in a backup. The whole namespace is therefore refused on principle.
         */
        val FOREIGN_PREF_PREFIXES = listOf("list_", "checkbox_", "seekbar_")

        /** Moonlight keys that do not follow a prefix. Same reasoning as above. */
        val FOREIGN_PREF_KEYS = setOf("video_format", "frame_pacing", "analog_scrolling")
    }

    /** Whether [key] belongs to a reader outside this app, which cannot cope with a wrong type. */
    private fun isForeignPref(key: String) =
            key in FOREIGN_PREF_KEYS || FOREIGN_PREF_PREFIXES.any { key.startsWith(it) }

    /**************************************************************************
     * Import/Export
     *
     * Importing/Exporting is done on a background thread.
     **************************************************************************/

    /**
     * Everything the app persists. When a new kind of persisted content is added anywhere in
     * the app, it has to gain a field here too (and an export/import switch) - otherwise a
     * "complete" backup silently leaves it behind and a fresh install cannot rebuild it.
     *
     * [localsendFavorites] carries the LocalSend pinned devices as their stored JSON array, so
     * a restore brings back the list and its order. It travels as text rather than a typed
     * section because the entry type is private to [com.limelight.localsend.LsFavorites].
     *
     * [discoveryCache] carries the addresses and hardware addresses discovery learned across
     * rounds; [knownHosts] and [trustedCerts] carry the SSH host keys and the trusted VNC
     * certificates. None of these are secrets, but a fresh install cannot rebuild any of them
     * without re-walking the network and re-prompting the user for every host it already knew.
     */
    @Serializable
    private data class Container(
            val version: Int = 1,
            var profiles: List<ServerProfile>,
            var preferences: Map<String, JsonElement> = emptyMap(),
            var pairing: PairingIdentity? = null,
            var localsendFavorites: String? = null,
            var discoveryCache: Map<String, Set<String>>? = null,
            var knownHosts: String? = null,
            var trustedCerts: Map<String, String>? = null,
    )

    /**
     * The GameStream client identity: the certificate a host put on its paired list, and the
     * private key that proves this client is the one that presented it.
     *
     * The other half of a pairing needs no special treatment - the pinned server certificate
     * and the client's unique id are columns of a server profile and travel with it. What a
     * fresh install cannot rebuild on its own is this pair: without it every restored host
     * answers its TLS handshake with a refusal, and the user is left pairing again server by
     * server, which is exactly what a backup was supposed to prevent.
     *
     * Both are base64 of the bytes kept in the app's own files, so that a backup stays the
     * plain text file it has always been.
     */
    @Serializable
    private data class PairingIdentity(
            val clientCertificate: String = "",
            val clientPrivateKey: String = "",
    )

    private val serializer = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Parts of the stored preferences that a backup may carry. Every key the app has ever
     * stored belongs to exactly one of these: keys shown by a preference screen follow the
     * screen that owns them, and everything else (the settings written by the app's own
     * dialogs rather than by a screen) falls into [OTHER].
     */
    private enum class PrefCategory { APPEARANCE, INPUT, VIEWER, SERVER, OTHER }

    val exportPrefAppearance = MutableLiveData(true)
    val exportPrefInput = MutableLiveData(true)
    val exportPrefViewer = MutableLiveData(true)
    val exportPrefServer = MutableLiveData(true)
    val exportPrefOther = MutableLiveData(true)
    val exportProfiles = MutableLiveData(true)
    // Secrets ship ticked: the user asked that a backup carry the passwords/keys by default,
    // so the checkbox starts on and one has to actively untick it to scrub them out.
    val exportSecrets = MutableLiveData(true)

    /**
     * The GameStream client certificate and key, ticked along with the other secrets and
     * useless without them: the pairing is only worth restoring in a backup that also
     * restored the servers it was made against.
     */
    val exportPairingIdentity = MutableLiveData(true)

    /** Whether an imported backup may replace this device's client certificate and key. */
    val importPairingIdentity = MutableLiveData(true)

    /**
     * The LocalSend pinned devices, ticked on by default like the other sections: they are the
     * one thing on this screen that a fresh install cannot rediscover on its own (discovery
     * finds who is around now, not what the user chose to remember). No secrets, just names and
     * addresses, so export and import share a single switch.
     */
    val exportLocalSendFavorites = MutableLiveData(true)
    val importLocalSendFavorites = MutableLiveData(true)

    /**
     * The addresses and MACs discovery learned across rounds. Not secret, and the reason a card
     * can name a host's hardware address before this round's probes jog the neighbour cache; the
     * backup keeps a reinstall from having to re-learn every one.
     */
    val exportDiscoveryCache = MutableLiveData(true)
    val importDiscoveryCache = MutableLiveData(true)

    /**
     * The SSH known-hosts file and the trusted VNC certificates - the trust the user already
     * granted. Public material rather than keys, so this rides outside the secrets gate; carrying
     * it means a fresh install does not re-prompt for every host it was told to trust before.
     */
    val exportSshTrust = MutableLiveData(true)
    val importSshTrust = MutableLiveData(true)

    val deleteCurrentServerBeforeImport = MutableLiveData(false)
    // Off until the user turns it on: the gate is opt-in, so a fresh install exports and imports
    // without demanding the device credential. The default only covers the never-touched case -
    // once switched, the stored value wins.
    val backupPasswordEnabled = MutableLiveData(PreferenceManager.getDefaultSharedPreferences(app).getBoolean("backup_password_enabled", false))

    val importExportFinishedEvent = LiveEvent<Result<String>>()

    /**
     * Exports data to given [uri].
     */
    fun export(uri: Uri) {
        launchImportExport {
            val json = exportJson()

            // Write out
            app.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.writer().use { it.write(json) }
            } ?: throw IOException(app.getString(R.string.err_export_unwritable))

            app.getString(R.string.msg_exported)
        }
    }

    /**
     * Exports data as JSON and posts the result on [jsonDestination],
     * for export, e.g. via QR code.
     */
    fun export(jsonDestination : MutableLiveData<String>) {
        launchImportExport {
            val json = exportJson()
            jsonDestination.postValue(json)
            app.getString(R.string.msg_exported)
        }
    }

    /**
     * Imports data from given [uri].
     *
     * Only what the document provider handed over is read. There is no network branch: a
     * backup is applied to the live configuration, so letting a picked URI name a host would
     * let whatever answers that request rewrite these settings, and the app would fetch it
     * without needing any permission at all.
     */
    fun import(uri: Uri) {
        launchImportExport {
            val json = app.contentResolver.openInputStream(uri)?.use { stream ->
                // Bounded, because `readText()` on a stream of unknown length has no
                // ceiling, and the picker can be pointed at anything the app can open - a
                // multi gigabyte file turns the import thread into an OutOfMemoryError,
                // which no `catch (Exception)` on the way back can stop.
                readBounded(stream)
            } ?: throw IOException(app.getString(R.string.err_import_unreadable))

            importJson(json)
            app.getString(R.string.msg_imported)
        }
    }

    /** Reads at most [MAX_RESPONSE_BYTES] from [stream], refusing anything larger. */
    private fun readBounded(stream: java.io.InputStream): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (output.size() + n > MAX_RESPONSE_BYTES)
                throw IOException(app.getString(R.string.err_import_too_large))
            output.write(buffer, 0, n)
        }

        // Editors on Windows write a UTF-8 BOM, and this app's own export opens with a
        // `{` - so the only thing between an exported backup and a hand-edited one is those
        // three bytes, which the parser reports as malformed JSON. Dropped here rather than
        // passed on as the first character of the document.
        val bytes = output.toByteArray()
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * Imports data from given JSON string (e.g. read off a QR code).
     */
    fun import(json: String) {
        launchImportExport {
            importJson(json)
            app.getString(R.string.msg_imported)
        }
    }

    /**
     * The backup protection switch. Turning it on makes import/export ask for the device
     * credential (see [com.limelight.util.DeviceAuthPrompt]) - the lock-screen PIN / pattern /
     * password / fingerprint, exactly like AVNC. Only the flag is persisted here: there is no
     * separate backup secret, so a read of the preference file yields nothing to attack.
     *
     * [IMPORT_BLOCKED_PREF_KEYS] keeps this flag out of backup files - a backup that quietly
     * disagreed with the device about whether the gate is armed is the wrong way to find out.
     */
    fun setBackupPasswordEnabled(enabled: Boolean) {
        backupPasswordEnabled.value = enabled
        PreferenceManager.getDefaultSharedPreferences(app).edit { putBoolean("backup_password_enabled", enabled) }
    }

    /**
     * Outcome of the duplicate sweep offered on the Tools screen, carried as the number of cards
     * it removed. Kept apart from [importExportFinishedEvent] because the backup screen observes
     * that one, and it sits *inside* the Tools screen whose row fires this - sharing the event
     * would report a cleanup twice over.
     *
     * A count rather than a sentence: this class holds the application context, and the in-app
     * language lives on the activity, so a string built here would come out in the system
     * language no matter what the user picked. The screen words it instead.
     */
    val dedupeFinishedEvent = LiveEvent<Result<Int>>()

    /**
     * Remove saved servers that are indistinguishable connection targets.
     *
     * Restoring a backup without "replace the current list" appends every profile the file
     * carries, because they arrive with their id cleared and so read as new servers. That is the
     * behaviour the tick box asks for, but a user who restores twice ends up with two identical
     * cards per machine, and the list keeps growing until scrolling it reads as a loop that
     * never ends. Rather than asking them to pick the clones out by hand, this offers one sweep.
     *
     * "Identical" is the name together with [ServerProfile.displayAddress], port and protocol:
     * what a connection is actually made on, plus what the user called it. Two services on one
     * PC therefore stay two cards, as does the same address reached over RFB and over GameStream,
     * and so does a card made with the editor's own duplicate action - that one appends " (Copy)"
     * to the name precisely to say it is meant to stay. The address comes from that property
     * rather than from [ServerProfile.host] because a USB-OTG phone has no host at all - the
     * cable is its address, held in [ServerProfile.androidUsbDeviceName] - so keying on the host
     * alone would merge two devices plugged into the same port. Of each group the lowest id
     * survives - the first one written, which is the card the user configured before the copies
     * appeared.
     */
    fun dedupeServers() {
        launchIO {
            runCatching {
                val duplicates = serverProfileDao.getList()
                        .sortedBy { it.ID }
                        .groupBy { listOf(it.name, it.displayAddress, it.port, it.protocol) }
                        .values.flatMap { group -> group.drop(1) }

                if (duplicates.isNotEmpty())
                    db.withTransaction { serverProfileDao.delete(duplicates) }

                duplicates.size
            }.let { dedupeFinishedEvent.fireAsync(it) }
        }
    }

    /**
     * Runs an import/export operation on a background thread and fires
     * [importExportFinishedEvent] with its [Result].
     */
    private fun launchImportExport(block: suspend () -> String) {
        launchIO {
            runCatching {
                block()
            }.let {
                importExportFinishedEvent.fireAsync(it)
            }
        }
    }

    /**
     * Serializes current profiles or settings into a backup JSON string.
     */
    private suspend fun exportJson(): String {
        val categories = enabledPrefCategories()
        val exportProfiles = exportProfiles.isTrue
        val exportSecrets = exportSecrets.isTrue && exportProfiles
        // Nested inside the secrets switch: a private key is a secret, and an "export without
        // passwords and keys" backup that still carried one would be a lie about its contents.
        val exportPairing = exportPairingIdentity.isTrue && exportSecrets
        debugCheck(categories.isNotEmpty() || exportProfiles)

        // Serialize
        val data = Container(
                profiles = if (exportProfiles) serverProfileDao.getList() else emptyList(),
                preferences = if (categories.isNotEmpty()) collectPreferences(categories) else emptyMap(),
                pairing = if (exportPairing) readPairingIdentity() else null,
                localsendFavorites = if (exportLocalSendFavorites.isTrue)
                    LsFavorites.exportRaw(app) else null,
                discoveryCache = if (exportDiscoveryCache.isTrue)
                    Discovery.exportCache(app).takeIf { it.isNotEmpty() } else null,
                knownHosts = if (exportSshTrust.isTrue) exportKnownHosts(app) else null,
                trustedCerts = if (exportSshTrust.isTrue)
                    exportTrustedCerts(app).takeIf { it.isNotEmpty() } else null,
        )

        if (!exportSecrets)
            scrubSecrets(data.profiles)

        return serializer.encodeToString(data)
    }

    /**
     * The client certificate and key this device pairs GameStream hosts with, or null when it
     * has never paired and therefore has nothing worth carrying over. Reading them never
     * generates a pair - see [AndroidCryptoProvider.exportClientIdentity].
     */
    private fun readPairingIdentity(): PairingIdentity? {
        val identity = runCatching { AndroidCryptoProvider(app).exportClientIdentity() }.getOrNull()
                ?: return null
        return PairingIdentity(
                clientCertificate = Base64.encodeToString(identity.certificatePem, Base64.NO_WRAP),
                clientPrivateKey = Base64.encodeToString(identity.privateKeyPkcs8, Base64.NO_WRAP))
    }

    /**
     * Deserializes given backup JSON and updates the database.
     *
     * @return whether the backup carried a client identity and this device took it over.
     */
    private suspend fun importJson(json: String): Boolean {
        val deleteCurrentServers = deleteCurrentServerBeforeImport.isTrue

        // Deserialize
        val data = serializer.decodeFromString<Container>(json)

        //This is where migrations would be applied (if required in future)

        // Nothing between this line and the database checks what the file said. A backup (or
        // a scanned QR code) can name a port of 0, a resolution of two billion or a zoom of
        // NaN, and each of those would be acted on later as if the editor had written it.
        data.profiles.forEach { it.sanitize() }

        if (data.profiles.isNotEmpty()) {
            if (deleteCurrentServers) {
                db.withTransaction {
                    serverProfileDao.deleteAll()
                    serverProfileDao.save(data.profiles)
                }
            } else {
                //Reset IDs so that they don't conflict with saved profiles
                data.profiles.forEach { it.ID = 0 }
                serverProfileDao.save(data.profiles)
            }
        }

        // The client identity goes in after the servers it belongs to: a restored pairing is
        // worthless beside a list that did not come with it, and the other order would leave
        // the user with a replaced certificate and the old servers still asking for a PIN.
        val pairingRestored = importPairingIdentity.isTrue && storePairingIdentity(data.pairing)

        // Replay app preferences (best-effort, unknown keys are ignored)
        applyPreferences(data.preferences)

        // The pinned devices are stored in their own preferences file, outside the default one
        // the loop above walks, so they are restored here and then pushed back into the live
        // LocalSend table so an open screen updates without a restart.
        val favoritesRaw = data.localsendFavorites
        if (importLocalSendFavorites.isTrue && favoritesRaw != null &&
                LsFavorites.importRaw(app, favoritesRaw)) {
            LsManager.reloadFavorites()
        }

        // Discovery's learned addresses and MACs are written back into its own preferences file,
        // outside the default one the loop above walked; the cache re-reads its live MAC memory.
        if (importDiscoveryCache.isTrue) {
            data.discoveryCache?.let { Discovery.importCache(app, it) }
        }

        // SSH known hosts and trusted VNC certificates land back in filesDir, so the user is not
        // re-prompted for machines this device was already told to trust.
        if (importSshTrust.isTrue) {
            data.knownHosts?.let { importKnownHosts(app, it) }
            data.trustedCerts?.let { importTrustedCerts(app, it) }
        }

        return pairingRestored
    }

    /**
     * Writes a backed-up client identity into the app's own files, replacing the one this
     * device generated for itself. Refused silently when the file has none, or carries
     * material that cannot be read as a certificate and a matching key - a device that keeps
     * its old identity can still be paired again, while one that stored junk from a backup
     * would fail every connection with nothing left to compare against.
     */
    private fun storePairingIdentity(pairing: PairingIdentity?): Boolean {
        if (pairing == null || pairing.clientCertificate.isEmpty() || pairing.clientPrivateKey.isEmpty())
            return false

        val certificate = decodeBase64(pairing.clientCertificate) ?: return false
        val privateKey = decodeBase64(pairing.clientPrivateKey) ?: return false

        return runCatching {
            AndroidCryptoProvider(app).importClientIdentity(
                    AndroidCryptoProvider.ClientIdentity(certificate, privateKey))
        }.getOrDefault(false)
    }

    /** Base64 as this app writes it (no line breaks), or null when the text is not Base64. */
    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.NO_WRAP)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Preference categories the user asked the backup to include.
     */
    private fun enabledPrefCategories(): Set<PrefCategory> {
        val out = mutableSetOf<PrefCategory>()
        if (exportPrefAppearance.isTrue) out += PrefCategory.APPEARANCE
        if (exportPrefInput.isTrue) out += PrefCategory.INPUT
        if (exportPrefViewer.isTrue) out += PrefCategory.VIEWER
        if (exportPrefServer.isTrue) out += PrefCategory.SERVER
        if (exportPrefOther.isTrue) out += PrefCategory.OTHER
        return out
    }

    /**
     * Which backup section each stored preference key belongs to, read off the preference
     * screens themselves (same source of truth as [collectDeclaredPrefs]). A key no screen
     * declares is not anybody's section but [OTHER].
     */
    private val prefCategoryOf: Map<String, PrefCategory> by lazy { collectPrefCategories() }

    private fun collectPrefCategories(): Map<String, PrefCategory> {
        val screens = mapOf(
                R.xml.pref_appearance to PrefCategory.APPEARANCE,
                R.xml.pref_input to PrefCategory.INPUT,
                R.xml.pref_viewer to PrefCategory.VIEWER,
                R.xml.pref_server to PrefCategory.SERVER,
        )
        val map = HashMap<String, PrefCategory>()

        for ((screen, category) in screens) {
            try {
                val parser = app.resources.getXml(screen)
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i) == "key")
                            parser.getAttributeValue(i)?.let { map[it] = category }
                    }
                }
            } catch (e: Exception) {
                // A screen that cannot be read simply loses its classification: its keys
                // fall back to OTHER and are still exported, just not as a named section.
                Log.w("PrefsViewModel", "Cannot classify the preference screen", e)
            }
        }
        return map
    }

    /**
     * Reads all preferences of the given [categories] as typed [JsonElement]s.
     */
    private fun collectPreferences(categories: Set<PrefCategory>): Map<String, JsonElement> {
        val all = PreferenceManager.getDefaultSharedPreferences(app).all
        val map = mutableMapOf<String, JsonElement>()
        for ((key, value) in all) {
            if (key.startsWith("run_info_"))
                continue

            if ((prefCategoryOf[key] ?: PrefCategory.OTHER) !in categories)
                continue

            when (value) {
                null -> Log.w("PrefsViewModel", "Skipping null preference: $key")
                is String -> map[key] = JsonPrimitive(value)
                is Boolean -> map[key] = JsonPrimitive(value)
                is Int -> map[key] = JsonPrimitive(value)
                is Float -> map[key] = JsonPrimitive(value)
                is Long -> map[key] = JsonPrimitive(value)
                is Set<*> -> if (value.all { it is String })
                    map[key] = JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) })
                else -> Log.w("PrefsViewModel", "Skipping unsupported preference type for key: $key")
            }
        }
        return map
    }

    /**
     * Imports preferences from JSON.
     */
    private fun applyPreferences(prefs: Map<String, JsonElement>) {
        // Read once, so every decision below can be taken against the type the app
        // actually stores under that key.
        val current = PreferenceManager.getDefaultSharedPreferences(app).all

        PreferenceManager.getDefaultSharedPreferences(app).edit {
            for ((key, element) in prefs) {
                if (key in IMPORT_BLOCKED_PREF_KEYS) {
                    Log.i("PrefsViewModel", "Not restoring a security setting from a file: $key")
                    continue
                }

                if (isForeignPref(key)) {
                    Log.i("PrefsViewModel", "Not restoring a Moonlight-owned preference: $key")
                    continue
                }

                val existing = current[key]
                val declared = declaredPrefs[key]
                val value = preferenceValue(element, existing, declared?.type)
                if (value == null) {
                    Log.w("PrefsViewModel", "Ignoring unusable preference: $key")
                    continue
                }

                // A list widget writes one of its own `entryValues` or nothing at all, and
                // every reader of such a key is a `when` over those strings with a default
                // arm written for the missing case. A value outside the list is therefore
                // not a choice the user could have made: it reaches a code path no reader
                // expects, or, worse, stays there forever as the value the dialog shows.
                if (declared?.allowedValues != null && (value !is String || value !in declared.allowedValues)) {
                    Log.w("PrefsViewModel", "Ignoring a value no widget could have written: $key")
                    continue
                }

                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }

    /**
     * Turns one JSON value into something [android.content.SharedPreferences] can hold.
     *
     * JSON does not distinguish an int from a long from a float, but the preference screen
     * that wrote the value did: every setting is read back with the type its widget
     * declared, and `getInt` on a key holding a String throws. So when the key already
     * exists, the stored type decides how the text gets parsed - a value that cannot be
     * read that way is dropped rather than installed under the right name with the wrong
     * type.
     *
     * For a key this app has never stored there is nothing on disk to go by. Most of them
     * belong to [com.limelight.util.AppPreferences], whose readers shrug off a mismatch -
     * those get their type guessed, which is all an older backup can offer. The ones a
     * preference screen owns are different, see [declaredPrefs].
     */
    private fun preferenceValue(element: JsonElement, existing: Any?, declared: WidgetType?): Any? {
        if (element is JsonArray) {
            // A set belongs to a `MultiSelectListPreference` and to nothing else.
            if (existing != null && existing !is Set<*>) return null
            if (declared != null) return null
            return element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }

        if (element !is JsonPrimitive) return null
        val content = element.content

        if (existing == null && declared != null) {
            // The widget that reads this back has already told us what it will ask for, so
            // the literal's shape in the file is not evidence worth following.
            return when (declared) {
                WidgetType.TEXT -> content
                WidgetType.BOOLEAN -> content.toBooleanStrictOrNull()
                WidgetType.INT -> content.toIntOrNull()
            }
        }

        return when (existing) {
            null -> when {
                // An unquoted literal is the only evidence of a number there can be. A
                // backup that writes `"2"` for a key this app has never stored means text,
                // and guessing an Int out of it would leave the reader facing a type the
                // preference screen never uses for that key.
                element.isString -> content
                content == "true" || content == "false" -> content.toBoolean()
                content.toIntOrNull() != null -> content.toInt()
                content.toLongOrNull() != null -> content.toLong()
                isFiniteFloat(content) -> content.toFloat()
                else -> content
            }

            is String -> content
            is Boolean -> if (!element.isString) content.toBooleanStrictOrNull() else null
            is Int -> content.toIntOrNull()
            is Long -> content.toLongOrNull()
            is Float -> if (isFiniteFloat(content)) content.toFloat() else null
            else -> null
        }
    }

    /**
     * Whether [content] is a float SharedPreferences can be asked for again.
     *
     * `NaN` and `Infinity` parse, and `putFloat` accepts them, but they slip past every
     * `coerceIn()` downstream (NaN compares false against both bounds and is handed back
     * unchanged) and end up as an invisible widget, an off-screen floating ball or a zoom
     * that no touch can be mapped through any more.
     */
    private fun isFiniteFloat(content: String): Boolean = content.toFloatOrNull()?.isFinite() == true

    /** What a preference widget is going to ask the store for. */
    private enum class WidgetType { TEXT, BOOLEAN, INT }

    /**
     * One screen's own declaration of one key: the type its widget will read back and, for a
     * list, the values that widget will offer.
     */
    private data class DeclaredPref(val type: WidgetType, val allowedValues: Set<String>?)

    /**
     * The type each preference screen will read its own keys back with, keyed by `app:key`.
     *
     * A widget does not accept a surprise: `ListPreference` calls `getString`,
     * `SwitchPreference` calls `getBoolean` and `SeekBarPreference` calls `getInt`, and the
     * wrong type throws a `ClassCastException` while the screen is still being inflated.
     * Nothing catches that, and because the stored type outranks every later import, one
     * hand written backup file would lock the user out of that screen for good - only
     * clearing app data, and therefore the whole configuration, gets it back. Guessing is
     * fine for the keys this app reads on its own (those readers fall back to the default),
     * but not for these.
     *
     * Read off the screens' own XML rather than copied into a list here, so that adding a
     * setting cannot make this table wrong.
     */
    private val declaredPrefs: Map<String, DeclaredPref> by lazy { collectDeclaredPrefs() }

    private fun collectDeclaredPrefs(): Map<String, DeclaredPref> {
        val screens = intArrayOf(R.xml.pref_main, R.xml.pref_appearance, R.xml.pref_input,
                R.xml.pref_server, R.xml.pref_viewer, R.xml.pref_tools)
        val declared = HashMap<String, DeclaredPref>()

        for (screen in screens) {
            try {
                val parser = app.resources.getXml(screen)
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue

                    var key: String? = null
                    var entryValuesRes = 0
                    for (i in 0 until parser.attributeCount) {
                        // Matched on the local name: `app:key` is bound to the resource-auto
                        // namespace, and that is not reported the same way from source XML and
                        // from a compiled one.
                        when (parser.getAttributeName(i)) {
                            "key" -> key = parser.getAttributeValue(i)
                            "entryValues" -> entryValuesRes = parser.getAttributeResourceValue(i, 0)
                        }
                    }

                    val tag = parser.name.substringAfterLast('.')
                    val type = when (tag) {
                        "ListPreference", "ListPreferenceEx", "EditTextPreference",
                        "NumericEditTextPreference" -> WidgetType.TEXT

                        "SwitchPreference", "SwitchPreferenceCompat",
                        "CheckBoxPreference" -> WidgetType.BOOLEAN

                        "SeekBarPreference" -> WidgetType.INT

                        // A plain `Preference` (a navigation row) and the categories persist
                        // nothing, so a key on one of those is not a widget's business.
                        else -> null
                    }

                    if (key == null || type == null) continue

                    // Only a list constrains its values. Anything else about the tag name is
                    // not a list, so no value set is claimed for it.
                    val allowed = if (tag == "ListPreference" || tag == "ListPreferenceEx") {
                        runCatching { app.resources.getStringArray(entryValuesRes).toSet() }
                                .getOrNull()?.takeIf { it.isNotEmpty() }
                    } else null

                    declared[key] = DeclaredPref(type, allowed)
                }
            } catch (e: Exception) {
                // A screen that cannot be read is a screen this import will not poison: the
                // keys it owns simply keep the guessing behaviour they always had.
                Log.w("PrefsViewModel", "Cannot read the preference schema", e)
            }
        }

        return declared
    }

    private fun scrubSecrets(profiles: List<ServerProfile>) {
        profiles.forEach {
            it.password = ""
            it.clipboardVncPassword = ""
            it.sshPassword = ""
            it.sshPrivateKey = ""
        }
    }
}