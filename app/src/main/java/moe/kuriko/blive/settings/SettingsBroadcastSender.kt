package moe.kuriko.blive.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs

/**
 * 模块侧的广播收发（跑在模块自己的进程里）。
 *
 * - 收到宿主的**显式请求**（[SettingsBroadcast.ACTION_REQUEST]）→ 把当前设置回推给宿主；
 *   manifest 里声明了接收器，所以宿主先启动、模块进程没起来时也能被拉起来。
 * - 设置页保存后调用 [pushSettings] 主动推送一次。
 *
 * 详细的机制取舍见 `hook/sponsorblock/SettingsBroadcast.kt` 的说明。
 */
object SettingsBroadcastSender {

    private const val TAG = "KurikoSB"

    const val ACTION_PUSH = "moe.kuriko.blive.action.SETTINGS_CHANGED"
    const val ACTION_REQUEST = "moe.kuriko.blive.action.REQUEST_SETTINGS"
    const val EXTRA_JSON = "settings_json"

    /** 把当前设置广播出去（宿主进程活着时就会收到并落盘）。 */
    fun pushSettings(context: Context, reason: String) {
        runCatching {
            val snapshot = SponsorBlockPrefs.load(context)
            val intent = Intent(ACTION_PUSH)
                .putExtra(EXTRA_JSON, SponsorBlockPrefs.toJson(snapshot))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
            Log.i(TAG, "已推送设置（$reason）：enabled=${snapshot.enabled} sponsor=${snapshot.optionOf("sponsor").label}")
        }.onFailure { Log.w(TAG, "推送设置失败：${it.message}") }
    }
}

/** 接收宿主的「请把设置发过来」。 */
class SettingsRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != SettingsBroadcastSender.ACTION_REQUEST) return
        val ctx = context ?: return
        SettingsBroadcastSender.pushSettings(ctx, "宿主请求")
    }
}
