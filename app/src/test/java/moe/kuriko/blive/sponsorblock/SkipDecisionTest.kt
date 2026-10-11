package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 跳过判定（[SkipDecision]）的边界条件测试。
 *
 * 这一段逻辑决定了「什么时候跳、跳到哪里、什么时候不该动」，是模块的核心；
 * 抽成纯函数之后就能在 JVM 上把边界条件全部钉死，不用等真机。
 */
class SkipDecisionTest {

    private fun segment(
        startSec: Double,
        endSec: Double,
        category: String = "sponsor",
        actionType: String = ActionType.SKIP,
        uuid: String = "u-$startSec-$endSec",
    ) = SponsorSegment(
        uuid = uuid,
        category = category,
        actionType = actionType,
        cid = "100",
        startMs = (startSec * 1000).toLong(),
        endMs = (endSec * 1000).toLong(),
        videoDurationMs = 60_000L,
        locked = false,
    )

    private val sponsor = segment(10.0, 20.0, category = "sponsor")
    private val intro = segment(30.0, 35.0, category = "intro")

    @Test
    fun `区间内命中`() {
        val hit = SkipDecision.findSkipSegment(15_000L, 60_000L, listOf(sponsor))
        assertNotNull(hit)
        assertEquals(20_000L, hit!!.endMs)
    }

    @Test
    fun `区间语义是半开的 位置等于 end 时不再命中`() {
        assertNotNull(SkipDecision.findSkipSegment(19_999L, 60_000L, listOf(sponsor)))
        assertNull(SkipDecision.findSkipSegment(20_000L, 60_000L, listOf(sponsor)))
    }

    @Test
    fun `提前量让起点前 300ms 就命中 漏跳会被兜住`() {
        assertNull(SkipDecision.findSkipSegment(9_699L, 60_000L, listOf(sponsor)))
        assertNotNull(SkipDecision.findSkipSegment(9_700L, 60_000L, listOf(sponsor)))
    }

    @Test
    fun `时长未知时不做任何判定`() {
        assertNull(SkipDecision.findSkipSegment(15_000L, 0L, listOf(sponsor)))
        assertNull(SkipDecision.findSkipSegment(15_000L, -1L, listOf(sponsor)))
    }

    @Test
    fun `位置越界会先夹取 避免误命中`() {
        // 位置报成负数 -> 夹到 0 -> 不在 [9.7s, 20s) 内
        assertNull(SkipDecision.findSkipSegment(-5_000L, 60_000L, listOf(sponsor)))
        // 位置超过时长 -> 夹到 duration
        assertNull(SkipDecision.findSkipSegment(999_000L, 60_000L, listOf(sponsor)))
    }

    @Test
    fun `最小时长过滤生效`() {
        val short = segment(10.0, 12.0) // 2 秒
        assertNotNull(SkipDecision.findSkipSegment(11_000L, 60_000L, listOf(short), minDurationMs = 0L))
        assertNull(SkipDecision.findSkipSegment(11_000L, 60_000L, listOf(short), minDurationMs = 3_000L))
    }

    @Test
    fun `关闭的分类不参与判定`() {
        val enabled = { category: String -> category != "intro" }
        assertNotNull(
            SkipDecision.findSkipSegment(15_000L, 60_000L, listOf(sponsor, intro), 0L, enabled),
        )
        assertNull(
            SkipDecision.findSkipSegment(32_000L, 60_000L, listOf(sponsor, intro), 0L, enabled),
        )
    }

    @Test
    fun `零长与逆序片段被丢弃`() {
        val zero = segment(10.0, 10.0)
        val reversed = segment(20.0, 15.0)
        assertNull(SkipDecision.findSkipSegment(10_000L, 60_000L, listOf(zero)))
        assertNull(SkipDecision.findSkipSegment(17_000L, 60_000L, listOf(reversed)))
        assertEquals(false, SkipDecision.isUsableInterval(zero))
        assertEquals(false, SkipDecision.isUsableInterval(reversed))
        assertEquals(true, SkipDecision.isUsableInterval(sponsor))
    }

    @Test
    fun `full 整片标记永远不参与跳转`() {
        val full = SponsorSegment("f", "sponsor", ActionType.FULL, "100", 0L, 0L, 60_000L, false)
        assertNull(SkipDecision.findSkipSegment(5_000L, 60_000L, listOf(full)))
    }

    @Test
    fun `mute 片段只在 mute 判定里命中`() {
        val mute = segment(10.0, 20.0, category = "music_offtopic", actionType = ActionType.MUTE)
        assertNull(SkipDecision.findSkipSegment(15_000L, 60_000L, listOf(mute)))
        assertNotNull(SkipDecision.findMuteSegment(15_000L, 60_000L, listOf(mute)))
        assertNull(SkipDecision.findMuteSegment(25_000L, 60_000L, listOf(mute)))
    }

    @Test
    fun `poi 单点在窗口内命中 且不是区间`() {
        val poi = SponsorSegment("p", "poi_highlight", ActionType.POI, "100", 30_000L, 30_000L, 60_000L, false)
        assertNotNull(SkipDecision.findPoiPoint(30_500L, 60_000L, listOf(poi)))
        assertNotNull(SkipDecision.findPoiPoint(29_000L, 60_000L, listOf(poi)))
        assertNull(SkipDecision.findPoiPoint(28_000L, 60_000L, listOf(poi)))
        // poi 不会被当成区间跳过
        assertNull(SkipDecision.findSkipSegment(30_000L, 60_000L, listOf(poi)))
    }

    @Test
    fun `多个片段时取第一个命中`() {
        val first = segment(10.0, 20.0, uuid = "first")
        val second = segment(15.0, 25.0, uuid = "second")
        val hit = SkipDecision.findSkipSegment(16_000L, 60_000L, listOf(first, second))
        assertEquals("first", hit?.uuid)
    }

    @Test
    fun `位置夹取`() {
        assertEquals(0L, SkipDecision.clampPosition(-1L, 60_000L))
        assertEquals(60_000L, SkipDecision.clampPosition(99_999L, 60_000L))
        assertEquals(5_000L, SkipDecision.clampPosition(5_000L, 60_000L))
        assertEquals(5_000L, SkipDecision.clampPosition(5_000L, 0L))
    }
}
