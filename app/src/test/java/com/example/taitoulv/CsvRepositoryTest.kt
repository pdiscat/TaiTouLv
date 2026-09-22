package com.example.taitoulv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** CSV 解析与汇总的单元测试（纯 JVM，不需要手机） */
class CsvRepositoryTest {

    private fun tempCsv(name: String, content: String): File =
        File.createTempFile(name, ".csv").apply {
            writeText(content, Charsets.UTF_8)
            deleteOnExit()
        }

    @Test
    fun `解析明细表并跳过带 BOM 的表头`() {
        val file = tempCsv(
            "detail",
            "\uFEFF时间,相对秒,可见人数,抬头,低头,趴桌,闭眼,即时抬头率,5秒抬头率,30秒抬头率,最小人脸像素,低头阈值,帧率\n" +
                "16:05:41,10.8,1,1,0,0,0,100.0,100.0,85.0,1160,-10,12.0\n" +
                "16:05:47,15.8,3,2,1,0,0,66.7,80.0,82.5,433,-10,12.5\n"
        )
        val samples = CsvRepository.readDetail(file)
        assertEquals(2, samples.size)
        assertEquals(10.8f, samples[0].seconds, 0.01f)
        assertEquals(1, samples[0].visible)
        assertEquals(85.0f, samples[0].rate30!!, 0.01f)
        assertEquals(1160, samples[0].minFacePx)
        assertEquals(3, samples[1].visible)
        assertEquals(1, samples[1].headDown)
    }

    @Test
    fun `空窗口值解析成 null 而不是 0`() {
        val file = tempCsv(
            "detail2",
            "时间,相对秒,可见人数,抬头,低头,趴桌,闭眼,即时抬头率,5秒抬头率,30秒抬头率,最小人脸像素,低头阈值,帧率\n" +
                "16:05:31,0.7,0,0,0,0,0,0.0,,0.0,0,-10,1.0\n"
        )
        val sample = CsvRepository.readDetail(file).single()
        assertNull(sample.rate5)
        assertEquals(0.0f, sample.rate30!!, 0.001f)
    }

    @Test
    fun `解析事件表`() {
        val file = tempCsv(
            "events",
            "\uFEFF类型,跟踪ID,开始秒,结束秒,时长秒,最低俯仰角,最低睁眼概率\n" +
                "低头,7,12.5,19.0,6.5,-28.3,0.75\n" +
                "趴桌,9,40.0,55.5,15.5,-45.1,0.10\n"
        )
        val events = CsvRepository.readEvents(file)
        assertEquals(2, events.size)
        assertEquals("低头", events[0].type)
        assertEquals(7, events[0].trackingId)
        assertEquals(6.5f, events[0].durationSec, 0.01f)
        assertEquals(true, events[1].isLying)
    }

    @Test
    fun `坏行会被跳过`() {
        val file = tempCsv(
            "bad",
            "时间,相对秒\n坏的,行\n16:00:00,1.0,1,1,0,0,0,100,100,100,900,-10,12\n"
        )
        assertEquals(1, CsvRepository.readDetail(file).size)
    }

    @Test
    fun `事件文件按时间戳对应`() {
        val detail = File("/tmp/抬头率明细_20260922_160116.csv")
        val event = CsvRepository.eventFileFor(detail)
        assertNotNull(event)
        assertEquals("低头事件_20260922_160116.csv", event!!.name)
        assertNull(CsvRepository.eventFileFor(File("/tmp/随便.csv")))
    }

    @Test
    fun `汇总统计`() {
        val samples = listOf(
            CsvSample(5f, 10, 8, 2, 0, 1, 80f, 80f, 80f, 300, 12f),
            CsvSample(10f, 10, 10, 0, 0, 0, 100f, 90f, 90f, 320, 12f)
        )
        val events = listOf(
            CsvEventRow("低头", 1, 6f, 12f, 6f, -25f),
            CsvEventRow("趴桌", 2, 20f, 35f, 15f, -40f)
        )
        val summary = CsvRepository.summaryOf(samples, events)!!
        assertEquals(10f, summary.durationSec, 0.01f)
        // 汇总用的是 30s 窗口序列（也就是图表里那条绿线）
        assertEquals(85f, summary.avgRate, 0.01f)
        assertEquals(80f, summary.minRate, 0.01f)
        assertEquals(90f, summary.maxRate, 0.01f)
        assertEquals(1, summary.headDownEvents)
        assertEquals(1, summary.lyingEvents)
    }
}
