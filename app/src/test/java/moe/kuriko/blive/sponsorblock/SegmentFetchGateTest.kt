package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SegmentFetchGate] 的测试。
 *
 * 这里每一个「该重查」的用例都对应真机上出现过或差点出现的坑：
 * 播放停止后回到同一条视频、失败重试、切清晰度/预加载造成的重复解析。
 */
class SegmentFetchGateTest {

    private val gate = SegmentFetchGate(startThresholdMs = 800L, maxAttempts = 3)

    @Test
    fun `位置没到阈值不查`() {
        assertFalse(gate.tryBeginFetch("BV1a", 0L, coolingDown = false))
        assertFalse(gate.tryBeginFetch("BV1a", 799L, coolingDown = false))
        assertNull(gate.active)
        assertEquals(0, gate.attemptCount)
    }

    @Test
    fun `走过阈值后放行一次 之后同一条稿件不再放行`() {
        assertTrue(gate.tryBeginFetch("BV1a", 800L, coolingDown = false))
        assertEquals("BV1a", gate.active)
        assertEquals(1, gate.attemptCount)
        // 宿主反复解析（切清晰度、预加载）都不会再触发
        repeat(10) { assertFalse(gate.tryBeginFetch("BV1a", 5_000L, coolingDown = false)) }
        assertEquals(1, gate.attemptCount)
    }

    @Test
    fun `换视频后可以再次放行`() {
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onVideoChanged()
        assertTrue(gate.tryBeginFetch("BV1b", 1_000L, coolingDown = false))
        assertEquals("BV1b", gate.active)
        assertEquals(1, gate.attemptCount)
    }

    /** 回归用例：真机上「看 A → 离开（停轮询清空片段）→ 再回来看 A」曾经永远不再查询。 */
    @Test
    fun `播放停止后回到同一条视频必须能重新放行`() {
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        assertFalse(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onPlaybackStopped()
        assertNull(gate.active)
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
    }

    @Test
    fun `拉取失败后允许重试 但有次数上限`() {
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onFetchFailed()
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onFetchFailed()
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onFetchFailed()
        // 第 4 次不再放行
        assertFalse(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        assertEquals(3, gate.attemptCount)
    }

    @Test
    fun `冷却中不消耗尝试次数 否则重试会被上限卡死`() {
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        gate.onFetchFailed()
        // 冷却期内 tick 每 500ms 问一次，问 100 次都不算尝试
        repeat(100) { assertFalse(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = true)) }
        assertEquals(1, gate.attemptCount)
        // 冷却过去后仍能重试（还剩 2 次额度）
        assertTrue(gate.tryBeginFetch("BV1a", 1_000L, coolingDown = false))
        assertEquals(2, gate.attemptCount)
    }

    @Test
    fun `bvid 为空时绝不放行`() {
        assertFalse(gate.tryBeginFetch(null, 5_000L, coolingDown = false))
        assertFalse(gate.tryBeginFetch("", 5_000L, coolingDown = false))
        assertNull(gate.active)
    }
}
