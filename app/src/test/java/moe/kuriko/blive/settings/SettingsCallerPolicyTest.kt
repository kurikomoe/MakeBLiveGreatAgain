package moe.kuriko.blive.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 调用方准入的测试。
 *
 * 重点是「卡太死」那一侧：Android 11+ 的包可见性可能让 `getPackagesForUid()` 拿不到
 * 宿主包名，如果因此把宿主判成非法调用方，Provider 会返回 null，宿主静默用默认配置，
 * 最后表现成「设置页改了不生效」——真机上这个坑已经踩过一次。
 */
class SettingsCallerPolicyTest {

    private val allowed = setOf("moe.kuriko.blive", "tv.danmaku.bili", "com.bilibili.app.in")

    @Test
    fun `正常的宿主调用放行`() {
        assertTrue(
            SettingsCallerPolicy.isAllowed(
                "tv.danmaku.bili",
                arrayOf("tv.danmaku.bili"),
                allowed,
            ),
        )
    }

    /** 回归用例：包可见性让 uid 反查不到包名时，仍然必须放行宿主。 */
    @Test
    fun `uid 反查不到包名时仍按 callingPackage 放行`() {
        assertTrue(SettingsCallerPolicy.isAllowed("tv.danmaku.bili", null, allowed))
        assertTrue(SettingsCallerPolicy.isAllowed("com.bilibili.app.in", emptyArray(), allowed))
    }

    @Test
    fun `拿不到 callingPackage 时退回按 uid 反查`() {
        assertTrue(SettingsCallerPolicy.isAllowed(null, arrayOf("tv.danmaku.bili"), allowed))
        assertTrue(SettingsCallerPolicy.isAllowed("", arrayOf("com.bilibili.app.in"), allowed))
    }

    @Test
    fun `两边都拿不到就拒绝`() {
        assertFalse(SettingsCallerPolicy.isAllowed(null, null, allowed))
        assertFalse(SettingsCallerPolicy.isAllowed(null, emptyArray(), allowed))
    }

    @Test
    fun `非白名单包一律拒绝`() {
        assertFalse(SettingsCallerPolicy.isAllowed("com.evil.app", arrayOf("com.evil.app"), allowed))
        assertFalse(SettingsCallerPolicy.isAllowed(null, arrayOf("com.evil.app"), allowed))
    }

    /** callingPackage 由系统按 UID 反查，理论上伪造不了；真出现了也要拒绝。 */
    @Test
    fun `包名与 uid 反查结果矛盾时拒绝`() {
        assertFalse(
            SettingsCallerPolicy.isAllowed(
                "tv.danmaku.bili",
                arrayOf("com.evil.app"),
                allowed,
            ),
        )
    }
}
