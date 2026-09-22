package com.example.taitoulv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为事件状态机的单元测试（纯 JVM，不需要手机）。
 * 时间由参数传入，所以判定完全确定性。
 */
class AttentionTrackerTest {

    private fun tracker(threshold: Float = -10f) = AttentionTracker(
        headDownThresholdProvider = { threshold },
        lyingPitch = -35f,
        eyesClosedProb = 0.4f,
        headDownEventMs = 5_000L,
        lyingEventMs = 10_000L,
        trackTimeoutMs = 2_000L
    )

    private fun face(
        id: Int,
        pitch: Float,
        eyesOpen: Float? = 0.9f,
        widthPx: Int = 400
    ) = FaceObservation(
        trackingId = id,
        left = 0f, top = 0f, right = widthPx.toFloat(), bottom = widthPx.toFloat(),
        pitchDegrees = pitch,
        eyesOpenProbability = eyesOpen,
        analysisWidthPx = widthPx
    )

    /** 按 500ms 一帧喂进去 */
    private fun feed(
        tracker: AttentionTracker,
        startMs: Long,
        durationMs: Long,
        observations: (Long) -> List<FaceObservation>
    ): Long {
        var t = startMs
        while (t <= startMs + durationMs) {
            tracker.onFrame(observations(t), t)
            t += 500L
        }
        return t
    }

    @Test
    fun `连续低头超过5秒记一次事件`() {
        val tracker = tracker()
        // 低头 6.5 秒：这一刻事件已经「成立」（计数 +1），但要等抬头才收尾落盘
        val t = feed(tracker, 0L, 6_500L) { listOf(face(1, -25f)) }

        val ongoing = tracker.onFrame(listOf(face(1, -25f)), t)
        assertEquals("连续低头达标后应计入事件数", 1, ongoing.headDownEvents)
        assertTrue("还在低头时事件尚未收尾", tracker.drainFinishedEvents().isEmpty())

        // 抬头 → 事件收尾
        feed(tracker, t + 500L, 500L) { listOf(face(1, 5f)) }
        val events = tracker.drainFinishedEvents()
        assertEquals(1, events.size)
        assertEquals(AttentionEventType.HEAD_DOWN, events[0].type)
        assertEquals(1, events[0].trackingId)
        assertTrue("事件时长应 ≥5s，实际 ${events[0].durationMs}", events[0].durationMs >= 5_000L)
        assertTrue("应记录到的最低俯仰角", events[0].minPitch <= -25f)
    }

    @Test
    fun `退出时收尾进行中的事件`() {
        val tracker = tracker()
        feed(tracker, 0L, 7_000L) { listOf(face(1, -25f)) }
        // 模拟用户直接退出：还在低头也要把事件落盘
        val events = tracker.finishAll(7_500L)
        assertEquals(1, events.size)
        assertTrue(events[0].durationMs >= 5_000L)
    }

    @Test
    fun `低头不到5秒不算事件`() {
        val tracker = tracker()
        feed(tracker, 0L, 3_000L) { listOf(face(1, -25f)) }
        feed(tracker, 4_000L, 1_000L) { listOf(face(1, 5f)) }
        assertEquals(0, tracker.drainFinishedEvents().size)
    }

    @Test
    fun `趴桌优先且不重复记低头`() {
        val tracker = tracker()
        // 低头到 6 秒（已满足低头事件），然后转为趴桌 12 秒
        var t = feed(tracker, 0L, 6_000L) { listOf(face(1, -28f, eyesOpen = 0.9f)) }
        t = feed(tracker, t, 12_000L) { listOf(face(1, -45f, eyesOpen = 0.2f)) }

        val snapshot = tracker.onFrame(listOf(face(1, -45f, eyesOpen = 0.2f)), t)
        assertEquals(1, snapshot.lyingEvents)
        assertEquals("趴桌优先，同一段不该再记低头", 0, snapshot.headDownEvents)

        // 抬头收尾
        feed(tracker, t + 500L, 1_000L) { listOf(face(1, 5f, eyesOpen = 0.9f)) }
        val events = tracker.drainFinishedEvents()
        assertEquals(1, events.size)
        assertEquals(AttentionEventType.LYING, events[0].type)
        assertTrue(events[0].durationMs >= 10_000L)
    }

    @Test
    fun `目标跟丢时事件按最后见到的时间收尾`() {
        val tracker = tracker()
        // 低头 8 秒，然后这个人从画面消失
        val t = feed(tracker, 0L, 8_000L) { listOf(face(1, -30f)) }
        feed(tracker, t, 3_000L) { emptyList() }   // 超过 2s 跟踪超时

        val events = tracker.drainFinishedEvents()
        assertEquals(1, events.size)
        assertTrue("结束时间不应超过最后见到的时间太多", events[0].endMs <= t + 1_000L)
    }

    @Test
    fun `窗口抬头率按人数加权`() {
        val tracker = tracker()
        // 两个脸，一个抬头一个低头，持续 12 秒
        feed(tracker, 0L, 12_000L) { listOf(face(1, 5f), face(2, -30f)) }

        val snapshot = tracker.onFrame(listOf(face(1, 5f), face(2, -30f)), 12_500L)
        assertEquals(2, snapshot.total)
        assertEquals(1, snapshot.headUp)
        assertEquals(1, snapshot.headDown)
        assertEquals(50f, snapshot.instantRate, 0.01f)
        assertEquals(50f, snapshot.window5Rate!!, 0.01f)
        assertEquals(50f, snapshot.window30Rate!!, 0.01f)
    }

    @Test
    fun `画面没人时不产生窗口值`() {
        val tracker = tracker()
        feed(tracker, 0L, 5_000L) { emptyList() }
        val snapshot = tracker.onFrame(emptyList(), 5_500L)
        assertEquals(0, snapshot.total)
        assertNull("空场景不能算成 0% 或 100%", snapshot.window30Rate)
    }

    @Test
    fun `闭眼与最小人脸像素统计`() {
        val tracker = tracker()
        val snapshot = tracker.onFrame(
            listOf(face(1, 5f, eyesOpen = 0.2f, widthPx = 60), face(2, 5f, eyesOpen = 0.9f, widthPx = 300)),
            1_000L
        )
        assertEquals(1, snapshot.eyesClosed)
        assertEquals(60, snapshot.minFacePx)
    }
}
