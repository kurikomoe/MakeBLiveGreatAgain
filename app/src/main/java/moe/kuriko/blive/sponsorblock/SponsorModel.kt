package moe.kuriko.blive.sponsorblock

/**
 * SponsorBlock 片段动作类型（与上游 `ActionType` / API `actionType` 字段一一对应）。
 */
object ActionType {
    /** 区间片段：跳过到区间结束。 */
    const val SKIP = "skip"

    /** 区间片段：不跳转，只在区间内静音。 */
    const val MUTE = "mute"

    /** 整片标记，`segment == [0,0]`，不参与跳过。 */
    const val FULL = "full"

    /** 单点标记（精彩时刻），`segment == [t,t]`，跳过到该点。 */
    const val POI = "poi"
}

/**
 * 每个分类可选的跳过方式，取值与上游 `CategorySkipOption` 保持一致
 * （`Disabled=-1, ShowOverlay=0, ManualSkip=1, AutoSkip=2`）。
 *
 * Android 端没有浏览器扩展那样的页面浮层，[NOTIFY] 对应「只在进入片段时弹提示」，
 * [MANUAL] 对应「提示 + 由用户在播放器上手动跳过」（本模块通过提示里的说明引导，
 * 不主动 seek），两者都**不会**自动跳转。
 */
enum class SkipOption(val value: Int, val label: String) {
    /** 完全忽略该分类。 */
    DISABLED(-1, "禁用"),

    /** 进入片段时提示，但不跳转。 */
    NOTIFY(0, "仅提示"),

    /** 手动跳过：进入片段时提示（不自动跳）。 */
    MANUAL(1, "手动跳过"),

    /** 自动跳过：进入片段立即 seek 到片段结束。 */
    AUTO(2, "自动跳过");

    companion object {
        fun fromValue(value: Int): SkipOption = values().firstOrNull { it.value == value } ?: DISABLED
    }
}

/**
 * SponsorBlock 分类表 —— 分类顺序、`category` 字面量、中文名、默认跳过方式与进度条标记
 * 默认颜色的**单一事实来源**（设置页、跳过决策、进度条标记全部从这里派生）。
 */
object SponsorCategories {

    data class Category(
        /** API 里的 `category` 字面量。 */
        val id: String,
        /** 中文显示名。 */
        val label: String,
        /** 默认跳过方式。 */
        val defaultOption: SkipOption,
        /** 进度条标记默认颜色（ARGB）。 */
        val color: Int,
        /** 该分类是否会下发 `actionType=mute`（音乐区专用）。 */
        val muteSupported: Boolean = true,
    ) {
        val colorHex: String get() = "#%06X".format(color and 0xFFFFFF)
    }

    /** 上游默认值：sponsor/padding/music_offtopic 自动跳过，filler 禁用，其余手动。 */
    val all: List<Category> = listOf(
        Category("sponsor", "赞助/恰饭", SkipOption.AUTO, 0xFF00D200.toInt()),
        Category("selfpromo", "自我推广", SkipOption.MANUAL, 0xFFFFFF00.toInt()),
        Category("interaction", "互动提醒", SkipOption.MANUAL, 0xFFAA00FF.toInt()),
        Category("intro", "开场动画", SkipOption.MANUAL, 0xFF00FFFF.toInt()),
        Category("outro", "结束画面", SkipOption.MANUAL, 0xFF0064FF.toInt()),
        Category("preview", "回顾/概要", SkipOption.NOTIFY, 0xFFFF8000.toInt()),
        Category("padding", "填充内容", SkipOption.AUTO, 0xFF7F00FF.toInt()),
        Category("filler", "离题闲聊", SkipOption.DISABLED, 0xFF9E9E9E.toInt()),
        Category("music_offtopic", "非音乐片段", SkipOption.AUTO, 0xFFFF00B4.toInt(), muteSupported = false),
        Category("poi_highlight", "精彩时刻", SkipOption.MANUAL, 0xFFFF1E1E.toInt()),
        Category("exclusive_access", "独家抢先", SkipOption.NOTIFY, 0xFF00B0FF.toInt()),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): Category? = byId[id]

    fun labelOf(id: String): String = byId[id]?.label ?: id

    fun colorOf(id: String): Int = byId[id]?.color ?: 0xFFFFC400.toInt()
}

/**
 * 一个 SponsorBlock 分段（来自 `/api/skipSegments/{prefix}`，字段名与上游 `SponsorTime` 对齐）。
 */
data class SponsorSegment(
    val uuid: String,
    val category: String,
    val actionType: String,
    val cid: String,
    val startMs: Long,
    val endMs: Long,
    val videoDurationMs: Long,
    val locked: Boolean,
) {
    val durationMs: Long get() = endMs - startMs

    /** `poi` 是单点标记（start == end），语义上不是区间。 */
    val isPoint: Boolean get() = startMs == endMs

    val isInterval: Boolean get() = endMs > startMs
}

/**
 * 某个稿件（bvid）的全部分段。
 *
 * [durationMs] 是提交时刻的视频时长，用于多 P 视频按 cid 挑选正确的分段集合。
 */
data class VideoSegments(
    val bvid: String,
    val durationMs: Long,
    val segments: List<SponsorSegment>,
)
