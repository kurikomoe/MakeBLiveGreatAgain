package moe.kuriko.blive.hook.sponsorblock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.kuriko.blive.sponsorblock.SponsorCategories

/**
 * 进度条片段标记（可选项，`PROJECTS.md` 里的「可选：实现进度条标记」）。
 *
 * 做法：hook 播放器进度条 `PlayerDotSeekBar.draw(Canvas)`（8.73.0 实测存在，
 * super 是 `androidx.appcompat.widget.AppCompatSeekBar`），在宿主自己画完之后，
 * 用同一块 Canvas 叠加按分类着色的矩形。
 *
 * 坐标映射：`x = paddingLeft + (ms / durationMs) * (width - paddingLeft - paddingRight)`，
 * 只依赖 View 的实测宽高与当前片段，不依赖宿主内部的进度换算。
 *
 * 只画有区间意义的片段（`skip`/`mute`/`poi`），`full` 是整片标记不参与。
 */
object ProgressMarkerHook {

    private const val TAG = "KurikoSB"

    /**
     * 进度条类名候选（含混淆短名兜底）。
     *
     * 注意：国际版 3.20.4 里没有 `PlayerDotSeekBar`，但 `PlayerSeekWidget3` 在，
     * 而它的 `draw(Canvas)` 来自父类（混淆名 `v3.b`）——[PlayerBridge.methodsInHierarchy]
     * 会把父类方法一并带出来，所以实际挂上的是基类方法，**全 App 的 seek bar 都会走这个 hook**。
     * 这也是为什么绘制前必须用 `max == 视频时长` 过滤（见 [drawMarkers]）。
     */
    private val SEEK_BAR_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerDotSeekBar",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
        "com.bilibili.playerbizcommon.widget.control.PlayerSeekWidget",
        "com.bilibili.playerbizcommon.widget.control.PlayerSeekBar",
    )

    private const val DRAW_METHOD = "draw"

    /** 进度条 `max` 与当前视频时长的允许误差（宿主每次更新都是同一来源，正常应为 0）。 */
    private const val DURATION_TOLERANCE_MS = 1_500L

    // 曾经还有一条「progress ≈ 播放位置」的校验，已删除：拖动进度条时会误判（见 drawMarkers）。

    /** 复用画笔，避免每帧分配。 */
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    fun install(classLoader: ClassLoader): Boolean {
        var hooked = false
        // PlayerSeekWidget3 继承 PlayerDotSeekBar，methodsInHierarchy 会把基类的 draw 也带出来，
        // 用这个集合去重，避免同一个 Method 被挂两次导致重复绘制。
        val seen = HashSet<java.lang.reflect.Method>()
        SEEK_BAR_CLASSES.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClassIfExists(className, classLoader) }.getOrNull()
                ?: return@forEach
            val methods = PlayerBridge.methodsInHierarchy(clazz).filter {
                it.name == DRAW_METHOD &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Canvas::class.java &&
                    // 关键：不能挂到 framework 的方法上。
                    // 真机日志实测：PlayerSeekWidget 的继承链会一路走到 android.view.View.draw(Canvas)，
                    // 挂上去就等于给全 App 每一个 View 的绘制都加了一层回调（性能灾难）。
                    isAppClass(it.declaringClass)
            }
            methods.forEach methodLoop@{ method ->
                if (!seen.add(method)) return@methodLoop
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val view = param.thisObject as? View ?: return
                                val canvas = param.args?.firstOrNull() as? Canvas ?: return
                                drawMarkers(canvas, view)
                            }.onFailure { Log.w(TAG, "绘制片段标记失败：${it.message}") }
                        }
                    })
                    hooked = true
                    Log.i(TAG, "进度条标记 hook：${method.declaringClass.name}.${method.name}(Canvas)")
                }.onFailure { Log.w(TAG, "hook ${clazz.name}.draw 失败：${it.message}") }
            }
        }
        if (!hooked) Log.i(TAG, "未找到进度条控件，进度条标记不可用")
        return hooked
    }

    private fun findClassSafe(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, classLoader) }.getOrNull()

    /**
     * 只认宿主自己的类。
     *
     * framework / 官方库的类（`android.*`、`androidx.*`、`kotlin.*`…）一律排除：
     * 它们的 `draw(Canvas)` 是全局共享的，挂上去会影响整个 App 的每一帧。
     */
    private fun isAppClass(clazz: Class<*>): Boolean {
        val name = clazz.name
        return !name.startsWith("android.") &&
            !name.startsWith("androidx.") &&
            !name.startsWith("java.") &&
            !name.startsWith("javax.") &&
            !name.startsWith("kotlin.") &&
            !name.startsWith("kotlinx.") &&
            !name.startsWith("com.google.android.material.") &&
            !name.startsWith("org.jetbrains.")
    }

    private fun drawMarkers(canvas: Canvas, view: View) {
        val snapshot = SkipController.markerSnapshot() ?: return
        val total = snapshot.durationMs
        if (total <= 0L) return

        // ---- 关键：只画「本视频的进度条」 --------------------------------------
        //
        // 宿主里所有 seek bar 都是同一个类（大陆版 PlayerDotSeekBar / 国际版混淆名 v3.b），
        // 而 PlayerSeekWidget3 是通过
        //     setMax(duration); setProgress(currentPosition);
        // 更新的（两者都是毫秒，见反编译 PlayerSeekWidget3.V3）。所以「max == 当前播放视频时长」
        // 就是「这个进度条正在显示本视频」的判据；信息流里的内嵌播放器、预加载播放器
        // 时长对不上，就不会被画上标记。
        val seekBar = view as? android.widget.ProgressBar ?: return
        if (!view.isShown) return
        val max = seekBar.max
        if (max <= 0) return
        if (kotlin.math.abs(max.toLong() - total) > DURATION_TOLERANCE_MS) return
        // 注意：**不要**拿 `progress` 和播放位置比对。
        // 手动拖动进度条时 progress 必然偏离播放位置，之前那版校验会让标记在拖动过程中
        // 整段消失、松手再出现（真机现象）。时长匹配已经足够锁定「本视频的进度条」，
        // 且拖动时 max 不变，标记可以稳定常驻。

        val left = view.paddingLeft.toFloat()
        val right = (view.width - view.paddingRight).toFloat()
        val span = right - left
        if (span < 24f) return

        // 竖直方向画在进度条中部，高度取控件高度的 1/3（夹在 3~8 dp）
        val density = view.resources.displayMetrics.density
        val barHeight = (view.height / 3f).coerceIn(3f * density, 8f * density)
        val top = (view.height - barHeight) / 2f
        val bottom = top + barHeight
        // 单点标记（poi）画成一根竖线
        val pointWidth = 3f * density

        snapshot.segments.forEach { segment ->
            val startFraction = (segment.startMs.toDouble() / total).coerceIn(0.0, 1.0)
            val endFraction = (segment.endMs.toDouble() / total).coerceIn(0.0, 1.0)
            val x1 = left + (span * startFraction).toFloat()
            val x2 = if (segment.isPoint) x1 + pointWidth else left + (span * endFraction).toFloat()
            if (x2 - x1 < 1f && !segment.isPoint) return@forEach
            rect.set(x1, top, maxOf(x2, x1 + 1f), bottom)
            paint.color = SponsorCategories.colorOf(segment.category)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
        }
    }
}
