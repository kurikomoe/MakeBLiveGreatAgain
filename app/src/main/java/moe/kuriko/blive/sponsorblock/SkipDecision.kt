package moe.kuriko.blive.sponsorblock

/**
 * 跳过判定 —— 纯函数，不依赖 Android，可被单元测试完整覆盖。
 *
 * 语义与上游扩展 `src/content/skipScheduler.ts` 对齐：
 *
 *  - **半开区间**：位置落在 `[start - LOOKAHEAD, end)` 内才算命中；
 *    位置恰好等于 `end` 时片段已经播完，不再回跳（否则会在片段尾部来回抽动）。
 *  - **提前量**：宿主进度回调可能一步跨过片段起点，留 [LOOKAHEAD_MS] 的余量避免漏跳。
 *  - **时长未知不动手**：`durationMs <= 0` 时一律不判定，避免把「还没起播」当成 0 秒位置。
 *  - **零长/逆序片段丢弃**：`end <= start` 的片段不是区间。
 *  - **`poi` 不是区间**：它是单点标记（`start == end`），只在点位前后 [POI_WINDOW_MS] 内提示。
 *  - **`full` 是整片标记**：`[0,0]`，永远不参与跳转。
 */
object SkipDecision {

    /** 命中判定的提前量（毫秒）。 */
    const val LOOKAHEAD_MS = 300L

    /** 单点标记（精彩时刻）的提示窗口。 */
    const val POI_WINDOW_MS = 1_000L

    /** 位置夹到 `[0, duration]`；时长未知时只保证非负。 */
    fun clampPosition(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    /** 片段本身是否是一个可用的区间。 */
    fun isUsableInterval(segment: SponsorSegment, minDurationMs: Long = 0L): Boolean =
        segment.isInterval && segment.startMs >= 0L && segment.durationMs >= minDurationMs

    /**
     * 命中一个可区间的跳过片段（`actionType == skip`）。
     *
     * @param enabled 分类是否启用（关闭的分类连提示都不给）
     */
    fun findSkipSegment(
        positionMs: Long,
        durationMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long = 0L,
        enabled: (String) -> Boolean = { true },
    ): SponsorSegment? = findInterval(positionMs, durationMs, segments, minDurationMs, enabled, ActionType.SKIP)

    /**
     * 命中一个静音片段（`actionType == mute`）。静音片段不跳转，只在区间内压音量。
     */
    fun findMuteSegment(
        positionMs: Long,
        durationMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long = 0L,
        enabled: (String) -> Boolean = { true },
    ): SponsorSegment? = findInterval(positionMs, durationMs, segments, minDurationMs, enabled, ActionType.MUTE)

    /**
     * 命中一个精彩时刻单点（`actionType == poi`）。
     */
    fun findPoiPoint(
        positionMs: Long,
        durationMs: Long,
        segments: List<SponsorSegment>,
        enabled: (String) -> Boolean = { true },
    ): SponsorSegment? {
        if (durationMs <= 0L) return null
        val position = clampPosition(positionMs, durationMs)
        return segments.firstOrNull { segment ->
            segment.actionType == ActionType.POI &&
                enabled(segment.category) &&
                kotlin.math.abs(position - segment.startMs) <= POI_WINDOW_MS
        }
    }

    private fun findInterval(
        positionMs: Long,
        durationMs: Long,
        segments: List<SponsorSegment>,
        minDurationMs: Long,
        enabled: (String) -> Boolean,
        actionType: String,
    ): SponsorSegment? {
        if (durationMs <= 0L) return null
        val position = clampPosition(positionMs, durationMs)
        return segments.firstOrNull { segment ->
            segment.actionType == actionType &&
                enabled(segment.category) &&
                isUsableInterval(segment, minDurationMs) &&
                position >= segment.startMs - LOOKAHEAD_MS &&
                position < segment.endMs
        }
    }
}
