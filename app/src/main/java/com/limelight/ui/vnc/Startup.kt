/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.limelight.model.ServerProfile
import com.limelight.model.db.MainDb
import com.limelight.model.db.ServerProfileDao
import com.limelight.viewmodel.VncViewModel
import com.limelight.vnc.VncUri


/**********************************************************************************************
 * [VncActivity] startup utilities
 *********************************************************************************************/
private const val PROFILE_KEY = "com.limelight.server_profile"
private const val PROFILE_ID_KEY = "com.limelight.server_profile_id"

fun Bundle.putProfile(profile: ServerProfile) {
    putParcelable(PROFILE_KEY, profile)
}

fun Bundle.getProfile(): ServerProfile? {
    return BundleCompat.getParcelable(this, PROFILE_KEY, ServerProfile::class.java)
}

fun createVncIntent(context: Context, profile: ServerProfile): Intent {
    return Intent(context, VncActivity::class.java).apply {
        if (profile.isSaved())
            putExtra(PROFILE_ID_KEY, profile.ID)
        else
            putExtra(PROFILE_KEY, profile)
    }
}

fun startVncActivity(source: Activity, profile: ServerProfile) {
    // Since the mini window leaves the main screen reachable, a server can be reopened while
    // it is still streaming into a window. That takes the window - and the session behind it
    // - down first: two streams to one machine, one of them invisible, would be pure
    // confusion. Other servers are untouched, which is what lets a windowed session keep
    // running underneath a second one.
    MiniWindow.endWindowedSession(profile.ID)
    source.startActivity(createVncIntent(source, profile))
}

fun startVncActivity(source: Activity, uri: VncUri) {
    startVncActivity(source, uri.toServerProfile())
}


/**********************************************************************************************
 * Argument parser
 *********************************************************************************************/
sealed class StartupArg {
    data class Profile(val profile: ServerProfile) : StartupArg()
    data class ProfileId(val id: Long) : StartupArg()
}

class MissingStartupArgException : Exception()

fun parseStartupArg(intent: Intent, savedState: Bundle?): StartupArg {
    // Prefer to use profile if available to keep changes across activity restarts.
    val profile = savedState?.getProfile() ?: intent.extras?.getProfile()
    if (profile != null)
        return StartupArg.Profile(profile.copy())  //Create a copy to avoid modification to source profile


    val id = intent.getLongExtra(PROFILE_ID_KEY, 0)
    if (id != 0L)
        return StartupArg.ProfileId(id)

    throw MissingStartupArgException()
}

/**********************************************************************************************
 * Session start
 *********************************************************************************************/

/**
 * The id in the startup argument has no row any more - the server was deleted while a
 * shortcut, a pending intent or a restored state still pointed at it.
 *
 * This message goes to the log. What the user is told is chosen by
 * `VncActivity.handleStartupFailure`, from resources.
 */
class InvalidProfileIdException(val id: Long) : Exception("No server profile with id $id")

suspend fun startSession(startupArg: StartupArg, viewModel: VncViewModel) {
    val dao = MainDb.getInstance(viewModel.app).serverProfileDao
    val profile = loadProfile(startupArg, dao)
    viewModel.initConnection(profile)
}

private suspend fun loadProfile(startupArg: StartupArg, dao: ServerProfileDao): ServerProfile {
    when (startupArg) {
        is StartupArg.Profile -> return startupArg.profile
        is StartupArg.ProfileId -> return dao.getByID(startupArg.id)
                                          ?: throw InvalidProfileIdException(startupArg.id)
    }
}