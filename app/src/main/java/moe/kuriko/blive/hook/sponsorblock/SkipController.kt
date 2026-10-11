package moe.kuriko.blive.hook.sponsorblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.util.Log
import android.widget.Toast
import moe.kuriko.blive.sponsorblock.ActionType
import moe.kuriko.blive.sponsorblock.BvAv
import moe.kuriko.blive.sponsorblock.SkipDecision
import moe.kuriko.blive.sponsorblock.SegmentFetchGate
import moe.kuriko.blive.sponsorblock.SegmentSelector
import moe.kuriko.blive.sponsorblock.SkipOption
import moe.kuriko.blive.sponsorblock.SponsorBlockApi
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs
import moe.kuriko.blive.sponsorblock.SponsorCategories
import moe.kuriko.blive.sponsorblock.SponsorSegment
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 跳过控制器：把「宿主播放器位置」和「SponsorBlock 片段」对接起来。
 *
 * 数据流：
 * ```
 *  PlayerCoreServiceV2.getCurrentPosition()  ──hook──▶ bindPlayer()
 *  PlayerMoss.playViewUnite / 解析任务        ──hook──▶ updateVideo(bvid, cid)
 *                                                       │
 *                                        SponsorBlockApi.fetchAsync（1h 缓存）
 *                                                       ▼
 *  主线程 500ms 轮询 getCurrentPosition() ──▶ onPosition() ──▶ 决策 ──▶ seek / mute / Toast
 * ```
 *
 * 所有对外暴露的方法都必须吞异常：它们运行在宿主进程里，任何未捕获异常都会让 B 站崩溃。
 */
object SkipController {

    private const val TAG = "KurikoSB"

    /** 同一个片段在这个时间内不重复跳（防止 seek 落点不准导致死循环）。 */
    private const val SAME_SEGMENT_COOLDOWN_MS = 4_000L

    /** 轮询间隔。 */
    private const val POLL_INTERVAL_MS = 500L

    /** 连续多少个 tick 都是「位置 0 + 时长未知」就认为播放器空闲（500ms × 20 = 10s）。 */
    private const val IDLE_TICKS_TO_STOP = 20

    /** 配置重读节流间隔。 */
    private const val SETTINGS_RELOAD_INTERVAL_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- 状态

    /** Context 的懒解析器（onHook 阶段 Application 可能还没创建，不能一次性取）。 */
    @Volatile
    private var contextProvider: (() -> Context?)? = null

    @Volatile
    private var settings: SponsorBlockPrefs.Snapshot = SponsorBlockPrefs.DEFAULT

    @Volatile
    private var playerRef: WeakReference<Any>? = null

    @Volatile
    private var seekMethod: Method? = null

    /** 当前稿件的 BV 号（SponsorBlock 的索引键）。 */
    @Volatile
    private var currentBvid: String? = null

    /** 当前分 P 的 cid（多 P 视频用它挑分段）。 */
    @Volatile
    private var currentCid: String? = null

    /** 服务端返回的**全部**分段（含其他分 P）。 */
    @Volatile
    private var rawSegments: List<SponsorSegment> = emptyList()

    /** 已按当前分 P 筛过的分段，跳过判定只用这一份。 */
    @Volatile
    private var segments: List<SponsorSegment> = emptyList()

    /**
     * 「什么时候该查片段」的状态机（纯逻辑，见 [SegmentFetchGate]）。
     * 只在主线程（tick / hook 回调）上访问。
     */
    private val fetchGate = SegmentFetchGate()

    @Volatile
    private var durationMs: Long = 0L

    private var lastSkipUuid: String? = null

    /** 最近一次记录过日志的命中片段（只用于诊断，避免每 500ms 刷屏）。 */
    private var lastNotifiedUuid: String? = null
    private var lastSkipAtMs: Long = 0L

    /** 本模块是否把音量压到了 0（离开 mute 片段时要还原）。 */
    private var mutedByModule = false
    private var savedVolume: Float? = null

    /** 已经提示过的片段 uuid（每个片段只提示一次）。 */
    private val notifiedUuids = HashSet<String>()

    private var pollRunning = false

    /** 连续「位置=0 且时长未知」的 tick 数，用来判断播放器已经空闲/销毁。 */
    private var idleTicks = 0

    /** 最近一次收到非零进度的时刻（`uptimeMillis`），用于判断绑定的播放器是否还活着。 */
    @Volatile
    private var lastPositionAtMs = 0L

    /** 重新读取模块配置的回调（由 hook 安装方注入）。 */
    @Volatile
    private var prefsReloader: (() -> SponsorBlockPrefs.Snapshot?)? = null

    /** 上次重读配置的时刻，避免每次 tick 都读文件。 */
    private var lastSettingsReloadAtMs = 0L

    /** 诊断用：最近一次失败原因。 */
    @Volatile
    var lastError: String? = null
        private set

    // ---------------------------------------------------------------- 初始化

    fun init(
        hostContextProvider: () -> Context?,
        snapshot: SponsorBlockPrefs.Snapshot,
        settingsReloader: (() -> SponsorBlockPrefs.Snapshot?)? = null,
    ) {
        contextProvider = hostContextProvider
        settings = snapshot
        prefsReloader = settingsReloader
        Log.i(TAG, "SkipController 初始化：enabled=${snapshot.enabled} 服务器=${snapshot.server}")
    }

    /**
     * 直接套用一份配置（广播推送时用）。
     *
     * 与 [reloadSettingsNow] 的区别：这里不需要再去读任何通道，
     * 配置是被宿主进程**收到广播时直接拿到的**（见 `SettingsBroadcast`）。
     */
    fun applySnapshot(snapshot: SponsorBlockPrefs.Snapshot, reason: String) {
        if (snapshot == settings) return
        settings = snapshot
        Log.i(
            TAG,
            "配置已更新（$reason）：enabled=${snapshot.enabled} 服务器=${snapshot.server} " +
                "分类=${snapshot.options.entries.joinToString { "${it.key}=${it.value.label}" }}",
        )
    }

    /** 立刻重读一次配置（宿主 App 启动时调用）。 */
    fun reloadSettingsNow() = maybeReloadSettings(force = true)

    /**
     * 重新读一遍模块配置。
     *
     * 宿主进程启动时只读一次配置，而设置页是另一个进程写的；不重读的话
     * 「改设置 → 回来看视频」不会生效，必须杀掉宿主才行。
     *
     * @param force true 表示忽略节流（切换视频时调用，保证新视频立刻用上新配置）
     */
    private fun maybeReloadSettings(force: Boolean = false) {
        val reloader = prefsReloader ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSettingsReloadAtMs < SETTINGS_RELOAD_INTERVAL_MS) return
        lastSettingsReloadAtMs = now
        runCatching { reloader() }.getOrNull()?.let { latest ->
            applySnapshot(latest, "重读")
        }
    }

    // ---------------------------------------------------------------- 播放器绑定

    /**
     * 当前绑定的播放器是否「活着」。
     *
     * 判据是最近 3 秒内报过非零进度。首页信息流的内嵌播放器、预加载播放器也会被
     * 构造函数 hook 抓到，用这个判据可以避免它们把真正在播的那个挤掉。
     */
    private fun isBoundPlayerLive(): Boolean =
        SystemClock.uptimeMillis() - lastPositionAtMs < 3_000L

    /**
     * 由 hook 调用：记住 core 实例与 seek 方法。
     *
     * @param fromPositionHook true 表示来自 `getCurrentPosition()`——
     *        这说明宿主正在主动查询这个播放器的进度，直接抢占绑定；
     *        构造函数 / 状态方法只在「当前绑定的播放器已经不活跃」时才接管。
     */
    fun bindPlayer(player: Any, fromPositionHook: Boolean = true) {
        // 播放器一定活着时才需要配置 / 提示，从它的对象图里挖宿主的 Context 最靠谱
        HostContext.offerContextFrom(player)
        val existing = playerRef?.get()
        if (existing === player) {
            // 同一个实例再次被查询：可能刚从「空闲停轮询」里醒过来（暂停很久后继续播），
            // 这里必须顺手把轮询拉起来，否则那一次 stopPolling 之后就再也不会跳过了。
            startPolling()
            return
        }
        if (!fromPositionHook && existing != null && isBoundPlayerLive()) return
        runCatching {
            val method = PlayerBridge.findSeekMethod(player.javaClass)
            if (method == null) {
                lastError = "未找到 seek 方法：${player.javaClass.name}"
                Log.w(TAG, lastError!!)
                return
            }
            if (existing !== player) {
                // 换播放器时重置与上一个播放器相关的状态
                durationMs = 0L
                idleTicks = 0
                restoreVolume()
            }
            playerRef = WeakReference(player)
            seekMethod = method
            Log.i(TAG, "绑定播放器 ${player.javaClass.name}，seek 方法 = ${method.name}${method.parameterTypes.contentToString()}（fromPositionHook=$fromPositionHook）")
            startPolling()
        }.onFailure { Log.w(TAG, "绑定播放器失败：$it") }
    }

    private fun startPolling() {
        if (pollRunning) return
        pollRunning = true
        mainHandler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            runCatching { tick() }.onFailure { Log.w(TAG, "轮询异常：$it") }
            if (pollRunning) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun tick() {
        maybeReloadSettings()
        val player = playerRef?.get() ?: return
        val positionMethod = PlayerBridge.findTimeMethod(player.javaClass, HookTargets.POSITION_METHODS)
            ?: return
        val position = PlayerBridge.invokeNumber(positionMethod, player) ?: return
        if (durationMs <= 0L) {
            val durationMethod = PlayerBridge.findTimeMethod(player.javaClass, HookTargets.DURATION_METHODS)
            val duration = durationMethod?.let { PlayerBridge.invokeNumber(it, player) } ?: 0L
            updateDuration(duration)
        }

        // 空闲检测：播放器已经销毁/切走时，位置与时长会长期停在 0，
        // 此时停掉轮询（下次 getCurrentPosition hook 触发会重新绑定并重启轮询）。
        if (position <= 0L && durationMs <= 0L) {
            idleTicks++
            if (idleTicks >= IDLE_TICKS_TO_STOP) {
                idleTicks = 0
                stopPolling()
                Log.i(TAG, "播放器空闲，暂停进度轮询（${status()}）")
            }
            return
        }
        idleTicks = 0
        lastPositionAtMs = SystemClock.uptimeMillis()

        onPosition(position)
    }

    private fun stopPolling() {
        pollRunning = false
        mainHandler.removeCallbacks(pollRunnable)
        restoreVolume()
        rawSegments = emptyList()
        segments = emptyList()
        // 必须一并放开闸门：否则「看 A → 离开（停轮询并清空片段）→ 再回来看 A」时，
        // 因为 bvid 没变，查询会被永久跳过，片段再也补不回来。
        // 重新查询会命中 1 小时缓存，不会再打服务器。
        fetchGate.onPlaybackStopped()
        notifiedUuids.clear()
    }

    // ---------------------------------------------------------------- 视频身份

    /**
     * 宿主解析出新的播放地址时调用。
     *
     * @param bvid 直接拿到的 BV 号，可能为空
     * @param avid 拿不到 BV 号时的 AV 号
     * @param cid  分 P id
     * @param title 可选标题（仅日志）
     */
    fun updateVideo(bvid: String?, avid: Long?, cid: Long?, title: String? = null) {
        runCatching {
            // 换视频时强制重读一次配置：设置页改完直接回来看视频就应该生效
            maybeReloadSettings(force = true)
            val resolvedBvid = bvid?.trim()?.takeIf { it.startsWith("BV") }
                ?: avid?.takeIf { it > 0L }?.let(BvAv::aidToBvid)?.takeIf { it.isNotEmpty() }
                ?: return
            val resolvedCid = cid?.takeIf { it > 0L }?.toString()
            if (resolvedBvid == currentBvid && resolvedCid == currentCid) return
            currentBvid = resolvedBvid
            currentCid = resolvedCid
            rawSegments = emptyList()
            segments = emptyList()
            fetchGate.onVideoChanged()
            notifiedUuids.clear()
            lastNotifiedUuid = null
            durationMs = 0L
            lastSkipUuid = null
            restoreVolume()
            // 注意：这里**不**发请求 —— 只登记身份，等播放器真的开始走时间轴再查
            // （见 maybeFetchForCurrentVideo），避免预加载/切清晰度/失败重试把请求打爆。
            Log.i(TAG, "登记待播视频 bvid=$resolvedBvid cid=$resolvedCid ${title?.let { "title=$it" } ?: ""}")
        }.onFailure { Log.w(TAG, "updateVideo 失败：$it") }
    }

    private fun fetchSegments(bvid: String) {
        SponsorBlockApi.fetchAsync(settings.server, bvid) { result, error ->
            runCatching {
                if (result == null) {
                    lastError = error
                    // 失败时放开闸门，让后续 tick 能重试。
                    // 频率由两层兜住：FetchThrottle 的同稿件冷却（60s）与尝试次数上限，
                    // 既不会因为网络抖动把请求打爆，也不会「一次失败整条视频都不再查」。
                    fetchGate.onFetchFailed()
                    Log.w(TAG, "拉取片段失败：$bvid -> $error（已尝试 ${fetchGate.attemptCount} 次）")
                    return@fetchAsync
                }
                if (currentBvid != bvid) return@fetchAsync // 期间又切走了
                rawSegments = result.segments
                applySegmentFilter("拉到")
                lastError = null
                val cids = SegmentSelector.distinctCids(result.segments)
                Log.i(
                    TAG,
                    "拉到 ${result.segments.size} 个片段：$bvid" +
                        if (cids.size > 1) "（多分 P，共 ${cids.size} 个 cid，当前 cid=$currentCid）" else "",
                )
                segments.forEach { segment ->
                    Log.d(
                        TAG,
                        "  ${segment.category}/${segment.actionType} " +
                            "${segment.startMs}..${segment.endMs}ms cid=${segment.cid}",
                    )
                }
            }.onFailure { Log.w(TAG, "处理片段失败：$it") }
        }
    }

    /**
     * 把拉到的全部分段筛成「当前分 P」的那一组。
     *
     * 多 P 视频里不同分 P 的片段混在同一个数组里（靠 cid 区分），不筛就会用别的分 P 的
     * 时间轴去跳；时长变化（例如换分 P、切换清晰度后时长刷新）时也要重新筛。
     */
    private fun applySegmentFilter(reason: String) {
        val raw = rawSegments
        if (raw.isEmpty()) {
            segments = emptyList()
            return
        }
        val filtered = SegmentSelector.forCurrentPart(raw, currentCid, durationMs)
        if (filtered.size != segments.size) {
            Log.i(TAG, "$reason：按当前分 P 筛出 ${filtered.size}/${raw.size} 个片段（cid=$currentCid duration=${durationMs}ms）")
        }
        segments = filtered
    }

    fun updateDuration(ms: Long) {
        if (ms <= 0L) return
        val changed = kotlin.math.abs(ms - durationMs) > 2_000L
        durationMs = ms
        // 换分 P / 时长刷新后需要按新的 duration 重新挑分段
        if (changed && rawSegments.isNotEmpty()) applySegmentFilter("时长变化")
    }

    // ---------------------------------------------------------------- 核心决策

    /** 由进度 tick 驱动。 */
    fun onPosition(positionMs: Long) {
        maybeFetchForCurrentVideo(positionMs)
        if (!settings.enabled) return
        val all = segments
        if (all.isEmpty()) return
        val duration = durationMs
        val position = SkipDecision.clampPosition(positionMs, duration)

        handleMute(all, position, duration)
        handleSkip(all, position, duration)
    }

    /**
     * 「进入视频」才查询 —— 请求压力的第一道闸。
     *
     * 宿主在播放链路上会反复解析视频（预加载、切清晰度、失败重试、信息流自动播放……），
     * 一解析出播放地址就去查 SponsorBlock 会白白打出很多请求。这里要求两件事同时成立：
     *   1. 有一个**真的在走时间轴**的播放器（[onPosition] 只由已绑定播放器的 tick 驱动）；
     *   2. 位置走过 [PLAYBACK_START_THRESHOLD_MS]，说明用户确实在看这条视频。
     *
     * 同一条稿件只会触发一次（`activeBvid` 记住查过的稿件），后续重复的解析事件不再联网；
     * 网络层还有缓存 + 同稿件合并 + 节流三层兜底。
     */
    private fun maybeFetchForCurrentVideo(positionMs: Long) {
        val bvid = currentBvid ?: return
        if (!fetchGate.tryBeginFetch(bvid, positionMs, SponsorBlockApi.isCoolingDown(bvid))) return
        Log.i(TAG, "开始播放，查询片段：$bvid（cid=$currentCid，第 ${fetchGate.attemptCount} 次）")
        fetchSegments(bvid)
    }

    /**
     * 处理跳过。
     *
     * 判定本身是纯函数（[SkipDecision]），这里只负责「按分类策略决定跳还是只提示」。
     */
    private fun handleSkip(all: List<SponsorSegment>, position: Long, duration: Long) {
        val snapshot = settings
        val enabled = { category: String -> snapshot.notifies(category) }

        // 精彩时刻（poi）：单点标记，默认只提示；只有显式打开 poiAutoSkip + 该分类为自动跳过时才 seek
        SkipDecision.findPoiPoint(position, duration, all, enabled)?.let { poi ->
            if (snapshot.poiAutoSkip && snapshot.autoSkips(poi.category)) {
                performSeek(poi.startMs, poi, "跳到精彩时刻")
            } else {
                notifyOnce(poi, "精彩时刻：${formatDuration(poi.startMs)}")
            }
        }

        val hit = SkipDecision.findSkipSegment(position, duration, all, snapshot.minDurationMs, enabled) ?: return
        val option = snapshot.optionOf(hit.category)
        if (hit.uuid != lastNotifiedUuid) {
            // 只记一次，避免每 500ms 刷屏；用于回答「为什么没提示」这类问题
            Log.i(
                TAG,
                "命中片段：${hit.category} ${hit.startMs}..${hit.endMs}ms，策略=${option.label}" +
                    "（showToast=${snapshot.showToast} 位置=${position}ms）",
            )
            lastNotifiedUuid = hit.uuid
        }
        when (option) {
            SkipOption.AUTO -> {
                val now = System.currentTimeMillis()
                if (hit.uuid == lastSkipUuid && now - lastSkipAtMs < SAME_SEGMENT_COOLDOWN_MS) return
                lastSkipUuid = hit.uuid
                lastSkipAtMs = now
                performSeek(hit.endMs, hit, "跳过")
            }
            SkipOption.MANUAL, SkipOption.NOTIFY -> {
                notifyOnce(hit, "${SponsorCategories.labelOf(hit.category)} ${formatDuration(hit.durationMs)}")
            }
            SkipOption.DISABLED -> Unit
        }
    }

    private fun performSeek(targetMs: Long, segment: SponsorSegment, action: String) {
        val player = playerRef?.get()
        val method = seekMethod
        if (player == null || method == null) {
            lastError = "未绑定播放器，无法跳过"
            return
        }
        if (PlayerBridge.seek(player, method, targetMs)) {
            Log.i(TAG, "$action 到 ${targetMs}ms（${segment.category}）")
            toast("已${action}：${SponsorCategories.labelOf(segment.category)} ${formatDuration(segment.durationMs)}")
            // seek 之后宿主会在下一 tick 报告新位置，这里先清掉 mute 状态避免误还原
            restoreVolume()
        } else {
            lastError = "seek 调用失败"
        }
    }

    private fun handleMute(all: List<SponsorSegment>, position: Long, duration: Long) {
        if (!settings.muteSegments) {
            restoreVolume()
            return
        }
        val snapshot = settings
        val hit = SkipDecision.findMuteSegment(
            position,
            duration,
            all,
            snapshot.minDurationMs,
        ) { category -> snapshot.notifies(category) }
        if (hit != null) {
            if (!mutedByModule) {
                val player = playerRef?.get() ?: return
                savedVolume = PlayerBridge.getVolume(player) ?: 1f
                if (PlayerBridge.setVolume(player, 0f, 0f)) {
                    mutedByModule = true
                    notifyOnce(hit, "${SponsorCategories.labelOf(hit.category)}：已静音 ${formatDuration(hit.durationMs)}")
                    Log.i(TAG, "静音 ${hit.startMs}..${hit.endMs}ms（${hit.category}）")
                }
            }
        } else if (mutedByModule) {
            restoreVolume()
        }
    }

    private fun restoreVolume() {
        if (!mutedByModule) return
        val player = playerRef?.get()
        mutedByModule = false
        if (player != null) {
            val volume = savedVolume ?: 1f
            PlayerBridge.setVolume(player, volume, volume)
            Log.i(TAG, "还原音量到 $volume")
        }
        savedVolume = null
    }

    // ---------------------------------------------------------------- 提示

    private fun notifyOnce(segment: SponsorSegment, message: String) {
        if (!settings.showToast) return
        if (!notifiedUuids.add(segment.uuid.ifEmpty { "${segment.category}@${segment.startMs}" })) return
        toast(message)
    }

    private fun toast(message: String) {
        // 懒解析：安装 hook 时拿不到 Context 也要保证之后能弹出提示
        val context = runCatching { contextProvider?.invoke() }.getOrNull()?.applicationContext
        if (context == null) {
            Log.w(TAG, "拿不到 Context，提示无法显示：$message")
            return
        }
        runCatching {
            mainHandler.post {
                runCatching {
                    // 用 LENGTH_LONG + 顶部显示：播放器底部是控制栏/进度条，
                    // 默认位置的 Toast 会被压在下面（真机上就出现过「提示弹了但看不见」）。
                    Toast.makeText(context, "空降助手：$message", Toast.LENGTH_LONG).apply {
                        setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, (context.resources.displayMetrics.heightPixels * 0.12f).toInt())
                    }.show()
                    Log.i(TAG, "已提示：$message")
                }.onFailure { Log.w(TAG, "Toast 显示失败：${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Toast 调度失败：$it") }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    // ---------------------------------------------------------------- 诊断

    /** 进度条标记用的只读快照（无片段 / 关闭标记时返回 null）。 */
    data class MarkerSnapshot(
        val durationMs: Long,
        val segments: List<SponsorSegment>,
    )

    /**
     * 取当前可用于进度条标记的片段。
     *
     * 只返回「启用的分类」里的区间片段与单点片段；`full` 是整片标记，没有区间可画。
     */
    fun markerSnapshot(): MarkerSnapshot? {
        if (!settings.enabled || !settings.progressMarker) return null
        val duration = durationMs
        if (duration <= 0L) return null
        val all = segments
        if (all.isEmpty()) return null
        val drawable = all.filter { segment ->
            segment.actionType != ActionType.FULL &&
                segment.category != "full" &&
                settings.notifies(segment.category) &&
                (segment.isPoint || segment.durationMs >= settings.minDurationMs)
        }
        if (drawable.isEmpty()) return null
        return MarkerSnapshot(duration, drawable)
    }

    /** 设置页 / 日志用的状态摘要。 */
    fun status(): String = buildString {
        append("播放器=").append(playerRef?.get()?.javaClass?.simpleName ?: "未绑定")
        append("，视频=").append(currentBvid ?: "未知")
        append("，cid=").append(currentCid ?: "未知")
        append("，片段=").append(segments.size)
        append("，时长=").append(durationMs).append("ms")
        lastError?.let { append("，最近错误=").append(it) }
    }
}
