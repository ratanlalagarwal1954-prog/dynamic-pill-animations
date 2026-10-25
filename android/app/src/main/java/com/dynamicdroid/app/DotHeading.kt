package com.dynamicdroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Original dot-matrix lettering, generated locally rather than a proprietary brand font. */
class DotHeading(context: Context) : View(context) {
    private val dots = ArrayList<Pair<Float, Float>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NothingStyle.foreground }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        dots.clear()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = NothingStyle.mono
            textSize = 32f * resources.displayMetrics.density
        }
        if (text.measureText("DYNAMIC") > w) text.textSize *= w / text.measureText("DYNAMIC")
        val baseline = (h - text.fontMetrics.bottom - text.fontMetrics.top) / 2f
        canvas.drawText("DYNAMIC", 0f, baseline, text)
        val step = (3f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        for (y in step / 2 until h step step) for (x in step / 2 until w step step) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 100) dots.add(x.toFloat() to y.toFloat())
        }
        bitmap.recycle()
        contentDescription = "Dynamic Droid"
    }
    override fun onDraw(canvas: Canvas) {
        val radius = resources.displayMetrics.density * 0.85f
        dots.forEach { (x, y) -> canvas.drawCircle(x, y, radius, paint) }
    }
}