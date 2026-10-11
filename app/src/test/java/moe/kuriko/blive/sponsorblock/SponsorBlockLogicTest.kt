package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单测：BV/AV 互转、哈希前缀、接口响应解析、配置解析。
 *
 * 这些用例不依赖 Android 运行时，`./gradlew testDebugUnitTest` 即可跑。
 * 期望值来源：
 *  - `av2 → BV1xx411c7mD`、`av170001 → BV17x411w7KC` 是 B 站上广为人知的真实对应关系；
 *  - `sha256("BV1Buz5YrELe")[:4] == "d5c2"` 与线上 `GET /api/skipSegments/d5c2` 的实际分桶一致。
 */
class SponsorBlockLogicTest {

    @Test
    fun `aid 转 bvid 与真实对应关系一致`() {
        assertEquals("BV1xx411c7mD", BvAv.aidToBvid(2L))
        assertEquals("BV17x411w7KC", BvAv.aidToBvid(170001L))
        assertEquals("BV1xx411c7mQ", BvAv.aidToBvid(1L))
    }

    @Test
    fun `非法 aid 返回空串`() {
        assertEquals("", BvAv.aidToBvid(0L))
        assertEquals("", BvAv.aidToBvid(-1L))
        // 超出 51 位可表示范围
        assertEquals("", BvAv.aidToBvid(1L shl 51))
    }

    @Test
    fun `bvid 转 aid 与 aid 转 bvid 互为逆运算`() {
        listOf(2L, 170001L, 113549775085328L).forEach { aid ->
            val bvid = BvAv.aidToBvid(aid)
            assertEquals(aid, BvAv.bvidToAid(bvid))
        }
    }

    @Test
    fun `哈希前缀取 sha256 前四位`() {
        // 线上 d5c2 分桶里确实有 BV1Buz5YrELe
        assertEquals("d5c2", SponsorBlockApi.hashPrefix("BV1Buz5YrELe"))
        assertEquals("4df8", SponsorBlockApi.hashPrefix("BV1xx411c7mD"))
        assertEquals(64, SponsorBlockApi.sha256Hex("BV1Buz5YrELe").length)
    }

    @Test
    fun `解析按前缀分桶的响应并挑出本条稿件`() {
        val body = """
            [
              {"videoID":"BV1other00000","segments":[
                {"UUID":"aaa","category":"sponsor","actionType":"skip","segment":[1.0,2.0],"cid":"1","videoDuration":100}
              ]},
              {"videoID":"BV1Buz5YrELe","segments":[
                {"UUID":"5d8ca75b","category":"selfpromo","actionType":"skip","segment":[20.766,33.131],
                 "cid":"27045661481","videoDuration":950,"locked":0,"votes":0,"description":""},
                {"UUID":"bbb","category":"poi_highlight","actionType":"poi","segment":[60.0,60.0],
                 "cid":"27045661481","videoDuration":950,"locked":0}
              ]}
            ]
        """.trimIndent()

        val result = SponsorBlockApi.parse("BV1Buz5YrELe", body)
        assertEquals("BV1Buz5YrELe", result.bvid)
        assertEquals(2, result.segments.size)

        val first = result.segments[0]
        assertEquals("selfpromo", first.category)
        assertEquals(ActionType.SKIP, first.actionType)
        assertEquals(20_766L, first.startMs)
        assertEquals(33_131L, first.endMs)
        assertEquals("27045661481", first.cid)
        assertTrue(first.isInterval)

        val poi = result.segments[1]
        assertTrue(poi.isPoint)
        assertEquals(60_000L, poi.startMs)
    }

    @Test
    fun `找不到对应稿件时返回空结果`() {
        val result = SponsorBlockApi.parse("BV1missing000", "[{\"videoID\":\"BV1other00000\",\"segments\":[]}]")
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `空响应与非法响应都不抛异常`() {
        assertTrue(SponsorBlockApi.parse("BV1Buz5YrELe", "[]").segments.isEmpty())
        assertTrue(SponsorBlockApi.parse("BV1Buz5YrELe", "not json").segments.isEmpty())
    }

    @Test
    fun `配置解析缺项时回落默认值`() {
        val snapshot = SponsorBlockPrefs.fromRawMap(mapOf("enabled" to false, "min_duration_sec" to 5))
        assertEquals(false, snapshot.enabled)
        assertEquals(5_000L, snapshot.minDurationMs)
        // 未配置的分类用默认跳过方式
        assertEquals(
            SkipOption.AUTO,
            snapshot.optionOf("sponsor"),
        )
        assertEquals(SponsorBlockApi.DEFAULT_SERVER, snapshot.server)
    }

    @Test
    fun `配置解析读取每个分类的跳过方式`() {
        val snapshot = SponsorBlockPrefs.fromRawMap(
            mapOf(
                SponsorBlockPrefs.optionKey("sponsor") to SkipOption.DISABLED.value,
                SponsorBlockPrefs.optionKey("intro") to SkipOption.AUTO.value,
            ),
        )
        assertEquals(SkipOption.DISABLED, snapshot.optionOf("sponsor"))
        assertEquals(SkipOption.AUTO, snapshot.optionOf("intro"))
        assertEquals(false, snapshot.autoSkips("sponsor"))
        assertEquals(true, snapshot.autoSkips("intro"))
        // 未知分类不崩，且不参与提示
        assertEquals(SkipOption.DISABLED, snapshot.optionOf("unknown_category"))
    }

    @Test
    fun `跳过方式取值与上游 CategorySkipOption 对齐`() {
        assertEquals(-1, SkipOption.DISABLED.value)
        assertEquals(0, SkipOption.NOTIFY.value)
        assertEquals(1, SkipOption.MANUAL.value)
        assertEquals(2, SkipOption.AUTO.value)
        assertEquals(SkipOption.AUTO, SkipOption.fromValue(2))
        assertEquals(SkipOption.DISABLED, SkipOption.fromValue(99))
    }

    @Test
    fun `分类表里 sponsor 与 padding 默认自动跳过`() {
        assertEquals(SkipOption.AUTO, SponsorCategories.find("sponsor")?.defaultOption)
        assertEquals(SkipOption.AUTO, SponsorCategories.find("padding")?.defaultOption)
        assertEquals(SkipOption.DISABLED, SponsorCategories.find("filler")?.defaultOption)
        assertNull(SponsorCategories.find("nope"))
        assertEquals("nope", SponsorCategories.labelOf("nope"))
    }
}
