package moe.kuriko.blive.sponsorblock

/**
 * 「什么时候该去查片段」的状态机 —— 纯逻辑，不依赖 Android，可单测。
 *
 * 这里编码的是**请求压力**与**不能漏查**之间的平衡，三条不变量：
 *
 *  1. **同一条视频只查一次**：宿主在播放链路上会反复解析视频（预加载、切清晰度、失败重试、
 *     信息流自动播放），不设闸就会打出很多请求；
 *  2. **只在真的开始播之后查**：位置走过 [startThresholdMs] 才认为用户在看这条视频；
 *  3. **该重查的时候必须重查**（这条最容易被写漏，是两个真机 bug 的来源）：
 *     - 播放停止/播放器空闲后**连同 `activeBvid` 一起放开**，否则「看 A → 离开 → 再回来看 A」
 *       会因为 bvid 没变而永远不再查询，片段再也补不回来；
 *     - 拉取失败后放开，允许（受冷却约束的）重试；
 *     - 切换视频时重置尝试次数。
 *
 * 另外：被冷却挡住（[isCoolingDown]）时**不消耗**尝试次数 —— 控制器是 2 Hz 的 tick，
 * 若把「冷却中」也算作一次尝试，三次之后重试就永远被 MAX_ATTEMPTS 卡死了。
 */
class SegmentFetchGate(
    private val startThresholdMs: Long = DEFAULT_START_THRESHOLD_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    private var activeBvid: String? = null
    private var attempts = 0

    /** 已经为哪条稿件放行过查询。 */
    val active: String? get() = activeBvid

    /** 当前这条稿件已经尝试过几次。 */
    val attemptCount: Int get() = attempts

    /** 切换到别的视频：重置尝试次数。 */
    fun onVideoChanged() {
        activeBvid = null
        attempts = 0
    }

    /**
     * 播放停止 / 播放器空闲。
     *
     * 必须放开 [activeBvid]：调用方在停轮询时会把片段清空，若不放行，
     * 回到同一条视频时「bvid 没变」会让查询被永久跳过。
     */
    fun onPlaybackStopped() {
        activeBvid = null
        attempts = 0
    }

    /** 拉取失败：放开，让后续 tick 能在冷却过去后重试。 */
    fun onFetchFailed() {
        activeBvid = null
    }

    /**
     * 现在是否应该为 [bvid] 发起一次查询。
     *
     * 返回 true 时会**占用一次尝试**并把该稿件记为已放行；返回 false 不会改变任何状态
     * （尤其是「冷却中」不吃掉尝试次数）。
     *
     * @param coolingDown 是否还在同稿件的请求冷却期（见 `FetchThrottle.isCoolingDown`）
     */
    fun tryBeginFetch(bvid: String?, positionMs: Long, coolingDown: Boolean): Boolean {
        if (bvid.isNullOrEmpty()) return false
        if (bvid == activeBvid) return false
        if (positionMs < startThresholdMs) return false
        if (coolingDown) return false
        if (attempts >= maxAttempts) return false
        activeBvid = bvid
        attempts++
        return true
    }

    companion object {
        /** 播放位置走过这么多毫秒才认为「用户真的在看这条视频」。 */
        const val DEFAULT_START_THRESHOLD_MS = 800L

        /** 同一条稿件最多尝试几次（覆盖网络抖动，同时兜住请求压力）。 */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
