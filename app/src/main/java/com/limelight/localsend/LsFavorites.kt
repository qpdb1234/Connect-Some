/*
 * Devices the user pinned, kept apart from the discovery table.
 *
 * Discovery is a live thing: a peer that stops re-confirming itself is dropped once
 * [LsConstants.PEER_TTL_MS] passes, which is exactly right for a list of who is around and
 * wrong for a list of who the user wants around. Favorites are therefore their own persisted
 * set, and the pruning loop never touches them.
 *
 * Stored as one JSON array under a single key rather than as one preference entry per device:
 * a device alias is free text picked by its owner and can contain any separator an encoded
 * line would be split on, and the package already has the wire JSON codec to lean on.
 */
package com.limelight.localsend

import android.content.Context
import kotlinx.serialization.Serializable

object LsFavorites {

    private const val PREFS = "localsend_favorites"
    private const val KEY_ENTRIES = "entries"

    /** The parts of an [LsPeer] that survive a reboot; `lastSeen` deliberately does not. */
    @Serializable
    private class Entry(
        val fingerprint: String,
        val alias: String,
        val host: String,
        val port: Int,
        val deviceModel: String?,
        val version: String,
    )

    /**
     * The pinned devices, in the order they were pinned. Unreadable content is treated as
     * empty rather than thrown: a hand-edited backup being nonsense must not stop the app
     * from starting.
     */
    fun load(context: Context): List<LsPeer> = runCatching {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        lsJson.decodeFromString<List<Entry>>(raw).map {
            LsPeer(it.fingerprint, it.alias, it.host, it.port, it.deviceModel, it.version, 0L)
        }
    }.getOrDefault(emptyList())

    /** Writes the whole list, so an unpin and a refresh of one row share one code path. */
    fun save(context: Context, peers: List<LsPeer>) {
        val entries = peers.map { Entry(it.fingerprint, it.alias, it.host, it.port, it.deviceModel, it.version) }
        runCatching {
            prefs(context).edit()
                .putString(KEY_ENTRIES, lsJson.encodeToString(entries)).apply()
        }
    }

    /**
     * The favorites as the raw JSON array stored on disk, or null when none are pinned. Kept as
     * the stored form so a backup carries favorites - and their order - verbatim without this
     * file's [Entry] type becoming public. See [importRaw] for the other half.
     */
    fun exportRaw(context: Context): String? =
        prefs(context).getString(KEY_ENTRIES, null)?.takeIf { it.isNotBlank() }

    /**
     * Replaces the stored favorites with [raw] after checking it decodes, so a hand-edited or
     * truncated backup cannot leave the list in a state [load] would choke on. Returns whether
     * anything was written; an empty or unreadable [raw] leaves the current favorites alone.
     */
    fun importRaw(context: Context, raw: String): Boolean = runCatching {
        val entries = lsJson.decodeFromString<List<Entry>>(raw)
        prefs(context).edit().putString(KEY_ENTRIES, raw).apply()
        entries.isNotEmpty()
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
