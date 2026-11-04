/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import com.limelight.R
import com.limelight.util.AccentTheme
import com.limelight.util.AppLogging
import com.limelight.util.Debugging
import com.limelight.util.DeviceAuthPrompt
import com.limelight.util.EdgeToEdgeHelper
import com.limelight.util.PersistentLog
import com.limelight.viewmodel.PrefsViewModel
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrefsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        DeviceAuthPrompt.applyFingerprintDialogFix(supportFragmentManager)

        super.onCreate(savedInstanceState)
        EdgeToEdgeHelper.setContentView(this, R.layout.activity_settings)

        if (savedInstanceState == null) {
            // The viewer's virtual key bar names one screen to open straight away. It is put in
            // place of the list rather than on top of it, and without a back stack entry: the
            // user came for that one screen, so leaving it has to leave the settings too
            // instead of parking them among switches they never asked to see.
            val startFragment = intent.getStringExtra(EXTRA_START_FRAGMENT)
            val fragment = startFragment?.let {
                supportFragmentManager.fragmentFactory.instantiate(classLoader, it)
            } ?: Main()

            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_host, fragment)
                    .commit()
        }

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Starts new fragment corresponding to given [pref].
     */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment!!)

        supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, fragment)
                .addToBackStack(null)
                .commit()

        return true
    }


    /**************************************************************************
     * Preference Fragments
     **************************************************************************/

    abstract class PrefFragment(private val prefResource: Int) : PreferenceFragmentCompat() {

        override fun onResume() {
            super.onResume()
            activity?.title = preferenceScreen.title
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(prefResource, rootKey)
            // Category screens carry a "restore this screen to defaults" row (added in each
            // pref_* XML except Tools, whose reset-all already covers the whole app); the main
            // list has none, so findPreference returns null there and nothing is wired. Living
            // in the base keeps one reset path across the categories that do offer the row.
            findPreference<Preference>(KEY_RESET_CATEGORY)?.setOnPreferenceClickListener {
                confirmResetCategory()
                true
            }
        }

        // The stock framework only knows how to show dialogs for its own preference
        // classes; route our custom ones to their fragments and leave the rest to super.
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is AccentColorPreference) {
                val dialog = AccentColorPreferenceDialogFragment.newInstance(preference.key)
                @Suppress("DEPRECATION")
                dialog.setTargetFragment(this, 0)
                dialog.show(parentFragmentManager, "AccentColorDialog")
            } else if (preference is ProtocolBadgePreference) {
                val dialog = ProtocolBadgePreferenceDialogFragment.newInstance(preference.key)
                @Suppress("DEPRECATION")
                dialog.setTargetFragment(this, 0)
                dialog.show(parentFragmentManager, "ProtocolBadgeDialog")
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }

        // A reset that wipes a whole screen deserves one deliberate step, like the reset-all on
        // the Tools screen: the affirmative reads as the destructive action, not a neutral OK.
        private fun confirmResetCategory() {
            AlertDialog.Builder(requireContext())
                    .setTitle(preferenceScreen.title)
                    .setMessage(R.string.msg_reset_category_defaults_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_reset) { _, _ -> resetCategory() }
                    .show()
        }

        /**
         * Restore just this screen: every preference it shows is dropped from the store and
         * re-seeded from its shipped default, while every other screen keeps what it holds.
         * Action rows and fragment links that were never stored are collected too, but removing
         * a key that is not there is a no-op, so they cost nothing.
         */
        private fun resetCategory() {
            val app = requireContext().applicationContext
            val keys = ArrayList<String>()
            collectKeys(preferenceScreen, keys)
            PreferenceManager.getDefaultSharedPreferences(app).edit()
                    .apply { keys.forEach { remove(it) } }
                    .apply()
            // Re-seed only this screen's XML. readAgain pushes the defaults past the once-only
            // "already seeded" marker, the same way the Tools screen's reset-all does.
            PreferenceManager.setDefaultValues(app, prefResource, true)
            // Let a screen resync anything it drives outside the store; it reports whether it
            // recreated the activity itself (a locale change does), so we do not recreate twice.
            if (!onCategoryReset()) {
                Toast.makeText(app, R.string.msg_reset_category_defaults_done, Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
        }

        /**
         * Called after this screen's keys are wiped and re-seeded, before it is rebuilt. Override
         * where a preference drives state kept outside SharedPreferences. Return true if the
         * override triggered its own activity recreation, so the caller skips the plain rebuild.
         */
        protected open fun onCategoryReset(): Boolean = false

        private fun collectKeys(pref: Preference, out: MutableList<String>) {
            pref.key?.let { if (it != KEY_RESET_CATEGORY) out.add(it) }
            if (pref is PreferenceGroup) {
                for (i in 0 until pref.preferenceCount) collectKeys(pref.getPreference(i), out)
            }
        }

        companion object {
            /** Key of the per-screen reset row, present in the category XMLs that offer it. */
            const val KEY_RESET_CATEGORY = "reset_category_defaults"
        }
    }

    @Keep class Main : PrefFragment(R.xml.pref_main)

    @Keep class Appearance : PrefFragment(R.xml.pref_appearance) {

        // The chosen language lives in the AndroidX locale store, not in the preference file the
        // reset clears, so it has to be put back by hand. Empty means "follow the system", the
        // shipped default; applying it recreates the activities on its own, which is why this
        // returns true and the base skips its own rebuild.
        override fun onCategoryReset(): Boolean {
            if (!AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                return true
            }
            return false
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            // The AndroidX locale store is the single source of truth (it also feeds the
            // system per-app-language settings on Android 13+), so the picker reflects it
            // rather than its own persisted value. An empty locale list means "follow the
            // system", which we surface as the "system" entry value.
            findPreference<ListPreference>("app_language")?.apply {
                value = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                    .substringBefore(',').ifBlank { "system" }
                setOnPreferenceChangeListener { _, newValue ->
                    val tag = newValue as String
                    val locales = if (tag == "system") LocaleListCompat.getEmptyLocaleList()
                                  else LocaleListCompat.forLanguageTags(tag)
                    // Applying recreates the running activities, re-localizing the whole UI.
                    AppCompatDelegate.setApplicationLocales(locales)
                    true
                }
            }
        }
    }

    @Keep class Viewer : PrefFragment(R.xml.pref_viewer) {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            // "Start" and "End" mean nothing on their own - which edge they are depends on the
            // reading direction - so the row shows its explanation under the chosen value
            // instead of making the user open the dialog to find out. The two are separated by
            // a blank line because the value is an answer and the text is about the question.
            // Deliberately not `app:dialogMessage`: androidx drops the whole radio list when a
            // ListPreference is given one.
            findPreference<ListPreference>("toolbar_alignment")?.summaryProvider =
                    Preference.SummaryProvider<ListPreference> { pref ->
                        listOfNotNull(pref.entry, pref.context.getString(R.string.desc_toolbar_alignment))
                                .joinToString("\n\n")
                    }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // If system does not support PiP, disable its preference
            val hasPiPSupport = Build.VERSION.SDK_INT >= 26 &&
                                requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

            if (!hasPiPSupport) {
                findPreference<SwitchPreference>("pip_enabled")!!.apply {
                    isEnabled = false
                    summary = getString(R.string.msg_pip_not_supported)
                }
            }
        }
    }

    /**
     * The whole of the virtual keys behind one entry: the switches that say how the keys
     * behave, and the route to the grid that says which keys there are. The viewer's key bar
     * opens this screen directly, because it is what its settings button stands for.
     */
    @Keep class VirtualKeys : PrefFragment(R.xml.pref_virtual_keys)

    @Keep class Input : PrefFragment(R.xml.pref_input) {
        private val visibilityTests = mutableMapOf<Preference, (Map<String, Any?>) -> Boolean>()
        private val enablementTests = mutableMapOf<Preference, (Map<String, Any?>) -> Boolean>()
        private val prefChangeListener = OnSharedPreferenceChangeListener { _, _ -> applyTests() }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefChangeListener)

            val canChangePtrIcon = Build.VERSION.SDK_INT >= 24
            if (!canChangePtrIcon) {
                findPreference<SwitchPreference>("hide_local_cursor")!!.apply {
                    isEnabled = false
                    summary = getString(R.string.msg_ptr_hiding_not_supported)
                }
            }

            findPreference<SwitchPreference>("capture_pointer")!!.apply {
                showIf { Build.VERSION.SDK_INT >= 26 }
            }

            findPreference<ListPreferenceEx>("gesture_swipe1")!!.apply {
                // Touchpad mode hijacks this gesture. The key is absent from the map until
                // the user picks something, so the untouched case must fall back to the very
                // default that AppPreferences reads - otherwise the row looks editable while
                // the viewer ignores it.
                enableIf { (it["gesture_style"] as? String ?: "touchpad") != "touchpad" }
                disabledStateSummary = getString(R.string.pref_gesture_action_move_pointer)
            }

            // To reduce clutter & avoid 'UI overload', pref to invert vertical scrolling is
            // only visible when 'Scroll remote content' option is used.
            findPreference<SwitchPreference>("invert_vertical_scrolling")!!.apply {
                showIf { it.values.contains("remote-scroll") }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        }

        private fun Preference.enableIf(test: (Map<String, Any?>) -> Boolean) {
            enablementTests += this to test
            applyTests()
        }

        private fun Preference.showIf(test: (Map<String, Any?>) -> Boolean) {
            visibilityTests += this to test
            applyTests()
        }

        private fun applyTests() {
            val prefs = preferenceManager.sharedPreferences?.all ?: return
            visibilityTests.forEach { it.key.isVisible = it.value(prefs) }
            enablementTests.forEach { it.key.isEnabled = it.value(prefs) }
        }
    }

    @Keep class Server : PrefFragment(R.xml.pref_server) {
        private val authPrompt by lazy { DeviceAuthPrompt(requireActivity()) }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val savedServerLock = findPreference<SwitchPreference>("lock_saved_server")!!

            if (authPrompt.canLaunch()) {
                authPrompt.init({ savedServerLock.isChecked = !savedServerLock.isChecked }, { })
                savedServerLock.setOnPreferenceChangeListener { _, _ ->
                    authPrompt.launch(getString(R.string.title_unlock_dialog))
                    false
                }
            } else {
                savedServerLock.isEnabled = false
            }

            findPreference<Preference>("forget_known_hosts")!!.setOnPreferenceClickListener {
                ForgetKnownHostsDialog().show(childFragmentManager, "ForgetKnownHosts")
                true
            }
        }
    }

    @Keep class Tools : PrefFragment(R.xml.pref_tools) {

        // Shared with the backup screen this screen hosts: that one already holds the viewmodel
        // through the activity, and the duplicate sweep has to read and write the same database
        // the import path does.
        private val viewModel by activityViewModels<PrefsViewModel>()

        // Export lives on this screen rather than in the log viewer, and it gives back
        // exactly what the recording switch wrote: with nothing recorded there is
        // nothing to hand over, so no live logcat fallback - the system buffer is the
        // device's noise, not our logs. CreateDocument hands back a content uri; the
        // copy runs off the main thread because the capture file can be megabytes.
        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    PersistentLog.existing(requireContext())?.readText() ?: ""
                }
                try {
                    val written = withContext(Dispatchers.IO) {
                        requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray())
                        } != null
                    }
                    Toast.makeText(requireContext(),
                            if (written) getString(R.string.msg_logs_exported)
                            else getString(R.string.msg_logs_export_failed, ""),
                            Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(),
                            getString(R.string.msg_logs_export_failed, t.message ?: ""),
                            Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            // The dropdown drives the recording switch: off stops the app emitting log
            // lines anywhere (AppLogging, native side included) and kills the follow-mode
            // logcat process; the two recording modes switch emission back on and restart
            // the capture, with "session" first emptying the file so it holds only this
            // run's log. Stopping never deletes what was already recorded, so it can
            // still be exported from the row below.
            findPreference<ListPreference>("logging_mode")?.setOnPreferenceChangeListener { _, newValue ->
                val context = requireContext().applicationContext
                val mode = newValue as String
                AppLogging.setEnabled(mode != "off")
                PersistentLog.stop()
                if (mode != "off") PersistentLog.start(context, freshFile = mode == "session")
                true
            }

            findPreference<Preference>("export_logs")?.setOnPreferenceClickListener {
                if (PersistentLog.existing(requireContext()) == null) {
                    // Do not open the save dialog just to write an empty file.
                    Toast.makeText(requireContext(), R.string.msg_debug_no_logs, Toast.LENGTH_SHORT).show()
                } else {
                    exportLauncher.launch("connect-some-logs.txt")
                }
                true
            }

            // "All" means everything this screen's two neighbours can reach: the capture
            // file the dropdown writes and the logcat buffer the recent-logs screen reads.
            // Clearing works mid-capture too - the writer appends to the emptied file, so
            // the record simply restarts from here. Unlike the reset-all row above, this
            // asks first too: it is the other irreversible action on this screen.
            findPreference<Preference>("clear_all_logs")?.setOnPreferenceClickListener {
                confirmClearAllLogs()
                true
            }

            findPreference<Preference>("dedupe_servers")?.setOnPreferenceClickListener {
                confirmDedupeServers()
                true
            }

            findPreference<Preference>("reset_all_settings")?.setOnPreferenceClickListener {
                confirmResetAll()
                true
            }
        }

        // The sweep runs off the main thread and answers whenever it finishes, so the report goes
        // through the event rather than a return value. `onViewCreated` rather than
        // `onCreatePreferences`: the latter runs before this fragment has a view, and
        // `viewLifecycleOwner` has no answer yet at that point.
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            viewModel.dedupeFinishedEvent.observe(viewLifecycleOwner) { result ->
                // Wording happens here rather than in the viewmodel: this context carries the
                // language the user picked on the settings screen, the application one does not.
                result.onSuccess { count ->
                    val text = if (count == 0) getString(R.string.msg_dedupe_servers_none)
                    else getString(R.string.msg_dedupe_servers_done, count)
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                }
                        .onFailure { Toast.makeText(requireContext(),
                                it.message ?: getString(R.string.error_import_export_failed),
                                Toast.LENGTH_LONG).show() }
            }
        }

        // Deleting saved servers is not reversible from the UI, so it asks first - and says which
        // cards it means, because "duplicate" is the one thing a user may not mean: two servers
        // on the same address under different names are deliberately separate entries.
        private fun confirmDedupeServers() {
            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_dedupe_servers)
                    .setMessage(R.string.msg_dedupe_servers_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_dedupe) { _, _ -> viewModel.dedupeServers() }
                    .show()
        }

        // A reset that wipes every preference deserves one deliberate step, so it asks first.
        // The affirmative reads as the destructive action rather than a neutral "OK".
        private fun confirmResetAll() {
            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.action_reset_all_settings)
                    .setMessage(R.string.msg_reset_all_settings_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_reset) { _, _ -> resetAllSettings() }
                    .show()
        }

        // Same deliberate step for wiping the logs, in the same shape: title names the
        // action, the button is the verb, the message says what is lost.
        private fun confirmClearAllLogs() {
            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_clear_all_logs)
                    .setMessage(R.string.msg_clear_all_logs_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_clear_logs) { _, _ -> clearAllLogs() }
                    .show()
        }

        private fun clearAllLogs() {
            val app = requireContext().applicationContext
            PersistentLog.clear(app)
            Debugging.clearLogs()
            Toast.makeText(app, R.string.msg_debug_cleared, Toast.LENGTH_SHORT).show()
        }

        private fun resetAllSettings() {
            val app = requireContext().applicationContext
            // Stop the background logcat writer and the emission it was fed by first:
            // the switch is about to be wiped back to "off", and leaving either running
            // would keep a recorder nobody asked for.
            PersistentLog.stop()
            AppLogging.setEnabled(false)
            // Clear the one store AppPreferences and every settings widget reads from. Saved
            // servers live in the database, not here, so they are untouched by design. The backup
            // password protection switch is carried across the wipe on purpose: it is the gate
            // between someone holding the phone and the stored server passwords, so a reset must
            // not become a way to disarm it. No preference screen declares this key, so the
            // re-seed below cannot overwrite it back either.
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val keepBackupProtection = prefs.getBoolean("backup_password_enabled", false)
            prefs.edit().clear().putBoolean("backup_password_enabled", keepBackupProtection).apply()
            // Re-seed each screen's shipped defaults. pref_main only links the fragments and
            // owns no keys itself, so the leaf screens are seeded one by one; readAgain forces
            // the write past the once-only "defaults already seeded" marker.
            for (screen in intArrayOf(R.xml.pref_appearance, R.xml.pref_viewer, R.xml.pref_input,
                            R.xml.pref_server, R.xml.pref_virtual_keys, R.xml.pref_tools)) {
                PreferenceManager.setDefaultValues(app, screen, true)
            }
            Toast.makeText(app, R.string.msg_reset_all_settings_done, Toast.LENGTH_SHORT).show()
            // Rebuild the screen so every row shows the default just written, instead of the
            // value it held when the reset ran.
            requireActivity().recreate()
        }
    }

    companion object {
        /**
         * Class name of the single screen to show instead of the settings list. Anything the
         * viewer can reach this way has to be survivable as a root fragment.
         */
        const val EXTRA_START_FRAGMENT = "start_fragment"
    }
}