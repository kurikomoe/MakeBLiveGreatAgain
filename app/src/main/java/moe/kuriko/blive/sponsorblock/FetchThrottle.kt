package moe.kuriko.blive.sponsorblock

/**
 * 请求节流策略 —— 纯逻辑，不依赖 Android，可单测。
 *
 * 为什么要它：宿主在播放链路上会**反复**触发视频解析（预加载、切清晰度、失败重试、
 * 信息流内嵌播放器自动播放……），每一次都可能带来一次「新视频」事件。直接照单全收
 * 会给 bsbsb.top（社区自费维护的服务器）造成不必要的压力。
 *
 * 三道闸：
 *  1. [canRequest]：同一条稿件在 [perVideoCooldownMs] 内**不再重复联网**
 *     （成功的结果本来就有缓存命中，这条主要防的是失败/空结果被反复重试）；
 *  2. [delayBeforeRequestMs]：全局最小请求间隔，多人/多播放器同时说话时把请求摊开；
 *  3. 真正的「只在进入视频时查一次」由调用方保证（见 `SkipController`：必须等播放器
 *     真的开始走时间轴才发起查询，而不是解析出播放地址就查）。
 */
class FetchThrottle(
    private val minGlobalIntervalMs: Long = DEFAULT_MIN_GLOBAL_INTERVAL_MS,
    private val perVideoCooldownMs: Long = DEFAULT_PER_VIDEO_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** bvid -> 最近一次真正发起网络请求的时间。 */
    private val lastAttemptAt = HashMap<String, Long>()

    private var lastNetworkAtMs = 0L

    /** 统计实际发出的网络请求数（诊断用）。 */
    var networkRequestCount: Long = 0L
        private set

    /** 因冷却被挡下的请求数（诊断用）。 */
    var suppressedCount: Long = 0L
        private set

    /**
     * 该稿件是否还在冷却期内（**纯查询，不计数**）。
     *
     * 调用方在「决定要不要发起一次查询」之前用它做门禁，这样被挡下的尝试不会被
     * 反复计入统计（tick 是 2 Hz，若每次都计数会把 [suppressedCount] 撑爆）。
     */
    @Synchronized
    fun isCoolingDown(bvid: String): Boolean {
        val last = lastAttemptAt[bvid] ?: return false
        return clock() - last < perVideoCooldownMs
    }

    /** 该稿件是否已经过了冷却期，可以联网（会把被挡下的次数记进 [suppressedCount]）。 */
    @Synchronized
    fun canRequest(bvid: String): Boolean {
        val allowed = !isCoolingDown(bvid)
        if (!allowed) suppressedCount++
        return allowed
    }

    /** 距离下一次可以发请求还要等多久（全局最小间隔）。 */
    @Synchronized
    fun delayBeforeRequestMs(): Long =
        (lastNetworkAtMs + minGlobalIntervalMs - clock()).coerceAtLeast(0L)

    /** 记录一次真正发起的网络请求。 */
    @Synchronized
    fun recordRequest(bvid: String) {
        val now = clock()
        lastAttemptAt[bvid] = now
        lastNetworkAtMs = now
        networkRequestCount++
    }

    /** 清空节流状态（换服务器 / 手动刷新时用）。 */
    @Synchronized
    fun reset() {
        lastAttemptAt.clear()
        lastNetworkAtMs = 0L
    }

    companion object {
        /** 全局最小请求间隔。 */
        const val DEFAULT_MIN_GLOBAL_INTERVAL_MS = 3_000L

        /** 同一条稿件的联网冷却。 */
        const val DEFAULT_PER_VIDEO_COOLDOWN_MS = 60_000L
    }
}
