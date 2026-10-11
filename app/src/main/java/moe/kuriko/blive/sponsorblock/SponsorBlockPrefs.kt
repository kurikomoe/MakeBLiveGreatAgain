package moe.kuriko.blive.sponsorblock

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * 模块配置（设置页写入、宿主进程读取）。
 *
 * 宿主（`tv.danmaku.bili` / `com.bilibili.app.in`）与模块不是同一个 uid，读配置依次尝试：
 *   1. 模块自己的 **ContentProvider**（`SponsorBlockSettingsProvider`，Binder IPC，
 *      **唯一在真机上验证可行**的方案）；
 *   2. 调用方注入的读取器（YukiHookAPI 的 `YukiHookPrefsBridge`）；
 *   3. `de.robv.android.xposed.XSharedPreferences`（LSPosed 当前版本**没有这个类**）；
 *   4. `createPackageContext(..., CONTEXT_IGNORE_SECURITY)`（Android 11+ 被沙箱挡住）；
 *   5. 默认值。
 * 每一步都只记日志，绝不把异常抛给宿主。
 */
object SponsorBlockPrefs {

    const val TAG = "KurikoSB"

    const val MODULE_PACKAGE = "moe.kuriko.blive"
    const val PREFS_NAME = "sponsorblock"

    /** 与 AndroidManifest 里 `${applicationId}.settings` 以及 Provider 里的常量保持一致。 */
    const val SETTINGS_AUTHORITY = "moe.kuriko.blive.settings"
    const val METHOD_GET_SETTINGS = "getSettings"

    const val KEY_ENABLED = "enabled"
    const val KEY_SERVER = "server"
    const val KEY_MIN_DURATION_SEC = "min_duration_sec"
    const val KEY_MUTE_SEGMENTS = "mute_segments"
    const val KEY_SHOW_TOAST = "show_toast"
    const val KEY_PROGRESS_MARKER = "progress_marker"
    const val KEY_POI_AUTO_SKIP = "poi_auto_skip"

    fun optionKey(categoryId: String) = "option_$categoryId"

    /**
     * 「宿主最近一次成功读取配置」的记账键。
     *
     * 这两个键由 **Provider 在响应宿主调用时**写入，用于在设置页直接回答
     * 「宿主到底有没有读到我的设置」——在此之前只能靠抓 logcat 猜，
     * 而这条链路一旦断掉，表现与「一切正常」完全一样（都退回默认值）。
     */
    const val KEY_HOST_LAST_READ_AT = "host_last_read_at"
    const val KEY_HOST_LAST_READ_BY = "host_last_read_by"

    /** 记录一次宿主读取（由 Provider 调用）。 */
    fun markHostRead(context: Context, callingPackage: String?) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_HOST_LAST_READ_AT, System.currentTimeMillis())
                .putString(KEY_HOST_LAST_READ_BY, callingPackage ?: "?")
                .apply()
        }
    }

    /** 读取「宿主最近一次读取」的记账；从未读过返回 null。 */
    fun hostReadStatus(context: Context): Pair<Long, String>? {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_HOST_LAST_READ_AT, 0L)
        if (at <= 0L) return null
        return at to (prefs.getString(KEY_HOST_LAST_READ_BY, "?") ?: "?")
    }

    /** 一份不可变配置快照。 */
    data class Snapshot(
        val enabled: Boolean = true,
        val server: String = SponsorBlockApi.DEFAULT_SERVER,
        /** 每个分类的跳过方式。 */
        val options: Map<String, SkipOption> = SponsorCategories.all.associate { it.id to it.defaultOption },
        /** 最小片段时长（毫秒），短于此值的片段忽略；0 = 不过滤。 */
        val minDurationMs: Long = 0L,
        /** 是否处理 `actionType=mute` 的片段（区间内静音而不是跳过）。 */
        val muteSegments: Boolean = true,
        /** 跳过时是否弹提示。 */
        val showToast: Boolean = true,
        /** 是否在进度条上绘制片段标记（见 `ui/ProgressMarkerHook`）。 */
        val progressMarker: Boolean = true,
        /** 精彩时刻（poi）是否也自动跳（默认按分类设置走）。 */
        val poiAutoSkip: Boolean = false,
    ) {
        fun optionOf(category: String): SkipOption =
            options[category] ?: SponsorCategories.find(category)?.defaultOption ?: SkipOption.DISABLED

        /** 该分类是否参与自动跳过。 */
        fun autoSkips(category: String): Boolean = optionOf(category) == SkipOption.AUTO

        /** 该分类是否需要给用户提示（自动 / 手动 / 仅提示）。 */
        fun notifies(category: String): Boolean = optionOf(category) != SkipOption.DISABLED
    }

    val DEFAULT = Snapshot()

    // ------------------------------------------------------------------ 模块进程（设置页）

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Snapshot =
        runCatching { fromRawMap(prefs(context).all) }.getOrElse { DEFAULT }

    fun save(context: Context, snapshot: Snapshot) {
        prefs(context).edit().apply {
            putBoolean(KEY_ENABLED, snapshot.enabled)
            putString(KEY_SERVER, snapshot.server)
            putInt(KEY_MIN_DURATION_SEC, (snapshot.minDurationMs / 1000L).toInt())
            putBoolean(KEY_MUTE_SEGMENTS, snapshot.muteSegments)
            putBoolean(KEY_SHOW_TOAST, snapshot.showToast)
            putBoolean(KEY_PROGRESS_MARKER, snapshot.progressMarker)
            putBoolean(KEY_POI_AUTO_SKIP, snapshot.poiAutoSkip)
            SponsorCategories.all.forEach { putInt(optionKey(it.id), snapshot.optionOf(it.id).value) }
        }.apply()
    }

    // ------------------------------------------------------------------ 宿主进程（Hook 侧）

    /**
     * 在宿主进程里读取模块配置。
     *
     * 顺序按「真机实测的可靠度」排：
     *   1. **ContentProvider IPC**（`SponsorBlockSettingsProvider`，Binder 跨进程，唯一实测可靠的）；
     *   2. 调用方注入的读取器（YukiHookAPI 的 `YukiHookPrefsBridge`，依赖模块 prefs 文件已存在）；
     *   3. `de.robv.android.xposed.XSharedPreferences`（**LSPosed 当前版本没有这个类**，必失败）；
     *   4. `createPackageContext(..., CONTEXT_IGNORE_SECURITY)`（Android 11+ 被沙箱挡住）；
     *   5. 默认值。
     *
     * @param rawProvider 由调用方提供的读取器，返回 null 表示不可用
     */
    fun loadInHost(
        contextProvider: () -> Context?,
        rawProvider: (() -> Map<String, Any?>?)? = null,
        cacheRead: (() -> Snapshot?)? = null,
        onAuthoritativeRead: ((Snapshot) -> Unit)? = null,
    ): Snapshot {
        fun authoritative(snapshot: Snapshot, source: String): Snapshot {
            Log.i(
                TAG,
                "配置来源：$source（enabled=${snapshot.enabled} " +
                    "sponsor=${snapshot.optionOf("sponsor").label}）",
            )
            runCatching { onAuthoritativeRead?.invoke(snapshot) }
            return snapshot
        }

        // 顺序是有讲究的：宿主私有目录里的缓存是**主通道**（零 IPC、必然成功）。
        // 而 ContentProvider / XSharedPreferences / createPackageContext 在 Android 11+ 上
        // 实测全部不可用（包可见性挡死 Provider，LSPosed 没把 XSharedPreferences 暴露给模块
        // 类加载器，见 FINDINGS §21），所以它们只在「缓存还是空的」时候尝试一遍。
        val cached = runCatching { cacheRead?.invoke() }.getOrNull()
        if (cached != null) {
            return authoritative(cached, "宿主缓存")
        }

        val hostContext = contextProvider()
        if (hostContext == null) {
            // 这条日志很重要：真机上「读不到设置」最常见的原因就是这里 —— onHook() 阶段
            // 宿主的 Application 还没创建，Context 拿不到，后面几条通道全部无法尝试。
            Log.w(TAG, "无缓存且拿不到宿主 Context，使用内置默认配置")
            return DEFAULT
        }

        readViaContentProvider(hostContext)?.let { return authoritative(it, "ContentProvider") }
        if (rawProvider != null) {
            runCatching { rawProvider() }
                .onFailure { Log.w(TAG, "注入的 prefs 读取器失败：${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return authoritative(fromRawMap(it), "YukiHookPrefsBridge") }
        }
        readViaXSharedPreferences()?.let { return authoritative(it, "XSharedPreferences") }
        readViaPackageContext(hostContext)?.let { return authoritative(it, "createPackageContext") }

        Log.w(TAG, "无缓存且各 IPC 通道均不可用，使用内置默认配置")
        return DEFAULT
    }

    /**
     * 通过模块的 ContentProvider 读取设置。
     *
     * ⚠️ 真机实测：Android 11+ 的**包可见性**会让宿主进程解析不到这个 authority
     * （`Unknown authority moe.kuriko.blive.settings`），所以它现在只是兜底通道，
     * 真正的主力是宿主私有目录里的缓存 + 广播推送（见 `SettingsBroadcast`）。
     */
    private fun readViaContentProvider(hostContext: Context?): Snapshot? = runCatching {
        val context = hostContext ?: return null
        val uri = Uri.parse("content://$SETTINGS_AUTHORITY")
        val bundle = context.contentResolver.call(uri, METHOD_GET_SETTINGS, null, null)
        if (bundle == null) {
            Log.d(TAG, "ContentProvider.call 返回 null（Provider 不存在或调用方被拒）")
            return null
        }
        if (bundle.isEmpty) {
            Log.d(TAG, "ContentProvider 返回空 Bundle")
            return null
        }
        val map = HashMap<String, Any?>()
        bundle.keySet().forEach { key -> map[key] = bundle.get(key) }
        fromRawMap(map)
    }.onFailure { Log.w(TAG, "ContentProvider 读取失败：${it.message}") }.getOrNull()

    private fun readViaXSharedPreferences(): Snapshot? = runCatching {
        val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
        val instance = clazz.getConstructor(String::class.java, String::class.java)
            .newInstance(MODULE_PACKAGE, PREFS_NAME)
        runCatching { clazz.getMethod("reload").invoke(instance) }
        val all = clazz.getMethod("getAll").invoke(instance) as? Map<*, *> ?: return null
        if (all.isEmpty()) return null
        fromRawMap(all)
    }.onFailure { Log.w(TAG, "XSharedPreferences 不可用：${it.message}") }.getOrNull()

    private fun readViaPackageContext(hostContext: Context?): Snapshot? = runCatching {
        val context = hostContext ?: return null
        val moduleContext = context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val all = moduleContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        if (all.isEmpty()) return null
        fromRawMap(all)
    }.onFailure { Log.w(TAG, "createPackageContext 读取失败：${it.message}") }.getOrNull()

    // ------------------------------------------------------------------ 解析

    // ------------------------------------------------------------------ 序列化

    /**
     * 序列化成 JSON。
     *
     * 用途：宿主进程通过**广播**把配置落到自己私有目录（`HostConfigCache`）。
     * 为什么不直接让宿主读模块的 prefs：Android 11+ 的包可见性会把 ContentProvider 挡掉，
     * 而 `XSharedPreferences` 在当前 LSPosed 里对模块类加载器不可见（详见 FINDINGS §21）。
     */
    fun toJson(snapshot: Snapshot): String {
        val options = JSONObject()
        snapshot.options.forEach { (id, option) -> options.put(id, option.value) }
        return JSONObject().apply {
            put(KEY_ENABLED, snapshot.enabled)
            put(KEY_SERVER, snapshot.server)
            put(KEY_MIN_DURATION_SEC, (snapshot.minDurationMs / 1000L).toInt())
            put(KEY_MUTE_SEGMENTS, snapshot.muteSegments)
            put(KEY_SHOW_TOAST, snapshot.showToast)
            put(KEY_PROGRESS_MARKER, snapshot.progressMarker)
            put(KEY_POI_AUTO_SKIP, snapshot.poiAutoSkip)
            put(KEY_OPTIONS, options)
        }.toString()
    }

    /** 从 JSON 还原（与 [toJson] 对称，解析失败返回 null）。 */
    fun fromJson(json: String): Snapshot? = runCatching {
        val obj = JSONObject(json)
        val map = HashMap<String, Any?>()
        map[KEY_ENABLED] = obj.optBoolean(KEY_ENABLED, true)
        map[KEY_SERVER] = obj.optString(KEY_SERVER, DEFAULT.server)
        map[KEY_MIN_DURATION_SEC] = obj.optInt(KEY_MIN_DURATION_SEC, 0)
        map[KEY_MUTE_SEGMENTS] = obj.optBoolean(KEY_MUTE_SEGMENTS, true)
        map[KEY_SHOW_TOAST] = obj.optBoolean(KEY_SHOW_TOAST, true)
        map[KEY_PROGRESS_MARKER] = obj.optBoolean(KEY_PROGRESS_MARKER, true)
        map[KEY_POI_AUTO_SKIP] = obj.optBoolean(KEY_POI_AUTO_SKIP, false)
        obj.optJSONObject(KEY_OPTIONS)?.let { options ->
            SponsorCategories.all.forEach { category ->
                if (options.has(category.id)) {
                    map[optionKey(category.id)] = options.optInt(category.id)
                }
            }
        }
        fromRawMap(map)
    }.getOrNull()

    private const val KEY_OPTIONS = "options"

    /** 把「键 → 值」的原始 map 解析成快照；缺项一律回落到默认值。 */
    fun fromRawMap(raw: Map<*, *>): Snapshot {
        fun bool(key: String, def: Boolean) = (raw[key] as? Boolean) ?: def
        val server = (raw[KEY_SERVER] as? String)?.takeIf { it.isNotBlank() } ?: SponsorBlockApi.DEFAULT_SERVER
        val minSec = (raw[KEY_MIN_DURATION_SEC] as? Number)?.toLong() ?: 0L
        val options = SponsorCategories.all.associate { category ->
            val value = (raw[optionKey(category.id)] as? Number)?.toInt()
            category.id to (value?.let { SkipOption.fromValue(it) } ?: category.defaultOption)
        }
        return Snapshot(
            enabled = bool(KEY_ENABLED, true),
            server = server,
            options = options,
            minDurationMs = (minSec * 1000L).coerceAtLeast(0L),
            muteSegments = bool(KEY_MUTE_SEGMENTS, true),
            showToast = bool(KEY_SHOW_TOAST, true),
            progressMarker = bool(KEY_PROGRESS_MARKER, true),
            poiAutoSkip = bool(KEY_POI_AUTO_SKIP, false),
        )
    }
}
