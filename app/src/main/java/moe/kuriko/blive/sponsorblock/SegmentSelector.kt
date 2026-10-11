package moe.kuriko.blive.sponsorblock

/**
 * 分段筛选 —— 纯函数，可单测。
 *
 * SponsorBlock 的一条稿件（bvid）下，不同分 P 的片段都存在同一个 `segments` 数组里，
 * 靠 `cid` 区分（见上游 `segmentSubmission.ts` 的多 P 处理）。**直接用整个数组会导致
 * 多 P 视频拿别的分 P 的时间轴去跳**，所以在进入跳过判定之前必须筛成「当前分 P」。
 *
 * 上游的取舍（"Multi-part: keep segments whose cid == current page cid; if multiple distinct
 * cids have videoDuration within ±5s of player duration, resolve cid from the page map"）：
 *
 *  1. 有 cid 且命中 → 只留该 cid 的片段（cid 为空的旧数据一并保留，因为无法判断归属）；
 *  2. 没有 cid 命中（cid 未知 / 服务端没带 cid / 是单 P 老数据）→ 退化为按 `videoDuration`
 *     与播放器时长比对（±[DURATION_TOLERANCE_MS]）挑一组；
 *  3. 仍然挑不出来 → 原样返回，宁可多跳也不要漏跳（与上游「不因元数据缺失就不工作」一致）。
 */
object SegmentSelector {

    /** 提交时长与播放时长的允许误差。 */
    const val DURATION_TOLERANCE_MS = 5_000L

    /**
     * 挑出属于当前分 P 的片段。
     *
     * @param cid 当前播放分 P 的 cid，未知传 null
     * @param playerDurationMs 播放器报告的时长；未知传 0
     */
    fun forCurrentPart(
        segments: List<SponsorSegment>,
        cid: String?,
        playerDurationMs: Long,
    ): List<SponsorSegment> {
        if (segments.isEmpty()) return segments

        // 1) 按 cid 精确匹配
        if (!cid.isNullOrEmpty()) {
            val matched = segments.filter { it.cid.isEmpty() || it.cid == cid }
            val hasCidMatch = matched.any { it.cid == cid }
            if (hasCidMatch) return matched
        }

        // 2) 退化为按时长匹配
        if (playerDurationMs > 0L) {
            val byDuration = segments.filter { segment ->
                segment.videoDurationMs > 0L &&
                    kotlin.math.abs(segment.videoDurationMs - playerDurationMs) <= DURATION_TOLERANCE_MS
            }
            if (byDuration.isNotEmpty()) return byDuration
        }

        // 3) 信息不足，原样返回
        return segments
    }

    /** 是否存在多分 P（用于日志诊断）。 */
    fun distinctCids(segments: List<SponsorSegment>): List<String> =
        segments.map { it.cid }.filter { it.isNotEmpty() }.distinct()
}
