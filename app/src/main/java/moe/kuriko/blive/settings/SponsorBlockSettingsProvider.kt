package moe.kuriko.blive.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import moe.kuriko.blive.BuildConfig
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs

/**
 * 跨进程设置桥 —— 让宿主（`tv.danmaku.bili` / `com.bilibili.app.in`）进程能读到模块的设置。
 *
 * 为什么必须走 ContentProvider：设置页跑在模块进程里，Hook 跑在宿主进程里，
 * 而宿主**没有任何办法**直接读模块的 SharedPreferences：
 *
 *  - `createPackageContext(..., CONTEXT_IGNORE_SECURITY)`：Android 11+ 被沙箱/SELinux 挡住；
 *  - `de.robv.android.xposed.XSharedPreferences`：LSPosed 这个版本根本没有这个类
 *    （真机日志实测 `ClassNotFoundException: de.robv.android.xposed.XSharedPreferences`）；
 *  - YukiHookAPI 自带的 prefs 桥：依赖模块 prefs 文件已存在且可读，不可靠。
 *
 * 实测可行的只有 `ContentResolver.call()` 这条 Binder IPC 路径（也是
 * `third/lsposed-bili-sponsorblock` 用的办法）。真机日志里
 * `读取模块配置失败，使用默认配置` 就是这么来的 —— 修掉之后设置页才真正生效。
 *
 * 权限：`exported="true"` 且不声明读写权限，靠 [isAllowedCaller] 在代码里做调用方准入。
 */
class SponsorBlockSettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * 宿主端调用方式：
     * ```
     * contentResolver.call(Uri.parse("content://moe.kuriko.blive.settings"), "getSettings", null, null)
     * ```
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_SETTINGS) return null
        val ctx = context ?: return null
        // isAllowedCaller 内部已经打了带 callingPackage/uid/uidPackages 的拒绝日志
        if (!isAllowedCaller(ctx)) return null
        return runCatching {
            // 记账：设置页据此显示「宿主有没有读到过」，省掉抓日志的来回
            SponsorBlockPrefs.markHostRead(ctx, runCatching { callingPackage }.getOrNull())
            val prefs = SponsorBlockPrefs.prefs(ctx)
            // 设置页从没保存过时，先把默认值落盘：这样宿主读到的与设置页显示的一致，
            // 也顺便把 prefs 文件建出来（YukiHookPrefsBridge 那条路依赖它存在）。
            if (prefs.all.isEmpty()) {
                SponsorBlockPrefs.save(ctx, SponsorBlockPrefs.DEFAULT)
            }
            Bundle().apply {
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
                putBoolean(RESULT_OK, true)
            }
        }.onFailure { Log.w(TAG, "读取设置失败：${it.message}") }.getOrNull()
    }

    /**
     * 只放行「模块自己」和「目标宿主」。
     *
     * 判断逻辑抽到纯函数 [SettingsCallerPolicy] 里（有单测），这里只负责取参数。
     */
    private fun isAllowedCaller(ctx: Context): Boolean {
        val allowed = buildSet {
            add(ctx.packageName)
            add(HOST_PACKAGE_CN)
            add(HOST_PACKAGE_INTL)
            add(HOST_PACKAGE_HD)
            // debug 包额外放行 adb shell，便于用 `adb shell content call` 自测 IPC；
            // release 包里 shell 不在白名单（真机上 adb 本身已能 root，这里只是不留口子）。
            if (BuildConfig.DEBUG) add("com.android.shell")
        }
        val callingPackage = runCatching { callingPackage }.getOrNull()
        val uidPackages = runCatching {
            ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
        }.getOrNull()
        val allowedCaller = SettingsCallerPolicy.isAllowed(callingPackage, uidPackages, allowed)
        if (!allowedCaller) {
            Log.w(
                TAG,
                "拒绝设置读取：callingPackage=$callingPackage uid=${Binder.getCallingUid()} " +
                    "uidPackages=${uidPackages?.joinToString()}",
            )
        }
        return allowedCaller
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "KurikoSB"

        /** authority 必须与 AndroidManifest 里的 `${applicationId}.settings` 一致。 */
        const val AUTHORITY = "moe.kuriko.blive.settings"

        const val METHOD_GET_SETTINGS = "getSettings"
        const val RESULT_OK = "ok"

        const val HOST_PACKAGE_CN = "tv.danmaku.bili"
        const val HOST_PACKAGE_INTL = "com.bilibili.app.in"
        const val HOST_PACKAGE_HD = "tv.danmaku.bilibilihd"
    }
}
