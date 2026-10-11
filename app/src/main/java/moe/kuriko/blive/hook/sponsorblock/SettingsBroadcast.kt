package moe.kuriko.blive.hook.sponsorblock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs

/**
 * 设置从模块进程送到宿主的通道 —— **广播**。
 *
 * ## 为什么不是 ContentProvider / XSharedPreferences
 *
 * 真机实测（国际版，Android 14）两条路都不通：
 *
 * ```
 * W KurikoSB: ContentProvider 读取失败：Unknown authority moe.kuriko.blive.settings
 * W KurikoSB: createPackageContext 读取失败：Application package moe.kuriko.blive not found
 * W KurikoSB: XSharedPreferences 不可用：de.robv.android.xposed.XSharedPreferences
 * ```
 *
 * - ContentProvider：**Android 11+ 包可见性**。宿主看不到模块这个包，Provider 压根解析不到。
 *   `<queries>` 写在模块自己的 manifest 里没用 —— 那只影响「模块能看谁」，不解决「别人看模块」。
 * - `XSharedPreferences`：类确实在 LSPosed 的 `framework.dex` 里，但对模块类加载器不可见；
 *   YukiHookAPI 的 `YukiHookPrefsBridge` 内部同样依赖它，所以一起挂。
 *
 * ## 现在的做法（用户建议的方向，方向反过来）
 *
 * 写不了宿主的私有目录 → 那就**让宿主进程自己写**：
 *
 * ```
 * 模块设置页保存 ──广播(带 JSON)──► 宿主进程的运行时接收器
 *                                      │ 写入自己的 filesDir/kuriko-sponsorblock.json
 *                                      ▼
 *                              宿主每次启动本地读取（零 IPC、必然成功）
 * ```
 *
 * 广播不受包可见性限制（接收方是**运行时注册**的接收器，Android 8 的隐式广播限制只针对
 * manifest 接收器）。代价是必须「保存设置时宿主进程还活着」——实测宿主在后台常驻，
 * 且设置页保存时也会发一次，所以正常流程都能覆盖。
 *
 * 另外宿主启动时会**反向请求**一次（显式广播打给模块的 manifest 接收器），
 * 覆盖「宿主先起、用户后改设置」的场景。
 */
object SettingsBroadcast {

    private const val TAG = "KurikoSB"

    /** 模块 → 宿主：设置已变更，携带完整配置 JSON。 */
    const val ACTION_PUSH = "moe.kuriko.blive.action.SETTINGS_CHANGED"

    /** 宿主 → 模块：请把你的设置发过来（宿主启动时发一次）。 */
    const val ACTION_REQUEST = "moe.kuriko.blive.action.REQUEST_SETTINGS"

    const val EXTRA_JSON = "settings_json"

    /** 模块侧的 manifest 接收器（用于接收宿主的请求）。 */
    private const val MODULE_RECEIVER_CLASS = "moe.kuriko.blive.settings.SettingsRequestReceiver"
    private const val MODULE_PACKAGE = "moe.kuriko.blive"

    @Volatile
    private var registered = false

    /** 在宿主进程注册接收器（宿主 Application 创建后调用）。 */
    fun registerReceiver(context: Context?) {
        val ctx = context ?: return
        if (registered) return
        registered = true
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_PUSH) return
                    applyPayload(receiverContext ?: ctx, intent.getStringExtra(EXTRA_JSON))
                }
            }
            val filter = IntentFilter(ACTION_PUSH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "已注册设置推送接收器：$ACTION_PUSH")
        }.onFailure { Log.w(TAG, "注册设置接收器失败：${it.message}") }
    }

    /** 处理推送来的设置：写进宿主缓存 + 立即生效。 */
    private fun applyPayload(context: Context, json: String?) {
        if (json.isNullOrEmpty()) return
        val snapshot = SponsorBlockPrefs.fromJson(json)
        if (snapshot == null) {
            Log.w(TAG, "推送的配置解析失败，忽略")
            return
        }
        runCatching {
            Log.i(
                TAG,
                "收到设置推送：enabled=${snapshot.enabled} 服务器=${snapshot.server} " +
                    "分类=${snapshot.options.entries.joinToString { "${it.key}=${it.value.label}" }}",
            )
            HostConfigCache.write(context, snapshot)
            SkipController.applySnapshot(snapshot, "广播推送")
        }.onFailure { Log.w(TAG, "应用推送配置失败：${it.message}") }
    }

    /**
     * 宿主启动时反向请求一次配置。
     *
     * 用**显式**广播（指定包名与组件），这样模块的 manifest 接收器即使进程没起来也能被拉起；
     * 隐式广播在 Android 8+ 不会投递给 manifest 接收器。
     */
    fun requestSettings(context: Context?) {
        val ctx = context ?: return
        runCatching {
            val intent = Intent(ACTION_REQUEST).apply {
                component = ComponentName(MODULE_PACKAGE, MODULE_RECEIVER_CLASS)
            }
            ctx.sendBroadcast(intent)
            Log.i(TAG, "已向模块请求配置：$MODULE_PACKAGE/$MODULE_RECEIVER_CLASS")
        }.onFailure { Log.w(TAG, "请求配置失败：${it.message}") }
    }
}
