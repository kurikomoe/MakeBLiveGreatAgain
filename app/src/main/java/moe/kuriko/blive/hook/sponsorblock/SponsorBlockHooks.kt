package moe.kuriko.blive.hook.sponsorblock

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * BilibiliSponsorBlock（小电视空降助手）跳过功能的 Hook 安装入口。
 *
 * 三条链路：
 *  1. **播放器 core**：拿到 `IPlayerCoreService` 实现，用于读进度 / seek / 静音；
 *  2. **视频身份**：`PlayerMoss.playViewUnite` 请求里读 bvid / aid / cid；
 *  3. **身份兜底**：播放地址解析任务的 `IResolveParams`（`"avid+cid"`）。
 *
 * 全部 hook 都允许失败：宿主版本变化时只记日志，不影响 B 站正常运行。
 */
object SponsorBlockHooks {

    private const val TAG = "KurikoSB"

    /** 已安装标记，避免重复安装。 */
    @Volatile
    private var installed = false

    /** hook 命中统计，日志里输出一行摘要（借鉴参考实现的 `hook summary: N/M hit`）。 */
    private val hits = LinkedHashMap<String, Boolean>()

    fun install(
        classLoader: ClassLoader,
        hostContext: Context?,
        prefsProvider: (() -> Map<String, Any?>?)? = null,
    ) {
        if (installed) return
        installed = true
        runCatching {
            // Context 按需取：onHook() 阶段 Application 可能还不存在，
            // 一次性取值拿到 null 会让设置通道静默失效（真机踩过）。
            // 这里注册的是「每次重新求值」的来源，PackageParam.appContext 之后被回填也能拿到。
            HostContext.addProvider { hostContext }
            val loadSettings = {
                SponsorBlockPrefs.loadInHost(
                    contextProvider = { HostContext.get() },
                    rawProvider = prefsProvider,
                    // IPC 全挂时用宿主私有目录里的缓存兜底（见 HostConfigCache 的说明）
                    cacheRead = { HostConfigCache.read(HostContext.get()) },
                    // 真的从 IPC 读到时顺手落一份缓存，下次启动可零成本读取
                    onAuthoritativeRead = { HostConfigCache.write(HostContext.get(), it) },
                )
            }
            val snapshot = loadSettings()
            SkipController.init({ HostContext.get() }, snapshot, settingsReloader = loadSettings)
        }.onFailure { Log.e(TAG, "初始化配置失败", it) }

        runCatching { hookPlayerCore(classLoader) }.onFailure { Log.e(TAG, "播放器 hook 失败", it) }
        runCatching { hookVideoIdentity(classLoader) }.onFailure { Log.e(TAG, "身份 hook 失败", it) }
        runCatching { hookResolverFallback(classLoader) }.onFailure { Log.e(TAG, "解析兜底 hook 失败", it) }
        runCatching { record("progress-marker", ProgressMarkerHook.install(classLoader)) }
            .onFailure { Log.e(TAG, "进度条标记 hook 失败", it) }

        val hitCount = hits.count { it.value }
        Log.i(TAG, "hook summary: $hitCount/${hits.size} hit -> ${hits.entries.joinToString { "${it.key}=${if (it.value) "HIT" else "MISS"}" }}")
    }

    /**
     * 宿主 Application 创建完成（此时 Context 一定拿得到）。
     *
     * 把「读配置」的时机提前到 App 启动，而不是等第一个视频被打开；
     * 同时把 Application 作为 Context 来源登记进 [HostContext]。
     */
    fun onHostApplicationCreated(application: Any?) {
        runCatching {
            HostContext.offerContextFrom(application)
            val context = HostContext.get()
            // 1) 注册设置推送接收器（模块保存设置时会广播过来）
            SettingsBroadcast.registerReceiver(context)
            // 2) 先按宿主私有目录里的缓存把配置装上（必然成功、零延迟）
            Log.i(TAG, "宿主 Application 已创建，读取配置缓存")
            SkipController.reloadSettingsNow()
            // 3) 再反向请求一次最新配置，覆盖「宿主先起、用户后改设置」的情况
            SettingsBroadcast.requestSettings(context)
        }.onFailure { Log.w(TAG, "启动时读取配置失败：${it.message}") }
    }

    private fun record(name: String, ok: Boolean) {
        hits[name] = ok
    }

    // ---------------------------------------------------------------- 播放器 core

    private fun hookPlayerCore(classLoader: ClassLoader) {
        var hooked = false
        HookTargets.CORE_IMPL_CLASSES.forEach { className ->
            val clazz = findClass(className, classLoader) ?: return@forEach
            hooked = hookCoreClass(clazz) || hooked
        }
        if (!hooked) {
            // 兜底：接口本身（默认方法有方法体）
            HookTargets.CORE_INTERFACE_CLASSES.forEach { className ->
                val clazz = findClass(className, classLoader) ?: return@forEach
                hooked = hookCoreClass(clazz) || hooked
            }
        }
        record("player-core", hooked)
        Log.i(TAG, if (hooked) "播放器 core hook 安装成功" else "未找到播放器 core，跳过功能不可用")
    }

    private fun hookCoreClass(clazz: Class<*>): Boolean {
        var count = 0

        // 1) 位置方法：既是「这个播放器活着」的信号，也是 tick 源
        PlayerBridge.methodsInHierarchy(clazz)
            .filter { it.name in HookTargets.POSITION_METHODS && it.parameterCount == 0 && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                count += hook(method) {
                    it.thisObject?.let { player -> SkipController.bindPlayer(player) }
                }
            }

        // 2) 状态方法：有些场景位置方法还没被调用，先用状态变化绑定
        //    （只在当前绑定的播放器已经不活跃时才接管，避免把真正在播的挤掉）
        PlayerBridge.methodsInHierarchy(clazz)
            .filter { it.name in HookTargets.STATE_METHODS && it.parameterCount == 0 && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                count += hook(method) {
                    val player = it.thisObject ?: return@hook
                    SkipController.bindPlayer(player, fromPositionHook = false)
                }
            }

        // 3) 构造函数：最早的绑定时机（预加载播放器也会命中，后续会被真正的播放器覆盖）
        if (!clazz.isInterface && !Modifier.isAbstract(clazz.modifiers)) {
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val player = param.thisObject ?: return
                            SkipController.bindPlayer(player, fromPositionHook = false)
                        }.onFailure { Log.w(TAG, "构造函数绑定失败：${it.message}") }
                    }
                })
                count++
            }
        }
        return count > 0
    }

    private fun hook(method: Method, action: (XC_MethodHook.MethodHookParam) -> Unit): Int {
        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { action(param) }.onFailure { Log.w(TAG, "${method.name} hook 回调异常：${it.message}") }
                }
            })
            1
        }.onFailure { Log.w(TAG, "hook ${method.declaringClass.name}.${method.name} 失败：${it.message}") }
            .getOrDefault(0)
    }

    // ---------------------------------------------------------------- 视频身份（主链路）

    private fun hookVideoIdentity(classLoader: ClassLoader) {
        val mossClass = findClass(HookTargets.PLAYER_MOSS_CLASS, classLoader)
        if (mossClass == null) {
            record("video-identity", false)
            return
        }
        val methods = HookTargets.PLAY_MOSS_METHODS.flatMap { name ->
            mossClass.declaredMethods.filter { it.name == name }
        }
        methods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val request = param.args?.firstOrNull() ?: return
                            readIdentityFromRequest(request)
                        }.onFailure { Log.w(TAG, "读取 playViewUnite 请求失败：${it.message}") }
                    }
                })
            }.onFailure { Log.w(TAG, "hook ${method.name} 失败：${it.message}") }
        }
        record("video-identity", methods.isNotEmpty())
        Log.i(TAG, "playViewUnite hook：${methods.size} 个重载")
    }

    private fun readIdentityFromRequest(request: Any) {
        val vod = PlayerBridge.callObject(request, "getVod")
        val bvid = PlayerBridge.callString(request, "getBvid")
            ?: PlayerBridge.callString(vod, "getBvid")
        val cid = PlayerBridge.callLong(vod, "getCid") ?: PlayerBridge.callLong(request, "getCid")
        val aid = PlayerBridge.callLong(vod, "getAid") ?: PlayerBridge.callLong(request, "getAid")
        if (cid == null && bvid == null && aid == null) return
        SkipController.updateVideo(bvid, aid, cid)
    }

    // ---------------------------------------------------------------- 视频身份（兜底链路）

    /**
     * 兜底：播放地址解析任务持有 `IResolveParams`，其 `getId()` 是 `"avid+cid"`。
     *
     * 8.73.0 实测构造签名：`NormalMediaResourceResolveTask(boolean, Context, boolean, IResolveParams, DownloadParams)`。
     */
    private fun hookResolverFallback(classLoader: ClassLoader) {
        var hooked = false
        HookTargets.RESOLVE_TASK_CLASSES.forEach { className ->
            val clazz = findClass(className, classLoader) ?: return@forEach
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            param.args?.forEach { arg -> readIdentityFromParams(arg) }
                        }.onFailure { Log.w(TAG, "读取解析参数失败：${it.message}") }
                    }
                })
                hooked = true
            }.onFailure { Log.w(TAG, "hook $className 构造失败：${it.message}") }
        }
        record("identity-fallback", hooked)
    }

    private fun readIdentityFromParams(params: Any?) {
        if (params == null) return
        val id = PlayerBridge.callString(params, HookTargets.RESOLVE_PARAMS_ID_METHOD)
        if (!id.isNullOrEmpty() && id.contains("+")) {
            val parts = id.split("+")
            val aid = parts.getOrNull(0)?.toLongOrNull()
            val cid = parts.getOrNull(1)?.toLongOrNull()
            if (aid != null && aid > 0L && cid != null && cid > 0L) {
                SkipController.updateVideo(null, aid, cid)
                return
            }
        }
        val aid = PlayerBridge.callLong(params, HookTargets.RESOLVE_PARAMS_AVID_METHOD)
        val cid = PlayerBridge.callLong(params, HookTargets.RESOLVE_PARAMS_CID_METHOD)
        if (aid != null && aid > 0L) SkipController.updateVideo(null, aid, cid)
    }

    // ---------------------------------------------------------------- 工具

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, classLoader) }
            .onFailure { Log.d(TAG, "类不存在：$name（${it.message}）") }
            .getOrNull()
}
