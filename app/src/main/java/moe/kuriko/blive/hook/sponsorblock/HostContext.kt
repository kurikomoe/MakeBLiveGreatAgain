package moe.kuriko.blive.hook.sponsorblock

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宿主进程 Context 的**懒解析**（多来源兜底）。
 *
 * **为什么不能只在安装 hook 时拿一次**：`onHook()` 跑在宿主进程刚起的阶段，
 * 那时 `PackageParam.appContext` 往往还是 null（Application 尚未创建）。
 * 把 null 存下来之后，所有依赖它的逻辑都会**静默失效** —— 真机上就这么发生过：
 *
 * ```
 * D KurikoSB: ContentProvider 没拿到数据（未安装 / 被拒 / 空），试下一条通道
 * W KurikoSB: 读取模块配置失败，使用默认配置（设置页的改动将不会生效）
 * ```
 *
 * 既没有「Provider 不存在」也没有「被拒」的日志，因为卡在更前面 —— 读取函数拿到
 * `hostContext == null` 就直接 return 了。同一个坑还会让跳过提示的 Toast 一声不响。
 *
 * 四个来源，按可靠性排序，全部失败就先返回 null，等下一次重试（配置每 10 秒重读一次）：
 *  1. **从播放器对象图里挖**（[offerContextFrom]）—— 播放器一定活着时才需要配置，
 *     所以这是最靠谱的一条：`PlayerContainerImpl.b()` 返回的就是宿主 Context；
 *  2. 注入的 `PackageParam.appContext`（**每次调用时重新读**，YukiHookAPI 会在
 *     Application 创建后回填）；
 *  3. `ActivityThread.currentApplication()`（hidden API，个别 ROM 可能不可用）；
 *  4. 都没有 → null。
 */
object HostContext {

    private const val TAG = "KurikoSB"

    /** 每次调用都重新求值，避免把「当时还是 null」的值固化下来。 */
    private val providers = CopyOnWriteArrayList<() -> Context?>()

    @Volatile
    private var cached: Context? = null

    /** 注册一个 Context 来源（会在每次取值时重新调用）。 */
    fun addProvider(provider: () -> Context?) {
        providers += provider
    }

    /** 取宿主 Context；拿不到返回 null（调用方稍后重试即可）。 */
    fun get(): Context? {
        cached?.let { return it }
        for (provider in providers) {
            val value = runCatching { provider() }.getOrNull()
            if (value != null) {
                val app = value.applicationContext ?: value
                cached = app
                return app
            }
        }
        currentApplication()?.let {
            cached = it
            Log.i(TAG, "宿主 Context 来自 ActivityThread.currentApplication()")
            return it
        }
        return null
    }

    fun hasContext(): Boolean = cached != null

    /**
     * 从一个宿主对象（播放器 core）的对象图里挖 Context。
     *
     * 不硬编码混淆字段名：遍历一层字段，谁身上有「无参且返回 Context」的方法就用谁
     * （`PlayerCoreServiceV2.a` 是 `PlayerContainerImpl`，其 `b()` 正是 `Context`）。
     */
    fun offerContextFrom(root: Any?) {
        if (cached != null || root == null) return
        runCatching {
            if (root is Context) {
                cached = root.applicationContext ?: root
                return
            }
            for (field in root.javaClass.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(root)
                }.getOrNull() ?: continue
                if (value is Context) {
                    cached = value.applicationContext ?: value
                    Log.i(TAG, "宿主 Context 来自 ${root.javaClass.simpleName}.${field.name}")
                    return
                }
                val name = value.javaClass.name
                if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("kotlin.")) {
                    continue
                }
                val method = runCatching {
                    value.javaClass.methods.firstOrNull { candidate ->
                        candidate.parameterCount == 0 &&
                            Context::class.java.isAssignableFrom(candidate.returnType)
                    }
                }.getOrNull() ?: continue
                val context = runCatching { method.invoke(value) }.getOrNull() as? Context ?: continue
                cached = context.applicationContext ?: context
                Log.i(TAG, "宿主 Context 来自 ${root.javaClass.simpleName}.${field.name}.${method.name}()")
                return
            }
        }.onFailure { Log.d(TAG, "从播放器对象图取 Context 失败：${it.message}") }
    }

    /**
     * `ActivityThread.currentApplication()` —— hidden API，在任何 App 进程里都能拿到
     * 本进程的 Application（个别 ROM 的 hidden API 策略可能拦，所以只作兜底）。
     */
    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
