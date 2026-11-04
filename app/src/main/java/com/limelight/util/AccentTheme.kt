/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.res.Resources
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.limelight.App
import com.limelight.R
import kotlin.math.abs

/**
 * Runtime accent color chosen with the color picker in the settings.
 *
 * The picker is continuous - any color, reachable by dragging or by typing a code - and the
 * chosen ARGB is stored as hex. A theme, however, can only point at compile-time resources and
 * Android exposes no public API to set a single theme color at runtime (on any level), so a
 * colored choice is rendered by its nearest pre-generated variant: 72 hues x 4 saturations x 4
 * values (see gen_accent.xml). Grays are the exception - a neutral (R == G == B) is matched to
 * an exact 256-step grayscale axis (App.Theme.Gray0..Gray255 in gen_accent_gray.xml), so every
 * pure gray, black and white included, renders literally rather than snapping. The champagne-
 * gold default is likewise literal. Light/dark keeps working through the DayNight parent, so
 * the two settings stay orthogonal. Unlike the accent, the assist ball is tinted directly at
 * runtime and therefore reproduces its chosen color exactly.
 */
object AccentTheme {

    // Must mirror gen_accent.xml.
    const val HUE_STEP = 5
    val SAT_LEVELS = floatArrayOf(0.12f, 0.45f, 0.75f, 1.00f)
    val VAL_LEVELS = floatArrayOf(0.55f, 0.75f, 0.90f, 1.00f)

    /**
     * The color a fresh install runs on, and the one the picker shows as its default.
     * A champagne gold, spelled exactly like `accent_default` in colors.xml: App.Theme carries
     * the same color so that [variantFor] can answer with it.
     *
     * Unlike every other accent, it is deliberately not one of the generated variants - it is
     * the one color that is rendered literally rather than snapped to the grid. That costs one
     * thing: the picker's nearest-variant math can never land back here by dragging, because
     * the nearest grid point to this color is a different, duller gold. Typing its code does
     * reach it, since [argbOf] hands the exact ARGB straight to [variantFor].
     */
    val DEFAULT_ARGB: Int = 0xFFEECE6D.toInt()

    // Names stored by the earlier preset picker; still accepted so existing installs
    // keep their color. "purple" is also what those installs saw as the default before
    // the interface settled on gold.
    private val LEGACY_NAMES = mapOf(
        "purple" to 0xFF6200EE.toInt(),
        "red" to 0xFFF44336.toInt(),
        "pink" to 0xFFE91E63.toInt(),
        "deepPurple" to 0xFF673AB7.toInt(),
        "indigo" to 0xFF3F51B5.toInt(),
        "blue" to 0xFF2196F3.toInt(),
        "lightBlue" to 0xFF03A9F4.toInt(),
        "cyan" to 0xFF00BCD4.toInt(),
        "teal" to 0xFF009688.toInt(),
        "green" to 0xFF4CAF50.toInt(),
        "lightGreen" to 0xFF8BC34A.toInt(),
        "lime" to 0xFFCDDC39.toInt(),
        "yellow" to 0xFFFFEB3B.toInt(),
        "amber" to 0xFFFFC107.toInt(),
        "orange" to 0xFFFF9800.toInt(),
        "deepOrange" to 0xFFFF5722.toInt(),
        "brown" to 0xFF795548.toInt(),
        "gray" to 0xFF9E9E9E.toInt(),
        "blueGray" to 0xFF607D8B.toInt(),
    )

    /** Turns a stored preference value (hex, or a legacy name) into an ARGB color. */
    fun argbOf(stored: String?): Int {
        if (stored == null) return DEFAULT_ARGB
        if (stored.startsWith("#")) {
            val digits = stored.substring(1)
            try {
                if (digits.length == 6) return (0xFF000000L or digits.toLong(16)).toInt()
                if (digits.length == 8) return digits.toLong(16).toInt()
            } catch (e: NumberFormatException) {
                return DEFAULT_ARGB
            }
        }
        return LEGACY_NAMES[stored] ?: DEFAULT_ARGB
    }

    fun hexOf(argb: Int): String = String.format("#%08X", argb)

    /** The color of one picker cell - shared by the view and the nearest-variant math. */
    fun rgbOf(hue: Float, satIdx: Int, valIdx: Int): Int =
        Color.HSVToColor(floatArrayOf(hue, SAT_LEVELS[satIdx], VAL_LEVELS[valIdx]))

    fun nearestLevel(value: Float, levels: FloatArray): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        levels.forEachIndexed { i, l ->
            val d = abs(l - value)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    private val styleCache = HashMap<String, Int>()

    /** The pre-generated theme variant that renders [argb] (exactly, or to its nearest). */
    fun variantFor(res: Resources, argb: Int): Int {
        // The champagne-gold default is off the grid, so this early return is the only way it
        // renders exactly; a colored choice below is rounded to its nearest grid neighbor.
        if (argb == DEFAULT_ARGB)
            return R.style.App_Theme_Gold

        // A neutral (R == G == B) is a gray, which the hue/saturation grid cannot represent: its
        // lowest saturation is 0.12 (so grays come out tinted) and its lowest value 0.55 (so dark
        // grays come out lifted). Grays get their own exact 256-step axis instead, so any pure
        // gray - black and white at the ends included - renders literally.
        val level = Color.red(argb)
        if (level == Color.green(argb) && level == Color.blue(argb))
            return grayFor(res, level)

        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        val h = (Math.round(hsv[0] / HUE_STEP) * HUE_STEP) % 360
        val s = nearestLevel(hsv[1], SAT_LEVELS)
        val v = nearestLevel(hsv[2], VAL_LEVELS)

        // getIdentifier wants the resource entry name as written in gen_accent.xml -
        // with dots. The R class field name (all underscores) would never be found.
        val name = "App.Theme.H${h}S${s}V${v}"
        val cached = styleCache[name]
        if (cached != null) return cached

        val id = res.getIdentifier(name, "style", res.getResourcePackageName(R.style.App_Theme))
            .let { if (it != 0) it else R.style.App_Theme }
        styleCache[name] = id
        return id
    }

    private val grayCache = HashMap<Int, Int>()

    /**
     * The exact gray theme variant for a neutral channel [level] (0 = pure black, 255 = pure
     * white). Resolved by name against gen_accent_gray.xml and cached, like the hue grid above.
     */
    private fun grayFor(res: Resources, level: Int): Int {
        val cached = grayCache[level]
        if (cached != null) return cached
        val id = res.getIdentifier("App.Theme.Gray$level", "style",
                res.getResourcePackageName(R.style.App_Theme))
            .let { if (it != 0) it else R.style.App_Theme }
        grayCache[level] = id
        return id
    }

    /**
     * Applies the selected accent theme to [activity] and rebuilds it whenever the
     * preference changes while it is alive. Call at the very start of onCreate(),
     * before the superclass gets the activity - the theme has to be set before any
     * view is inflated.
     */
    @JvmStatic
    fun attach(activity: ComponentActivity) {
        val prefs = (activity.application as App).prefs
        var applied = argbOf(prefs.ui.accentColor)
        activity.setTheme(variantFor(activity.resources, applied))

        // Comparing on every resume rather than observing the live preference: an
        // activity that was in the background when the color changed cannot be reached
        // by a recreate at that moment, and LiveData would not replay the unchanged
        // value once it came back - it would have kept showing the old accent forever.
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = argbOf(prefs.ui.accentColor)
                if (current != applied) {
                    // Runs before the rebuilt instance's onCreate, so the theme it
                    // applies is the new one and this does not fire twice.
                    applied = current
                    activity.recreate()
                }
            }
        })
    }
}
