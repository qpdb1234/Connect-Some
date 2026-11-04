/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.limelight.R
import com.limelight.util.AccentTheme
import com.limelight.util.EdgeToEdgeHelper

/**
 * Activity for app details.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        super.onCreate(savedInstanceState)

        EdgeToEdgeHelper.setContentView(this, R.layout.activity_about)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_host, AboutFragment())
                    .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}