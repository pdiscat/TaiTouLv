package com.example.taitoulv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 要画在预览上的一张脸：坐标已是 PreviewView 坐标系 */
data class FaceMark(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val state: FaceState,
    val pitchDegrees: Float,
    val eyesClosed: Boolean
)

/**
 * 把检测结果画到预览画面上：
 * 绿色 = 抬头，红色 = 低头，紫色 = 趴桌；闭眼会在标签里标出来。
 *
 * 坐标换算已经由 CameraX 的 MlKitAnalyzer 做完（结果就是 PreviewView 坐标），这里不做事。
 */
class HeadUpOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    private var marks: List<FaceMark> = emptyList()
    private var mapped = false

    init {
        val density = resources.displayMetrics.density
        boxPaint.strokeWidth = 3f * density
        labelPaint.textSize = 14f * density
    }

    fun setFaces(newMarks: List<FaceMark>, boxesMapped: Boolean) {
        marks = newMarks
        mapped = boxesMapped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!mapped) return

        for (mark in marks) {
            val color = colorFor(mark.state)

            boxPaint.color = color
            rect.set(mark.left, mark.top, mark.right, mark.bottom)
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            val label = labelFor(mark)
            val labelHeight = labelPaint.textSize + 8f
            val labelTop = (rect.top - labelHeight).coerceAtLeast(0f)
            val labelRight = rect.left + labelPaint.measureText(label) + 12f

            labelBgPaint.color = color
            canvas.drawRect(rect.left, labelTop, labelRight, labelTop + labelHeight, labelBgPaint)
            canvas.drawText(label, rect.left + 6f, labelTop + labelPaint.textSize, labelPaint)
        }
    }

    private fun labelFor(mark: FaceMark): String {
        val base = when (mark.state) {
            FaceState.HEAD_UP -> "抬头"
            FaceState.HEAD_DOWN -> "低头"
            FaceState.LYING -> "趴桌"
        }
        val eyes = if (mark.eyesClosed) " 闭眼" else ""
        return "$base ${mark.pitchDegrees.toInt()}°$eyes"
    }

    private fun colorFor(state: FaceState): Int = when (state) {
        FaceState.HEAD_UP -> COLOR_HEAD_UP
        FaceState.HEAD_DOWN -> COLOR_HEAD_DOWN
        FaceState.LYING -> COLOR_LYING
    }

    companion object {
        private const val COLOR_HEAD_UP = 0xFF34C759.toInt()
        private const val COLOR_HEAD_DOWN = 0xFFFF3B30.toInt()
        private const val COLOR_LYING = 0xFFAF52DE.toInt()
    }
}
