package com.example.taitoulv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 抬头率曲线：绿色粗线 = 30 秒滑动平均，灰色细线 = 即时值，
 * 红色竖线 = 低头/趴桌事件发生的时刻。
 * 不依赖任何图表库，直接 Canvas 画。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x22FFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 11f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }

    private var samples: List<CsvSample> = emptyList()
    private var events: List<CsvEventRow> = emptyList()

    fun setData(newSamples: List<CsvSample>, newEvents: List<CsvEventRow>) {
        samples = newSamples
        events = newEvents
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padLeft = 46f * density
        val padRight = 14f * density
        val padTop = 30f * density
        val padBottom = 24f * density
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom
        if (plotW <= 0f || plotH <= 0f) return

        drawLegend(canvas, padLeft, padTop)

        // Y 轴网格 0/25/50/75/100
        listOf(0, 25, 50, 75, 100).forEach { v ->
            val y = padTop + plotH * (1f - v / 100f)
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint)
            canvas.drawText("$v%", 4f * density, y + 4f * density, textPaint)
        }

        if (samples.size < 2) {
            canvas.drawText("数据太少，至少需要两条记录", width / 2f, padTop + plotH / 2f, centerPaint)
            return
        }

        val maxSec = max(samples.last().seconds, 1f)
        fun xOf(sec: Float) = padLeft + plotW * (sec / maxSec)
        fun yOf(rate: Float) = padTop + plotH * (1f - rate.coerceIn(0f, 100f) / 100f)

        // X 轴刻度（时间 mm:ss）
        val ticks = 4
        for (i in 0..ticks) {
            val sec = maxSec * i / ticks
            val x = xOf(sec)
            canvas.drawLine(x, padTop, x, padTop + plotH, gridPaint)
            val label = "%d:%02d".format((sec / 60).toInt(), (sec % 60).toInt())
            val labelWidth = textPaint.measureText(label)
            // 末尾刻度别被右边缘切掉
            canvas.drawText(label, (x - labelWidth).coerceIn(0f, width - labelWidth), padTop + plotH + 16f * density, textPaint)
        }

        // 30 秒平均：填充 + 粗线
        drawSeries(canvas, ::xOf, ::yOf, samples.map { it.seconds to it.rate30 }, 0xFF34C759.toInt(), 2.5f, true)
        // 即时值：细线
        drawSeries(canvas, ::xOf, ::yOf, samples.map { it.seconds to it.instant }, 0x66FFFFFF, 1.2f, false)

        // 事件竖线
        events.forEach { event ->
            val x = xOf(event.startSec.coerceIn(0f, maxSec))
            linePaint.color = if (event.isLying) 0xFFAF52DE.toInt() else 0xFFFF3B30.toInt()
            linePaint.strokeWidth = 2f * density
            linePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
            canvas.drawLine(x, padTop, x, padTop + plotH, linePaint)
            linePaint.pathEffect = null
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        xOf: (Float) -> Float,
        yOf: (Float) -> Float,
        points: List<Pair<Float, Float?>>,
        color: Int,
        strokeWidthDp: Float,
        fill: Boolean
    ) {
        val valid = points.filter { it.second != null }
        if (valid.size < 2) return

        val path = Path()
        val fillPath = Path()
        valid.forEachIndexed { index, (sec, rate) ->
            val x = xOf(sec)
            val y = yOf(rate!!)
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height.toFloat() - 24f * density)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        if (fill) {
            fillPath.lineTo(xOf(valid.last().first), height.toFloat() - 24f * density)
            fillPath.close()
            fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
            canvas.drawPath(fillPath, fillPaint)
        }

        linePaint.color = color
        linePaint.strokeWidth = strokeWidthDp * density
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(path, linePaint)
    }

    private fun drawLegend(canvas: Canvas, padLeft: Float, padTop: Float) {
        var x = padLeft
        val y = padTop - 14f * density

        fun legend(color: Int, label: String) {
            linePaint.color = color
            linePaint.strokeWidth = 2.5f * density
            canvas.drawLine(x, y, x + 18f * density, y, linePaint)
            x += 22f * density
            canvas.drawText(label, x, y + 4f * density, textPaint)
            x += textPaint.measureText(label) + 14f * density
        }

        legend(0xFF34C759.toInt(), "30s 平均")
        legend(0x99FFFFFF.toInt(), "即时")
        legend(0xFFFF3B30.toInt(), "低头事件")
    }
}
