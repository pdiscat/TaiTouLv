package com.example.taitoulv

/** 单张脸当前的状态 */
enum class FaceState { HEAD_UP, HEAD_DOWN, LYING }

enum class AttentionEventType(val label: String) {
    HEAD_DOWN("低头"),
    LYING("趴桌")
}

/**
 * 一次「持续低头 / 趴桌」事件。
 * 判定口径：同一跟踪目标连续满足条件 ≥ 阈值时长才记一次事件，避免单帧抖动刷出一堆假事件。
 */
data class AttentionEvent(
    val type: AttentionEventType,
    val trackingId: Int,
    val startMs: Long,
    var endMs: Long,
    var minPitch: Float,
    var minEyesOpen: Float?
) {
    val durationMs: Long get() = endMs - startMs
}

/** 一帧的统计快照，UI 与 CSV 都用它 */
data class TrackerSnapshot(
    val total: Int = 0,
    val headUp: Int = 0,
    val headDown: Int = 0,
    val lying: Int = 0,
    val eyesClosed: Int = 0,
    val minFacePx: Int = 0,
    val instantRate: Float = 0f,
    val window5Rate: Float? = null,
    val window30Rate: Float? = null,
    val headDownEvents: Int = 0,
    val lyingEvents: Int = 0,
    val fps: Float = 0f,
    val stateByTrackingId: Map<Int, FaceState> = emptyMap()
)

/**
 * 注意力状态机：把「每帧的人脸观测」变成「持续行为事件 + 窗口统计」。
 *
 * - 用 ML Kit 的 trackingId 跟住每个人；
 * - 连续低头 ≥ [headDownEventMs]（默认 5s）记一次低头事件；
 * - 连续「俯仰角 ≤ [lyingPitch] 且闭眼」≥ [lyingEventMs]（默认 10s）记一次趴桌事件；
 *   趴桌优先，同一段里不再重复记低头；
 * - 跟踪丢失超过 [trackTimeoutMs] 视为离场，进行中的事件用最后见到的时间收尾；
 * - 另外维护 5s / 30s 滑动窗口的抬头率（按人数加权，比逐帧比例稳得多）。
 *
 * 只在分析线程上调用（单线程），内部不加锁。
 */
class AttentionTracker(
    private val headDownThresholdProvider: () -> Float,
    private val lyingPitch: Float = DEFAULT_LYING_PITCH,
    private val eyesClosedProb: Float = DEFAULT_EYES_CLOSED,
    private val headDownEventMs: Long = DEFAULT_HEAD_DOWN_EVENT_MS,
    private val lyingEventMs: Long = DEFAULT_LYING_EVENT_MS,
    private val trackTimeoutMs: Long = DEFAULT_TRACK_TIMEOUT_MS
) {
    private class Track(val id: Int) {
        var lastSeenMs = 0L
        var headDownSinceMs: Long? = null
        var lyingSinceMs: Long? = null
        var headDownEvent: AttentionEvent? = null
        var lyingEvent: AttentionEvent? = null
        var minPitch = 0f
        var minEyes: Float? = null
    }

    private data class Sample(val t: Long, val total: Int, val headUp: Int)

    private val tracks = HashMap<Int, Track>()
    private val finished = ArrayList<AttentionEvent>()
    private val samples = ArrayDeque<Sample>()
    private val frameTimes = ArrayDeque<Long>()
    private var lastSampleMs = 0L
    private var fps = 0f
    private var headDownEventCount = 0
    private var lyingEventCount = 0

    /** 单张脸的状态判定（UI 画框、事件累计都用同一套口径） */
    fun classify(pitch: Float, eyesOpen: Float?): FaceState {
        val eyesClosed = eyesOpen != null && eyesOpen < eyesClosedProb
        return when {
            pitch <= lyingPitch && eyesClosed -> FaceState.LYING
            pitch < headDownThresholdProvider() -> FaceState.HEAD_DOWN
            else -> FaceState.HEAD_UP
        }
    }

    fun onFrame(observations: List<FaceObservation>, nowMs: Long): TrackerSnapshot {
        updateFps(nowMs)

        val states = HashMap<Int, FaceState>(observations.size)
        var headUp = 0
        var headDown = 0
        var lying = 0
        var eyesClosed = 0
        var minFacePx = Int.MAX_VALUE

        observations.forEach { obs ->
            val state = classify(obs.pitchDegrees, obs.eyesOpenProbability)
            when (state) {
                FaceState.HEAD_UP -> headUp++
                FaceState.HEAD_DOWN -> headDown++
                FaceState.LYING -> lying++
            }
            if (obs.eyesOpenProbability != null && obs.eyesOpenProbability < eyesClosedProb) eyesClosed++
            if (obs.analysisWidthPx in 1 until minFacePx) minFacePx = obs.analysisWidthPx

            val id = obs.trackingId
            if (id != null) {
                states[id] = state
                val track = tracks.getOrPut(id) { Track(id) }
                track.lastSeenMs = nowMs
                updateTrack(track, state, obs.pitchDegrees, obs.eyesOpenProbability, nowMs)
            }
        }

        // 跟丢的目标：用最后见到的时间把进行中的事件收尾
        if (tracks.isNotEmpty()) {
            val stale = tracks.values.filter { nowMs - it.lastSeenMs > trackTimeoutMs }
            stale.forEach { track ->
                finishHeadDown(track, track.lastSeenMs)
                finishLying(track, track.lastSeenMs)
                tracks.remove(track.id)
            }
        }

        // 只把「画面里确实有人脸」的帧计入窗口：空场景当成未知，不能算成 0% 或 100%
        if (observations.isNotEmpty() && nowMs - lastSampleMs >= SAMPLE_INTERVAL_MS) {
            lastSampleMs = nowMs
            samples.addLast(Sample(nowMs, observations.size, headUp))
            while (samples.isNotEmpty() && nowMs - samples.first().t > WINDOW_30S) samples.removeFirst()
        }

        return TrackerSnapshot(
            total = observations.size,
            headUp = headUp,
            headDown = headDown,
            lying = lying,
            eyesClosed = eyesClosed,
            minFacePx = if (minFacePx == Int.MAX_VALUE) 0 else minFacePx,
            instantRate = if (observations.isEmpty()) 0f else headUp * 100f / observations.size,
            window5Rate = windowRate(nowMs, WINDOW_5S),
            window30Rate = windowRate(nowMs, WINDOW_30S),
            headDownEvents = headDownEventCount,
            lyingEvents = lyingEventCount,
            fps = fps,
            stateByTrackingId = states
        )
    }

    /** 取走已经结束的事件（给 CSV 用），取完即清空 */
    fun drainFinishedEvents(): List<AttentionEvent> {
        if (finished.isEmpty()) return emptyList()
        val out = ArrayList(finished)
        finished.clear()
        return out
    }

    /**
     * 收尾：把所有「进行中」的事件按当前时间结束掉再取走。
     * 课程结束 / 用户退出 App 时调用，避免正在低头的人的事件丢失。
     * 注意只在真正退出时调用——转屏会走 onDestroy，不能在那里调用，否则一次持续低头会被切成两段。
     */
    fun finishAll(nowMs: Long): List<AttentionEvent> {
        tracks.values.forEach { track ->
            finishHeadDown(track, nowMs)
            finishLying(track, nowMs)
        }
        return drainFinishedEvents()
    }

    // ------------------------------------------------------------------ 内部

    private fun updateFps(nowMs: Long) {
        frameTimes.addLast(nowMs)
        while (frameTimes.isNotEmpty() && nowMs - frameTimes.first() > 1_000L) frameTimes.removeFirst()
        fps = frameTimes.size.toFloat()
    }

    private fun windowRate(nowMs: Long, windowMs: Long): Float? {
        var total = 0
        var up = 0
        for (i in samples.indices.reversed()) {
            val s = samples[i]
            if (nowMs - s.t > windowMs) break
            total += s.total
            up += s.headUp
        }
        return if (total <= 0) null else up * 100f / total
    }

    private fun updateTrack(track: Track, state: FaceState, pitch: Float, eyesOpen: Float?, nowMs: Long) {
        if (state == FaceState.LYING) {
            // 趴桌优先：同一段里把已经记下的低头事件撤掉，避免一次行为算两项
            if (track.headDownEvent != null) {
                track.headDownEvent = null
                headDownEventCount--
            }
            track.headDownSinceMs = null

            if (track.lyingSinceMs == null) {
                track.lyingSinceMs = nowMs
                track.minPitch = pitch
                track.minEyes = eyesOpen
            } else {
                track.minPitch = minOf(track.minPitch, pitch)
                if (eyesOpen != null) track.minEyes = minOf(track.minEyes ?: eyesOpen, eyesOpen)
            }
            val since = track.lyingSinceMs ?: nowMs
            if (track.lyingEvent == null && nowMs - since >= lyingEventMs) {
                track.lyingEvent = AttentionEvent(
                    type = AttentionEventType.LYING,
                    trackingId = track.id,
                    startMs = since,
                    endMs = nowMs,
                    minPitch = track.minPitch,
                    minEyesOpen = track.minEyes
                )
                lyingEventCount++
            }
            return
        }

        finishLying(track, nowMs)

        if (state == FaceState.HEAD_DOWN) {
            if (track.headDownSinceMs == null) {
                track.headDownSinceMs = nowMs
                track.minPitch = pitch
                track.minEyes = eyesOpen
            } else {
                track.minPitch = minOf(track.minPitch, pitch)
                if (eyesOpen != null) track.minEyes = minOf(track.minEyes ?: eyesOpen, eyesOpen)
            }
            val since = track.headDownSinceMs ?: nowMs
            if (track.headDownEvent == null && nowMs - since >= headDownEventMs) {
                track.headDownEvent = AttentionEvent(
                    type = AttentionEventType.HEAD_DOWN,
                    trackingId = track.id,
                    startMs = since,
                    endMs = nowMs,
                    minPitch = track.minPitch,
                    minEyesOpen = track.minEyes
                )
                headDownEventCount++
            }
        } else {
            finishHeadDown(track, nowMs)
        }
    }

    private fun finishHeadDown(track: Track, endMs: Long) {
        track.headDownEvent?.let { event ->
            event.endMs = maxOf(endMs, event.startMs)
            finished += event
        }
        track.headDownEvent = null
        track.headDownSinceMs = null
        track.minPitch = 0f
        track.minEyes = null
    }

    private fun finishLying(track: Track, endMs: Long) {
        track.lyingEvent?.let { event ->
            event.endMs = maxOf(endMs, event.startMs)
            finished += event
        }
        track.lyingEvent = null
        track.lyingSinceMs = null
    }

    companion object {
        const val DEFAULT_LYING_PITCH = -35f
        const val DEFAULT_EYES_CLOSED = 0.4f
        const val DEFAULT_HEAD_DOWN_EVENT_MS = 5_000L
        const val DEFAULT_LYING_EVENT_MS = 10_000L
        const val DEFAULT_TRACK_TIMEOUT_MS = 2_000L

        private const val SAMPLE_INTERVAL_MS = 500L
        private const val WINDOW_5S = 5_000L
        private const val WINDOW_30S = 30_000L
    }
}
