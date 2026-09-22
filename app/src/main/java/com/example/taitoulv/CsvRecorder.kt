package com.example.taitoulv

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 记录器（进程内单例）。
 *
 * **只有点了「开始记录」才开始写**：[start] 时才创建两个文件并写表头，[stop] 时 flush + 关闭。
 * 因为是进程级单例，转屏重建 Activity 不会中断记录；进程被杀则自然回到「未记录」状态。
 *
 * 两个文件放在 App 的外部私有目录（不需要存储权限）：
 * - `抬头率明细_yyyyMMdd_HHmmss.csv`：每 5 秒一行
 * - `低头事件_yyyyMMdd_HHmmss.csv`：每次低头/趴桌事件一行
 */
class CsvRecorder private constructor(private val context: Context) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var detailWriter: BufferedWriter? = null
    private var eventWriter: BufferedWriter? = null

    var detailFile: File? = null
        private set
    var eventFile: File? = null
        private set

    /** 本次记录的起点，开始记录时设定 */
    var sessionStartMs = 0L
        private set

    var isRecording = false
        private set

    var detailRows = 0
        private set
    var eventRows = 0
        private set

    private var lastDetailMs = 0L

    val directoryPath: String get() = dataDir(context).absolutePath

    /** 开始记录：创建文件 + 写表头。返回是否成功 */
    @Synchronized
    fun start(): Boolean {
        if (isRecording) return true
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val dir = dataDir(context)
        val detail = File(dir, "抬头率明细_$stamp.csv")
        val event = File(dir, "低头事件_$stamp.csv")

        return runCatching {
            detailWriter = open(detail).also {
                it.write("\uFEFF时间,相对秒,可见人数,抬头,低头,趴桌,闭眼,即时抬头率,5秒抬头率,30秒抬头率,最小人脸像素,低头阈值,帧率\n")
                it.flush()
            }
            eventWriter = open(event).also {
                it.write("\uFEFF类型,跟踪ID,开始秒,结束秒,时长秒,最低俯仰角,最低睁眼概率\n")
                it.flush()
            }
            detailFile = detail
            eventFile = event
            detailRows = 0
            eventRows = 0
            lastDetailMs = 0L
            sessionStartMs = SystemClock.elapsedRealtime()
            isRecording = true
            true
        }.getOrElse {
            stop()
            false
        }
    }

    /** 停止记录：flush 并关闭文件 */
    @Synchronized
    fun stop() {
        runCatching {
            detailWriter?.flush()
            eventWriter?.flush()
        }
        runCatching {
            detailWriter?.close()
            eventWriter?.close()
        }
        detailWriter = null
        eventWriter = null
        isRecording = false
    }

    /** 每 5 秒记一行明细；没在记录时什么都不做 */
    @Synchronized
    fun maybeWriteDetail(nowMs: Long, snapshot: TrackerSnapshot, threshold: Float) {
        val writer = detailWriter ?: return
        if (nowMs - lastDetailMs < DETAIL_INTERVAL_MS) return
        lastDetailMs = nowMs

        val seconds = (nowMs - sessionStartMs) / 1000.0
        val row = listOf(
            timeFormat.format(Date()),
            "%.1f".format(seconds),
            snapshot.total.toString(),
            snapshot.headUp.toString(),
            snapshot.headDown.toString(),
            snapshot.lying.toString(),
            snapshot.eyesClosed.toString(),
            "%.1f".format(snapshot.instantRate),
            snapshot.window5Rate?.let { "%.1f".format(it) } ?: "",
            "%.1f".format(snapshot.window30Rate ?: snapshot.instantRate),
            snapshot.minFacePx.toString(),
            "%.0f".format(threshold),
            "%.1f".format(snapshot.fps)
        ).joinToString(",")

        runCatching {
            writer.write(row)
            writer.newLine()
            writer.flush()
            detailRows++
        }
    }

    /** 事件即时落盘；没在记录时什么都不做 */
    @Synchronized
    fun writeEvents(events: List<AttentionEvent>) {
        val writer = eventWriter ?: return
        if (events.isEmpty()) return

        runCatching {
            events.forEach { event ->
                val row = listOf(
                    event.type.label,
                    event.trackingId.toString(),
                    "%.1f".format((event.startMs - sessionStartMs) / 1000.0),
                    "%.1f".format((event.endMs - sessionStartMs) / 1000.0),
                    "%.1f".format(event.durationMs / 1000.0),
                    "%.1f".format(event.minPitch),
                    event.minEyesOpen?.let { "%.2f".format(it) } ?: ""
                ).joinToString(",")
                writer.write(row)
                writer.newLine()
            }
            writer.flush()
            eventRows += events.size
        }
    }

    @Synchronized
    fun flush() {
        runCatching {
            detailWriter?.flush()
            eventWriter?.flush()
        }
    }

    companion object {
        private const val DETAIL_INTERVAL_MS = 5_000L

        @Volatile
        private var instance: CsvRecorder? = null

        fun get(context: Context): CsvRecorder =
            instance ?: synchronized(this) {
                instance ?: CsvRecorder(context.applicationContext).also { instance = it }
            }

        private fun dataDir(context: Context): File =
            (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

        private fun open(file: File): BufferedWriter =
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    }
}
