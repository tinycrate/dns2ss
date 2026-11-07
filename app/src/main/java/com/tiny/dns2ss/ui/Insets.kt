package com.tiny.dns2ss.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Adds the status-bar/navigation-bar/cutout insets to whatever padding the view
 * already has. Call after the content view is set.
 */
fun View.applySystemBarInsets() {
    val initial = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(
            initial[0] + bars.left,
            initial[1] + bars.top,
            initial[2] + bars.right,
            initial[3] + bars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
