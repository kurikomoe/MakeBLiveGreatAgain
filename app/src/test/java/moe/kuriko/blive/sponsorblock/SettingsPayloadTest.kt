package moe.kuriko.blive.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置跨进程传输的**端到端回归**：Provider 返回的 Bundle（键值对）→ 宿主解析出的配置。
 *
 * 下面 [DEFAULT_PAYLOAD] 不是编的，是从真机上抓下来的原始输出：
 *
 * ```
 * adb shell content call --uri content://moe.kuriko.blive.settings --method getSettings
 * Result: Bundle[{option_sponsor=2, option_exclusive_access=0, enabled=true, option_filler=-1, ...}]
 * ```
 *
 * 这条链路最容易出的问题是「静默返回默认值」——默认值与「读取失败」在行为上无法区分，
 * 所以这里把真实的线上载荷固定下来当回归基线。
 */
class SettingsPayloadTest {

    private val defaultPayload: Map<String, Any?> = mapOf(
        "option_sponsor" to 2,
        "option_exclusive_access" to 0,
        "enabled" to true,
        "option_filler" to -1,
        "progress_marker" to true,
        "server" to "https://www.bsbsb.top",
        "poi_auto_skip" to false,
        "option_padding" to 2,
        "option_selfpromo" to 1,
        "option_music_offtopic" to 2,
        "option_preview" to 0,
        "ok" to true,
        "min_duration_sec" to 0,
        "option_poi_highlight" to 1,
        "mute_segments" to true,
        "show_toast" to true,
        "option_interaction" to 1,
        "option_intro" to 1,
        "option_outro" to 1,
    )

    @Test
    fun `真机默认载荷解析结果正确`() {
        val snapshot = SponsorBlockPrefs.fromRawMap(defaultPayload)
        assertTrue(snapshot.enabled)
        assertEquals("https://www.bsbsb.top", snapshot.server)
        assertEquals(0L, snapshot.minDurationMs)
        assertTrue(snapshot.muteSegments)
        assertTrue(snapshot.showToast)
        assertTrue(snapshot.progressMarker)
        assertFalse(snapshot.poiAutoSkip)

        // 上游默认：sponsor/padding/music_offtopic 自动跳过，filler 禁用，其余手动/仅提示
        assertEquals(SkipOption.AUTO, snapshot.optionOf("sponsor"))
        assertEquals(SkipOption.AUTO, snapshot.optionOf("padding"))
        assertEquals(SkipOption.AUTO, snapshot.optionOf("music_offtopic"))
        assertEquals(SkipOption.DISABLED, snapshot.optionOf("filler"))
        assertEquals(SkipOption.MANUAL, snapshot.optionOf("selfpromo"))
        assertEquals(SkipOption.MANUAL, snapshot.optionOf("interaction"))
        assertEquals(SkipOption.MANUAL, snapshot.optionOf("intro"))
        assertEquals(SkipOption.MANUAL, snapshot.optionOf("outro"))
        assertEquals(SkipOption.NOTIFY, snapshot.optionOf("preview"))
        assertEquals(SkipOption.NOTIFY, snapshot.optionOf("exclusive_access"))
        assertEquals(SkipOption.MANUAL, snapshot.optionOf("poi_highlight"))
    }

    /** 对应「把赞助/恰饭改成禁用」那次手测：Provider 会返回 `option_sponsor = -1`。 */
    @Test
    fun `把 sponsor 改成禁用后宿主必须读到禁用`() {
        val payload = defaultPayload + ("option_sponsor" to SkipOption.DISABLED.value)
        val snapshot = SponsorBlockPrefs.fromRawMap(payload)
        assertEquals(SkipOption.DISABLED, snapshot.optionOf("sponsor"))
        assertFalse(snapshot.autoSkips("sponsor"))
        assertFalse(snapshot.notifies("sponsor"))
        // 同一份载荷里其它分类不受影响
        assertEquals(SkipOption.AUTO, snapshot.optionOf("padding"))
    }

    @Test
    fun `把 sponsor 改成仅提示后宿主读到仅提示`() {
        val payload = defaultPayload + ("option_sponsor" to SkipOption.NOTIFY.value)
        val snapshot = SponsorBlockPrefs.fromRawMap(payload)
        assertEquals(SkipOption.NOTIFY, snapshot.optionOf("sponsor"))
        assertFalse(snapshot.autoSkips("sponsor"))
        assertTrue(snapshot.notifies("sponsor"))
    }

    @Test
    fun `最小时长与服务器地址改动能读到`() {
        val payload = defaultPayload + mapOf(
            "min_duration_sec" to 30,
            "server" to "https://www.bsbsb.xyz",
        )
        val snapshot = SponsorBlockPrefs.fromRawMap(payload)
        assertEquals(30_000L, snapshot.minDurationMs)
        assertEquals("https://www.bsbsb.xyz", snapshot.server)
    }

    @Test
    fun `开关类改动能读到`() {
        val payload = defaultPayload + mapOf(
            "enabled" to false,
            "mute_segments" to false,
            "show_toast" to false,
            "progress_marker" to false,
            "poi_auto_skip" to true,
        )
        val snapshot = SponsorBlockPrefs.fromRawMap(payload)
        assertFalse(snapshot.enabled)
        assertFalse(snapshot.muteSegments)
        assertFalse(snapshot.showToast)
        assertFalse(snapshot.progressMarker)
        assertTrue(snapshot.poiAutoSkip)
    }

    /** Provider 在 prefs 为空时只回了 `ok`，此时必须整体回落到默认值而不是崩。 */
    @Test
    fun `只有 ok 键时回落默认值`() {
        val snapshot = SponsorBlockPrefs.fromRawMap(mapOf("ok" to true))
        assertEquals(SponsorBlockPrefs.DEFAULT, snapshot)
    }

    @Test
    fun `未知额外键被忽略`() {
        val payload = defaultPayload + mapOf("some_future_key" to "x", "ok" to true)
        assertEquals(SponsorBlockPrefs.fromRawMap(defaultPayload), SponsorBlockPrefs.fromRawMap(payload))
    }
}
