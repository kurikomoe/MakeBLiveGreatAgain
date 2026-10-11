package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FetchThrottle] 的测试。
 *
 * 真机背景：宿主在播放链路上会反复解析视频，不节流的话浏览一会儿信息流就能对
 * bsbsb.top（社区自费维护）打出一串请求。这里把三道闸的行为钉死：
 * 同稿件冷却、全局最小间隔、以及「冷却查询不计数」——后者是重试逻辑的前提：
 * 控制器每 500 ms 问一次「能不能查」，如果这个查询也计数，统计会被瞬间撑爆。
 */
class FetchThrottleTest {

    private var now = 1_000_000L
    private fun throttle(
        minGlobal: Long = 3_000L,
        perVideo: Long = 60_000L,
    ) = FetchThrottle(minGlobal, perVideo) { now }

    @Test
    fun `同一条稿件在冷却期内不再联网`() {
        val t = throttle()
        assertTrue(t.canRequest("BV1a"))
        t.recordRequest("BV1a")

        assertFalse(t.canRequest("BV1a")) // 挡下 #1
        now += 59_000L
        assertFalse(t.canRequest("BV1a")) // 挡下 #2
        now += 2_000L
        assertTrue(t.canRequest("BV1a"))
        assertEquals(2L, t.suppressedCount)
    }

    @Test
    fun `冷却查询本身不计数`() {
        val t = throttle()
        t.recordRequest("BV1a")
        repeat(120) { assertTrue(t.isCoolingDown("BV1a")) } // 模拟 2Hz tick 问一分钟
        assertEquals(0L, t.suppressedCount)
        // 冷却过去后放行，且重试路径上的查询同样不计数
        now += 61_000L
        assertFalse(t.isCoolingDown("BV1a"))
        assertEquals(0L, t.suppressedCount)
        assertTrue(t.canRequest("BV1a"))
        assertEquals(0L, t.suppressedCount)
    }

    @Test
    fun `不同稿件互不影响`() {
        val t = throttle()
        t.recordRequest("BV1a")
        assertFalse(t.canRequest("BV1a"))
        assertFalse(t.isCoolingDown("BV1b"))
        assertTrue(t.canRequest("BV1b"))
    }

    @Test
    fun `全局最小间隔把突发请求摊开`() {
        val t = throttle(minGlobal = 3_000L)
        assertEquals(0L, t.delayBeforeRequestMs())
        t.recordRequest("BV1a")
        assertEquals(3_000L, t.delayBeforeRequestMs())
        now += 1_000L
        assertEquals(2_000L, t.delayBeforeRequestMs())
        now += 5_000L
        assertEquals(0L, t.delayBeforeRequestMs())
    }

    @Test
    fun `网络请求计数只统计真正发出的那些`() {
        val t = throttle()
        t.recordRequest("BV1a")
        t.canRequest("BV1a") // 被挡下，不计入网络请求
        t.recordRequest("BV1b")
        assertEquals(2L, t.networkRequestCount)
        assertEquals(1L, t.suppressedCount)
    }

    @Test
    fun `reset 后重新放行`() {
        val t = throttle()
        t.recordRequest("BV1a")
        assertFalse(t.canRequest("BV1a"))
        t.reset()
        assertTrue(t.canRequest("BV1a"))
        assertEquals(0L, t.delayBeforeRequestMs())
    }

    @Test
    fun `失败重试的节奏：冷却期内一直挡住 冷却过后放行`() {
        val t = throttle(perVideo = 60_000L)
        // 第一次请求失败（失败也会被 recordRequest 记下时间）
        t.recordRequest("BV1a")
        // 控制器每个 tick 都会问一次，前 60 秒全部返回「冷却中」，且不消耗尝试次数
        repeat(60) {
            assertTrue(t.isCoolingDown("BV1a"))
            now += 1_000L
        }
        assertFalse(t.isCoolingDown("BV1a"))
        assertEquals(0L, t.suppressedCount)
        assertEquals(1L, t.networkRequestCount)
    }
}
