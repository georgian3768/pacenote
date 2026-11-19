package com.pocketmechanic.spike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Thick rounded frame around the whole screen for a cue flash. Click-through by
 * the window flag; shared by the legacy and Compose overlays so the flash looks
 * identical in both.
 */
internal class OverlayBorderView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * context.resources.displayMetrics.density
        color = COLOR_UP
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2f
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 28f, 28f, paint)
    }

    companion object {
        val COLOR_UP = Color.rgb(46, 194, 126)
        val COLOR_DOWN = Color.rgb(229, 72, 77)
    }
}
