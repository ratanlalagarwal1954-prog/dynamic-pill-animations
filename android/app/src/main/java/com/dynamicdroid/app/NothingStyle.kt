package com.dynamicdroid.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/** Nothing-inspired palette, without using proprietary logos or fonts. */
object NothingStyle {
    val background = Color.rgb(13, 13, 13)
    val surface = Color.rgb(30, 30, 30)
    val foreground = Color.rgb(242, 242, 242)
    val muted = Color.rgb(157, 157, 157)
    val red = Color.rgb(235, 48, 48)
    val line = Color.rgb(55, 55, 55)
    val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)

    fun panel(color: Int = surface) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 8f
        setStroke(1, line)
    }
}