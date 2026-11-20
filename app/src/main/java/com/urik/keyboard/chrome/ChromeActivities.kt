package com.urik.keyboard.chrome

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat

/**
 * Carries the settable ground and text roles to every activity's window and toolbar — the two things no
 * inflated widget covers (with the roles at their defaults that part changes nothing) — and gives the rows
 * of every plain settings page their icons.
 */
object ChromeActivities : Application.ActivityLifecycleCallbacks {
    /** The pages built by hand carry their own icons; every other settings list gets them here. */
    private val preferencePages = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f is PreferenceFragmentCompat && f !is com.urik.keyboard.settings.keyboardui.KeyboardUiFragment) {
                try {
                    f.preferenceScreen?.let { ChromePreferences.decorate(it) }
                    f.listView?.let { ChromePreferences.tighten(it) }
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        (activity as? FragmentActivity)?.supportFragmentManager
            ?.registerFragmentLifecycleCallbacks(preferencePages, true)
        if (!ChromeColors.customised) return
        try {
            activity.window.setBackgroundDrawable(ColorDrawable(ChromeColors.background))
            activity.window.statusBarColor = ChromeColors.background
            activity.window.navigationBarColor = ChromeColors.background
        } catch (_: Throwable) {
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (!ChromeColors.customised) return
        try {
            tint(activity.window.decorView)
        } catch (_: Throwable) {
        }
    }

    private fun tint(v: View) {
        if (v is Toolbar) {
            v.setTitleTextColor(ChromeColors.text)
            v.navigationIcon?.setTint(ChromeColors.accent)
            v.overflowIcon?.setTint(ChromeColors.accent)
            v.setBackgroundColor(ChromeColors.background)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) tint(v.getChildAt(i))
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
