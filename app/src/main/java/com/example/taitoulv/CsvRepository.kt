package com.example.taitoulv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 明细表里的一行 */
data class CsvSample(
    val seconds: Float,
    val visible: Int,
    val headUp: Int,
    val headDown: Int,
    val lying: Int,
    val eyesClosed: Int,
    val instant: Float?,
    val rate5: Float?,
    val rate30: Float?,
    val minFacePx: Int,
    val fps: Float
)

/** 事件表里的一行 */
data class CsvEventRow(
    val type: String,
    val trackingId: Int,
    val startSec: Float,
    val endSec: Float,
    val durationSec: Float,
    val minPitch: Float
) {
    val isLying: Boolean get() = type == LYING_LABEL
}

/**
 * CSV 文件的读取与管理。
 * 解析部分是纯 JVM 逻辑（不碰 Android API），所以可以直接单元测试。
 */
/** 事件类型标签（CSV 里就是这么写的） */
const val LYING_LABEL = "趴桌"
const val HEAD_DOWN_LABEL = "低头"

object CsvRepository {

    private const val DETAIL_PREFIX = "抬头率明细_"
    private const val EVENT_PREFIX = "低头事件_"

    data class CsvFileInfo(val file: File) {
        val isEventFile: Boolean get() = file.name.startsWith(EVENT_PREFIX)
        val stamp: String
            get() = file.name.removeSuffix(".csv")
                .removePrefix(if (isEventFile) EVENT_PREFIX else DETAIL_PREFIX)
    }

    /** 记录文件所在目录（App 外部私有目录，不需要存储权限） */
    fun dataDir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

    /** 按修改时间倒序列出所有 csv */
    fun listFiles(context: Context): List<CsvFileInfo> =
        dataDir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { CsvFileInfo(it) }
            .orEmpty()

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun rowCount(file: File): Int =
        runCatching { file.useLines { it.count { line -> line.isNotBlank() } } }.getOrDefault(0)

    fun sizeText(file: File): String = when {
        file.length() >= 1024 * 1024 -> "%.1f MB".format(file.length() / 1024f / 1024f)
        file.length() >= 1024 -> "%.1f KB".format(file.length() / 1024f)
        else -> "${file.length()} B"
    }

    fun timeText(file: File): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(file.lastModified()))

    fun modifiedDesc(file: File): Long = file.lastModified()

    /** 明细文件对应的同名事件文件（抬头率明细_xxx.csv → 低头事件_xxx.csv） */
    fun eventFileFor(detailFile: File): File? {
        val name = detailFile.name
        if (!name.startsWith(DETAIL_PREFIX)) return null
        return File(detailFile.parentFile, name.replace(DETAIL_PREFIX, EVENT_PREFIX))
    }

    /** 解析明细表；表头（第二列是「相对秒」）会自动跳过 */
    fun readDetail(file: File): List<CsvSample> {
        val out = ArrayList<CsvSample>()
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val f = line.split(',')
                if (f.size < 13) return@forEachLine
                val seconds = f[1].toFloatOrNull() ?: return@forEachLine
                out += CsvSample(
                    seconds = seconds,
                    visible = f[2].toIntOrNull() ?: 0,
                    headUp = f[3].toIntOrNull() ?: 0,
                    headDown = f[4].toIntOrNull() ?: 0,
                    lying = f[5].toIntOrNull() ?: 0,
                    eyesClosed = f[6].toIntOrNull() ?: 0,
                    instant = f[7].toFloatOrNull(),
                    rate5 = f[8].toFloatOrNull(),
                    rate30 = f[9].toFloatOrNull(),
                    minFacePx = f[10].toIntOrNull() ?: 0,
                    fps = f[12].toFloatOrNull() ?: 0f
                )
            }
        }
        return out
    }

    /** 解析事件表；表头会自动跳过 */
    fun readEvents(file: File): List<CsvEventRow> {
        val out = ArrayList<CsvEventRow>()
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val f = line.split(',')
                if (f.size < 6) return@forEachLine
                val start = f[2].toFloatOrNull() ?: return@forEachLine
                out += CsvEventRow(
                    type = f[0].trim(),
                    trackingId = f[1].toIntOrNull() ?: -1,
                    startSec = start,
                    endSec = f[3].toFloatOrNull() ?: start,
                    durationSec = f[4].toFloatOrNull() ?: 0f,
                    minPitch = f[5].toFloatOrNull() ?: 0f
                )
            }
        }
        return out
    }

    /** 一句话汇总，显示在图表上方 */
    fun summaryOf(samples: List<CsvSample>, events: List<CsvEventRow>): Summary? {
        if (samples.isEmpty()) return null
        val rates = samples.mapNotNull { it.rate30 ?: it.instant }
        if (rates.isEmpty()) return null
        return Summary(
            durationSec = samples.last().seconds,
            avgRate = rates.average().toFloat(),
            minRate = rates.min(),
            maxRate = rates.max(),
            avgVisible = samples.map { it.visible }.average().toFloat(),
            headDownEvents = events.count { !it.isLying },
            lyingEvents = events.count { it.isLying }
        )
    }

    data class Summary(
        val durationSec: Float,
        val avgRate: Float,
        val minRate: Float,
        val maxRate: Float,
        val avgVisible: Float,
        val headDownEvents: Int,
        val lyingEvents: Int
    )
}
