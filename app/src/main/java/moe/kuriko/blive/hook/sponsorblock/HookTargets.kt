package moe.kuriko.blive.hook.sponsorblock

/**
 * 宿主（bilibili）hook 目标候选表。
 *
 * 宿主每次发版 R8 都会重排混淆名，把候选集中在这里，改版时只改这一张表。
 *
 * 已实测版本：
 *  - `tv.danmaku.bili` **8.73.0**（.refs/apks 中的大陆版）：播放器实现类**未混淆**，
 *    即 `tv.danmaku.biliplayerimpl.core.PlayerCoreServiceV2`，接口
 *    `tv.danmaku.biliplayerv2.service.IPlayerCoreService`（`seekTo(int)` 有默认实现）。
 *  - `com.bilibili.app.in` **3.20.4**（国际版）：同一职责被混淆成 `tt3.p0` /
 *    接口 `bt3.b`，seek 方法名是 `B0`。这一组来自静态核对，**真机未验证**。
 *
 * 参考：`third/lsposed-bili-sponsorblock` 的 `HostTargets` 思路（集中候选 + 形状校验），
 * 但其取值逆向自国际版 6.5.0/6.6.0，与本项目目标版本不同，不能照搬。
 */
object HookTargets {

    const val HOST_PACKAGE_CN = "tv.danmaku.bili"
    const val HOST_PACKAGE_INTL = "com.bilibili.app.in"

    /** 不应安装 hook 的子进程（省电 + 避免误伤）。 */
    val SUB_PROCESS_SUFFIXES = listOf(
        ":web", ":download", ":pushservice", ":ijkservice", ":widgetProvider",
        ":dd_update", ":heap_analysis", ":safemode", ":sandboxed_process",
    )

    // ---------------------------------------------------------------- 播放器 core

    /**
     * 播放器 core 实现类候选（含方法体的那个类）。
     *
     * `PlayerCoreServiceV2` 8.73.0 实测：
     *  - `getCurrentPosition() -> int`（毫秒）
     *  - `getDuration() -> int`（毫秒）
     *  - `l(int, boolean) -> void`（真正的 seek，日志锚点 `"[player]seek to "`）
     *  - `setVolume(float, float) -> void`
     */
    val CORE_IMPL_CLASSES = listOf(
        "tv.danmaku.biliplayerimpl.core.PlayerCoreServiceV2",
        "tt3.p0",
    )

    /** 播放器 core 接口候选（用于按接口兜底找方法）。 */
    val CORE_INTERFACE_CLASSES = listOf(
        "tv.danmaku.biliplayerv2.service.IPlayerCoreService",
        "bt3.b",
    )

    /** 当前位置方法（无参、返回 int/long，单位毫秒）。 */
    val POSITION_METHODS = listOf("getCurrentPosition")

    /** 时长方法（无参、返回 int/long，单位毫秒）。 */
    val DURATION_METHODS = listOf("getDuration", "getRealDuration")

    /** 播放状态方法（无参、返回 int）。 */
    val STATE_METHODS = listOf("getState")

    /**
     * seek 方法候选（返回 void，参数 `(int)` 或 `(int, boolean)`），按优先级尝试。
     * 8.73.0 是 `l`，3.20.4 是 `B0`，`seekTo` 是接口默认方法（可作为兜底）。
     */
    val SEEK_METHOD_CANDIDATES = listOf("seekTo", "l", "o", "B0")

    /** 音量方法（`setVolume(float, float)`）。 */
    const val SET_VOLUME_METHOD = "setVolume"

    // ---------------------------------------------------------------- 视频身份（bvid / cid）

    /**
     * 播放地址聚合服务（protobuf 族，跨版本相对稳定）：
     * `playViewUnite(PlayViewUniteReq, MossResponseHandler)`。
     *
     * 请求对象上取 `getVod().getCid()/.getAid()` 或 `getBvid()`，
     * 响应对象上取 `getPlayArc().getCid()/.getAid()`。
     * 两个目标版本（8.73.0 / 3.20.4）都实测存在该类的**未混淆**描述符。
     */
    const val PLAYER_MOSS_CLASS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
    const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
    val PLAY_MOSS_METHODS = listOf("playViewUnite", "executePlayViewUnite")

    /**
     * 兜底身份来源：播放地址解析任务 / UGC 解析器。
     *
     * 8.73.0 实测：`NormalMediaResourceResolveTask(boolean, Context, boolean, IResolveParams, DownloadParams)`
     * 的第 4 个参数就是解析参数，`IResolveParams.getId()` 返回 `"avid+cid"`。
     */
    val RESOLVE_TASK_CLASSES = listOf(
        "tv.danmaku.biliplayerv2.service.resolve.NormalMediaResourceResolveTask",
        "com.bilibili.lib.media.resolver2.MediaResolverHandler",
    )
    const val RESOLVE_PARAMS_ID_METHOD = "getId"
    const val RESOLVE_PARAMS_AVID_METHOD = "getAvid"
    const val RESOLVE_PARAMS_CID_METHOD = "getCid"
}
