package moe.kuriko.blive.hook.sponsorblock

import android.content.Context
import android.util.Log
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs
import java.io.File

/**
 * 宿主私有目录里的配置缓存。
 *
 * **为什么需要它**：设置的权威副本在模块进程（设置页写的 SharedPreferences），宿主只能靠
 * ContentProvider 走 IPC 去取 —— 而 IPC 可能因为包可见性、厂商 ROM、模块被停用等原因失败。
 * 一旦失败，宿主就只能用内置默认值，用户「设置页改了不生效」且**无从察觉**。
 *
 * **为什么不是「模块写进宿主目录」**：做不到。`/data/data/<宿主>` 与
 * `/sdcard/Android/data/<宿主>` 都禁止跨应用写（沙箱 + SELinux），模块进程没有 root 权限。
 * 能写宿主私有目录的只有**宿主进程自己** —— 也就是这里的 hook 代码。
 *
 * 于是分工是：
 *  - 权威副本：模块 SharedPreferences（设置页）→ ContentProvider 提供给宿主；
 *  - 宿主缓存：宿主每次**成功**拿到配置后，顺手落一份 JSON 到自己的 `filesDir`；
 *  - 启动时：先读缓存（零成本、必然成功），再异步用 Provider 的结果覆盖，保证既是「启动即可用」
 *    又不会长期停留在旧值上（Provider 有响应时永远以它为准）。
 */
object HostConfigCache {

    private const val TAG = "KurikoSB"
    private const val FILE_NAME = "kuriko-sponsorblock.json"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读缓存；没有或损坏都返回 null（调用方回落到默认值）。 */
    fun read(context: Context?): SponsorBlockPrefs.Snapshot? {
        val ctx = context ?: return null
        return runCatching {
            val file = file(ctx)
            if (!file.isFile) return null
            SponsorBlockPrefs.fromJson(file.readText())
        }.onFailure { Log.w(TAG, "读配置缓存失败：${it.message}") }.getOrNull()
    }

    /** 把配置落盘（best-effort，失败只记日志）。 */
    fun write(context: Context?, snapshot: SponsorBlockPrefs.Snapshot) {
        val ctx = context ?: return
        runCatching { file(ctx).writeText(SponsorBlockPrefs.toJson(snapshot)) }
            .onFailure { Log.w(TAG, "写配置缓存失败：${it.message}") }
    }

}
