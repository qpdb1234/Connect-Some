/*
 * One place that puts the app's own language choice onto a context which cannot see it.
 *
 * The language picked in the interface settings lives in AndroidX's locale store. Below Android 13
 * the system has no notion of a per-app language, so AppCompat applies the stored value only to the
 * activities it decorates; every other context - the application context above all, since it is
 * created once and never recreated - still resolves strings in the device's language. Anything
 * drawn from that context then reads in a language the user never chose, which is exactly what
 * happened to the floating LocalSend card: it has to outlive the page that opened it, so it is
 * inflated on the application context and came up in English on a Chinese app.
 *
 * [inAppLanguage] closes that gap for the contexts that need it. It is deliberately a no-op when
 * the app follows the system language, so a call site never has to know which case it is in.
 */
package com.limelight.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * A context that resolves its resources in the language picked inside the app rather than the
 * device's, or this one when the app has no language of its own and follows the system.
 *
 * Only the locale is overridden; the screen size and density of the original configuration are
 * carried along untouched, so measurement done through the result still matches the display. The
 * theme has to be re-applied by hand, because [Context.createConfigurationContext] returns a brand
 * new context whose theme starts unset - left alone, a view inflated from it would be styled from
 * the platform default instead of from the application's theme.
 */
@Suppress("DEPRECATION")
fun Context.inAppLanguage(): Context {
    val stored = AppCompatDelegate.getApplicationLocales()
    if (stored.isEmpty) return this

    // The whole list cannot be handed over: Configuration exposes a public setter for a single
    // Locale only (the list form is framework-internal), and that setter updates the list
    // underneath it. Nothing here ever stores a second language anyway - the picker in the
    // settings writes exactly one - so the first stored locale is the answer.
    val locale = stored.get(0) ?: return this

    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config).apply {
        val theme = applicationInfo?.theme ?: 0
        if (theme != 0) setTheme(theme)
    }
}
