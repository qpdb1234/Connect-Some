package com.limelight.util

import android.app.Dialog
import android.os.Bundle
import com.limelight.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder


/**
 * Shows a dialog without the crash that [DialogFragment.show] carries.
 *
 * `show()` commits an ordinary transaction, and an observer of a `LiveData` wakes at
 * STARTED - which, on the way back to the foreground, is while the host's state has already
 * been written. The commit then throws `IllegalStateException`, from the one path that is
 * supposed to *report* something: an import that failed, a login the server refused. Adding
 * the fragment while allowing state loss shows the same dialog, and only gives up restoring
 * it if the process dies behind it.
 */
fun DialogFragment.showSafely(manager: FragmentManager, tag: String?) {
    if (!manager.isStateSaved) {
        try {
            show(manager, tag)
            return
        } catch (e: IllegalStateException) {
            // The host saved its state between the check and the commit.
            Log.d(javaClass.simpleName, "Host state was saved while showing $tag", e)
        }
    }

    runCatching {
        manager.beginTransaction().add(this, tag).commitAllowingStateLoss()
    }.onFailure { Log.e(javaClass.simpleName, "Cannot show $tag", it) }
}


object MsgDialog {

    /**
     * Shows a dialog with given title & message,
     */
    fun show(manager: FragmentManager, title: CharSequence, msg: CharSequence) {
        val fragment = MsgDialogFragment()
        val args = Bundle(2)

        args.putCharSequence("title", title)
        args.putCharSequence("msg", msg)
        fragment.arguments = args

        fragment.showSafely(manager, null)
    }

    class MsgDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return MaterialAlertDialogBuilder(requireContext())
                    .setTitle(requireArguments().getCharSequence("title"))
                    .setMessage(requireArguments().getCharSequence("msg"))
                    .setPositiveButton(android.R.string.ok) { _, _ -> /* Let it dismiss */ }
                    .create()
        }
    }
}