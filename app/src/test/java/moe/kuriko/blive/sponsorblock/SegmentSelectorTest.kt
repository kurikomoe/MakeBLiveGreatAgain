package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多分 P 分段筛选（[SegmentSelector]）的测试。
 *
 * 真机上出错的形态是「多 P 视频用别的分 P 的时间轴跳过」，所以这里把三种取舍都钉死：
 * cid 精确匹配 → 按时长匹配 → 信息不足时原样返回。
 */
class SegmentSelectorTest {

    private fun segment(
        cid: String,
        startSec: Double,
        endSec: Double,
        videoDurationSec: Double = 0.0,
        uuid: String = "$cid-$startSec",
    ) = SponsorSegment(
        uuid = uuid,
        category = "sponsor",
        actionType = ActionType.SKIP,
        cid = cid,
        startMs = (startSec * 1000).toLong(),
        endMs = (endSec * 1000).toLong(),
        videoDurationMs = (videoDurationSec * 1000).toLong(),
        locked = false,
    )

    private val p1 = segment("1001", 10.0, 20.0, 120.0)
    private val p2 = segment("1002", 200.0, 210.0, 300.0)

    @Test
    fun `有 cid 时只保留该分 P 的片段`() {
        val result = SegmentSelector.forCurrentPart(listOf(p1, p2), "1001", 125_000L)
        assertEquals(listOf(p1), result)
    }

    @Test
    fun `cid 命中时忽略时长不匹配`() {
        // 播放器时长与提交时长差很多（例如换清晰度/补帧），cid 命中仍然作数
        val result = SegmentSelector.forCurrentPart(listOf(p1, p2), "1001", 999_000L)
        assertEquals(listOf(p1), result)
    }

    @Test
    fun `cid 未知时按 videoDuration 挑一组`() {
        val result = SegmentSelector.forCurrentPart(listOf(p1, p2), null, 302_000L)
        assertEquals(listOf(p2), result)
    }

    @Test
    fun `按时长匹配有 5 秒容差`() {
        assertEquals(listOf(p1), SegmentSelector.forCurrentPart(listOf(p1, p2), null, 124_000L))
        assertEquals(listOf(p1), SegmentSelector.forCurrentPart(listOf(p1, p2), null, 116_000L))
        // 超出容差就挑不出来，退回全量
        assertEquals(2, SegmentSelector.forCurrentPart(listOf(p1, p2), null, 100_000L).size)
    }

    @Test
    fun `cid 对不上且时长也对不上时原样返回 宁可多跳不漏跳`() {
        val result = SegmentSelector.forCurrentPart(listOf(p1, p2), "9999", 500_000L)
        assertEquals(2, result.size)
    }

    @Test
    fun `cid 为空的旧数据在任何情况下都保留`() {
        val legacy = segment("", 30.0, 40.0, 120.0)
        val result = SegmentSelector.forCurrentPart(listOf(legacy, p2), "1001", 125_000L)
        assertTrue(result.contains(legacy))
        assertEquals(1, result.size)
    }

    @Test
    fun `单 P 视频不受影响`() {
        val single = segment("1001", 10.0, 20.0, 120.0)
        assertEquals(listOf(single), SegmentSelector.forCurrentPart(listOf(single), "1001", 120_000L))
        assertEquals(listOf(single), SegmentSelector.forCurrentPart(listOf(single), null, 120_000L))
    }

    @Test
    fun `空列表与未知时长都不崩`() {
        assertTrue(SegmentSelector.forCurrentPart(emptyList(), "1001", 0L).isEmpty())
        assertEquals(1, SegmentSelector.forCurrentPart(listOf(p1), "1001", 0L).size)
    }

    @Test
    fun `能列出多分 P 的 cid 用于日志`() {
        assertEquals(listOf("1001", "1002"), SegmentSelector.distinctCids(listOf(p1, p2, p1)))
    }
}
