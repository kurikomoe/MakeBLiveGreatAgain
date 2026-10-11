# MakeBLiveGreatAgain

B 站增强 Xposed 模块（YukiHookAPI 构建），包含两块功能：

## 1. 空降助手（xposed 版 BilibiliSponsorBlock）

从 [小电视空降助手 / BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock)
的社区数据源（默认 `https://www.bsbsb.top`）拉取众包标记的片段，在 B 站客户端里自动跳过
赞助广告、开场动画、互动提醒等片段。**只改本机播放行为**，不登录、不上报、不提交。

- 跳过方式按分类配置：禁用 / 仅提示 / 手动跳过 / 自动跳过（默认 sponsor、padding、
  非音乐片段为自动跳过，其余为提示）
- `actionType=mute` 的片段在区间内**静音播放**而不是跳转
- 播放器进度条按分类显示彩色片段标记（颜色与分类一一对应）
- 最小片段时长过滤、可在设置页改数据源地址、清空片段缓存

实现要点（逆向结论见 [`FINDINGS.md`](FINDINGS.md)）：

| 环节 | 做法 |
| --- | --- |
| 视频身份 | hook `PlayerMoss.playViewUnite` 的请求取 `bvid` / `vod.cid`；兜底读播放地址解析任务的 `IResolveParams.getId()`（`"avid+cid"`），再本地做 BV/AV 转换 |
| 播放器控制 | 按**方法形状**反射定位 `getCurrentPosition()` / `getDuration()` / `seekTo(int, boolean)`，不硬编码混淆类名 |
| 跳过判定 | 纯函数 `SkipDecision`（半开区间 + 提前量 + 逐分类策略），单测覆盖边界 |
| 分 P 处理 | `SegmentSelector`：cid 精确匹配 → `videoDuration` ±5s → 原样返回 |
| 请求压力 | 只在真的开始播放时查一次 + 同稿件 60s 冷却 + 全局 3s 间隔 + 同稿件并发合并 + 1h 缓存 |
| 设置跨进程 | 模块自己的 `ContentProvider`（`ContentResolver.call()`），宿主进程读取 |

配置页在桌面图标「B站小工具」里；设置改动 10 秒内对宿主生效（无需重启 App）。

### 已适配的客户端

| 客户端 | 版本 | 状态 |
| --- | --- | --- |
| `tv.danmaku.bili`（大陆版） | 8.73.0 | 真机验证可用 |
| `com.bilibili.app.in`（国际版） | 3.20.4 | 真机验证可用 |

宿主每次发版都可能重排混淆名，改版失效时只需要维护 `hook/sponsorblock/HookTargets.kt`
那张候选表（并且 `.temp/verify_hook_contract.py` 可以对 APK 逐条核对契约）。

## 2. 直播弹幕清理

禁用 B 站直播中的顶端聚合弹幕、表情包弹幕和底部弹幕。

P.S 表情包弹幕和底部弹幕没有做 fuzzy search，可能只在我使用的版本上有效
（现在大家也会注意不发底部弹幕了，应该不需要修了吧）。

代码处于非常原始的状态，能跑就行.jpg

## 构建

```bash
source .temp/env.sh          # 项目内的 Android SDK / Gradle 缓存环境（见 MEMORY.md）
./gradlew assembleDebug      # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # 纯逻辑单测（41 个用例）
```

首次构建需要 Android SDK（`.temp/setup_android_sdk.sh` 会自动装到项目内）。

## 致谢

- 使用 [Yuki Hook API](https://github.com/fankes/YukiHookAPI) 构建模块
- 数据与分类体系来自 [BilibiliSponsorBlock / 小电视空降助手](https://github.com/hanydd/BilibiliSponsorBlock)
- 工程实践参考 [lsposed-bili-sponsorblock (Bili2233)](https://github.com/ch6vip/lsposed-bili-sponsorblock)、
  [BiliSponsorSkip](https://github.com/makabaka11/BiliSponsorSkip)
