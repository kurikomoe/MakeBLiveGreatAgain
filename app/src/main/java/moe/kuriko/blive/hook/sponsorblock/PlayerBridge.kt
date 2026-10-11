package moe.kuriko.blive.hook.sponsorblock

import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 播放器 core 的反射桥。
 *
 * 只依赖**方法形状**（名字 + 参数/返回类型 + 有方法体），不依赖具体混淆类名，
 * 这样宿主改版重排混淆名时只有 [HookTargets] 里的候选需要维护。
 */
object PlayerBridge {

    private const val TAG = "KurikoSB"

    /**
     * 是否使用精确 seek（IJK 的 accurate 标志）。
     *
     * `true`：跳到片段结束点**精确位置**，广告尾巴不会漏出来（慢一点）；
     * `false`：跳到目标前最近的关键帧（快，但会漏 1~2 s 广告，等于没跳干净）。
     */
    private const val ACCURATE_SEEK = true

    /** 沿继承链收集所有 `declaredMethods`（子类优先）。 */
    fun methodsInHierarchy(clazz: Class<*>): List<Method> {
        val out = LinkedHashSet<Method>()
        var current: Class<*>? = clazz
        var depth = 0
        while (depth < 12) {
            val type = current ?: break
            if (type == Any::class.java) break
            runCatching { out.addAll(type.declaredMethods) }
            current = type.superclass
            depth++
        }
        return out.toList()
    }

    /** 无参、返回 int/long、且有方法体的方法。 */
    fun findTimeMethod(target: Class<*>, names: List<String>): Method? =
        methodsInHierarchy(target).firstOrNull { method ->
            method.name in names &&
                method.parameterCount == 0 &&
                !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                (method.returnType == Int::class.javaPrimitiveType ||
                    method.returnType == Long::class.javaPrimitiveType)
        }

    /**
     * 找 seek 方法：返回 void，参数为 `(int)` 或 `(int, boolean)`。
     *
     * 优先按 [HookTargets.SEEK_METHOD_CANDIDATES] 的顺序匹配名字，匹配不到再退回
     * 「任意符合形状的方法」（宿主把方法名也混淆掉时的兜底）。
     */
    fun findSeekMethod(target: Class<*>): Method? {
        val all = methodsInHierarchy(target).filter { method ->
            method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                (method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) ||
                    method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                    ))
        }
        HookTargets.SEEK_METHOD_CANDIDATES.forEach { name ->
            // 两参版本（smooth seek）优先
            all.firstOrNull { it.name == name && it.parameterCount == 2 }?.let { return it }
        }
        HookTargets.SEEK_METHOD_CANDIDATES.forEach { name ->
            all.firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    /** 调用无参数值方法，失败返回 null。 */
    fun invokeNumber(method: Method, target: Any): Long? = runCatching {
        (method.invoke(target) as? Number)?.toLong()
    }.onFailure { Log.w(TAG, "${method.name} 调用失败：${it.message}") }.getOrNull()

    /**
     * 执行 seek。
     *
     * 两参版本 `(int, boolean)` 的第二参是 IJK 的 **accurate** 标志
     * （反编译实证：`IjkMediaPlayAdapter.c(long, boolean)` → `ijkMediaPlayer.seekTo(j, z)`；
     * 而它自己的 `seekTo(long)` 走的是 `seekTo(j, false)`）。
     *
     * 这里刻意传 [ACCURATE_SEEK] = `true`：非精确 seek 只会落到目标位置**之前**的最近关键帧，
     * 对「跳到广告片段结束」来说等于把广告尾巴又看了一遍（bilibili 的 GOP 通常在 1~2 s）。
     * 代价是精确 seek 需要从关键帧解码到目标点，略慢一点。
     *
     * 位置先夹到 `[0, Int.MAX_VALUE]`，防止越界后回卷成负数把播放头拽回片头。
     */
    fun seek(target: Any, method: Method, positionMs: Long): Boolean {
        val clamped = positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return runCatching {
            if (method.parameterCount == 2) {
                method.invoke(target, clamped, ACCURATE_SEEK)
            } else {
                method.invoke(target, clamped)
            }
            true
        }.onFailure { Log.w(TAG, "seek(${method.name}) 失败：${it.message}") }.getOrDefault(false)
    }

    /** 取对象上某个无参方法返回的字符串（用于 `getBvid` 之类）。 */
    fun callString(target: Any?, methodName: String): String? = runCatching {
        target?.javaClass?.let { clazz ->
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                }?.let { method ->
                    method.isAccessible = true
                    return@runCatching method.invoke(target) as? String
                }
                current = current.superclass
            }
            null
        }
    }.getOrNull()

    /** 取对象上某个无参方法返回的数值（用于 `getCid` / `getAid` 之类）。 */
    fun callLong(target: Any?, methodName: String): Long? = runCatching {
        target?.javaClass?.let { clazz ->
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                }?.let { method ->
                    method.isAccessible = true
                    return@runCatching (method.invoke(target) as? Number)?.toLong()
                }
                current = current.superclass
            }
            null
        }
    }.getOrNull()

    /** 取对象上某个无参方法返回的对象。 */
    fun callObject(target: Any?, methodName: String): Any? = runCatching {
        target?.javaClass?.let { clazz ->
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                }?.let { method ->
                    method.isAccessible = true
                    return@runCatching method.invoke(target)
                }
                current = current.superclass
            }
            null
        }
    }.getOrNull()

    /** 按字段名读 long 字段（兜底：宿主用字段而不是 getter 暴露 aid/cid）。 */
    fun fieldLong(target: Any?, fieldNames: List<String>): Long? = runCatching {
        target?.javaClass?.let { clazz ->
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredFields.firstOrNull { it.name in fieldNames }?.let { field ->
                    field.isAccessible = true
                    return@runCatching (field.get(target) as? Number)?.toLong()
                }
                current = current.superclass
            }
            null
        }
    }.getOrNull()

    /** 按字段名读字符串字段。 */
    fun fieldString(target: Any?, fieldNames: List<String>): String? = runCatching {
        target?.javaClass?.let { clazz ->
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredFields.firstOrNull { it.name in fieldNames }?.let { field ->
                    field.isAccessible = true
                    return@runCatching field.get(target) as? String
                }
                current = current.superclass
            }
            null
        }
    }.getOrNull()

    /** 设置音量（`setVolume(float, float)`）。 */
    fun setVolume(target: Any?, left: Float, right: Float): Boolean = runCatching {
        val method = target?.javaClass?.let { clazz ->
            methodsInHierarchy(clazz).firstOrNull {
                it.name == HookTargets.SET_VOLUME_METHOD &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Float::class.javaPrimitiveType
            }
        } ?: return false
        method.invoke(target, left, right)
        true
    }.getOrDefault(false)

    /** 读取当前音量（`getVolume()` 返回 `Pair<Float,Float>`，取 first）。 */
    fun getVolume(target: Any?): Float? = runCatching {
        val method = target?.javaClass?.let { clazz ->
            methodsInHierarchy(clazz).firstOrNull {
                it.name == "getVolume" && it.parameterCount == 0
            }
        } ?: return null
        val pair = method.invoke(target)
        val first = pair?.javaClass?.declaredMethods?.firstOrNull { it.name == "getFirst" }
        (first?.invoke(pair) as? Number)?.toFloat()
    }.getOrNull()
}
