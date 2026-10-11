package moe.kuriko.blive.sponsorblock

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SponsorBlock（小电视空降助手 / bsbsb.top）服务端客户端。
 *
 * 协议要点（与浏览器扩展 `src/requests/background/segmentRequest.ts` 完全一致）：
 *  - `GET {server}/api/skipSegments/{hashPrefix}`，**不带任何 query 参数**；
 *  - `hashPrefix` = `sha256(bvid)` 的十六进制**前 4 位**（小写），bvid 原样参与哈希、不做大小写归一；
 *  - 响应是**按前缀分桶的裸数组**，需要自己按 `videoID` 精确匹配挑出本条稿件；
 *  - `200 []` 与 `400`（前缀非法）都等价于「没有片段」；
 *  - 服务端在 Cloudflare 后面，**必须带一个正常的 UA**（缺失或 `Python-urllib` 会被 1010 拒绝）。
 */
object SponsorBlockApi {

    private const val TAG = "KurikoSB"

    /** 默认数据源（与扩展的 `config.json` → `serverAddress` 一致）。 */
    const val DEFAULT_SERVER = "https://www.bsbsb.top"

    /** 备用镜像，主站不可用时按顺序尝试。 */
    val MIRRORS = listOf("https://www.bsbsb.xyz")

    /** 与扩展一致：正常浏览器/客户端 UA，避免被 Cloudflare 挡。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /** 片段缓存有效期（扩展是 1 小时）。 */
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    private data class CacheEntry(val value: VideoSegments, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 同一稿件的并发请求合并：bvid -> 等待回调列表。 */
    private val pending = HashMap<String, MutableList<(VideoSegments?, String?) -> Unit>>()

    /** 请求节流（同一稿件冷却 + 全局最小间隔）。 */
    val throttle = FetchThrottle()

    /**
     * 该稿件是否还在冷却期（纯查询，不联网、不计数）。
     *
     * 调用方在「要不要发起查询」之前用它做门禁，避免冷却期内反复空转。
     */
    fun isCoolingDown(bvid: String): Boolean = throttle.isCoolingDown(bvid)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SponsorBlock-Api").apply { isDaemon = true }
    }

    /** `sha256(bvid)` 的十六进制前 4 位。 */
    fun hashPrefix(bvid: String): String = sha256Hex(bvid).take(4)

    fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 异步拉取某个 bvid 的全部分段。
     *
     * 三道防重复：
     *  1. **缓存命中**（1 小时内）直接回调，不联网、不排队；
     *  2. **同一稿件的并发请求合并**成一次网络请求，结果广播给所有等待者；
     *  3. **节流**：同稿件 60 秒内不重复联网，全局请求间隔不小于 3 秒。
     *
     * @param serverAddress 服务端地址（设置页可改）
     * @param onResult 结果回调，失败时第一参为 null、第二参为可读原因
     */
    fun fetchAsync(
        serverAddress: String,
        bvid: String,
        onResult: (VideoSegments?, String?) -> Unit,
    ) {
        if (bvid.isBlank()) {
            onResult(null, "bvid 为空")
            return
        }
        val cached = cache[bvid]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
            onResult(cached.value, null)
            return
        }

        synchronized(pending) {
            // 2) 已经有同稿件的请求在飞 —— 挂上去一起等，不再发一次
            pending[bvid]?.let { waiters ->
                waiters.add(onResult)
                Log.d(TAG, "合并到进行中的请求：$bvid（当前 ${waiters.size} 个等待者）")
                return
            }
            // 3) 冷却期内不联网（缓存没命中说明上次是失败/空结果，没必要立刻重试）
            if (!throttle.canRequest(bvid)) {
                onResult(null, "请求冷却中（${bvid}），跳过本次查询")
                return
            }
            pending[bvid] = mutableListOf(onResult)
        }

        executor.execute {
            // 全局最小间隔：把突发请求摊开，避免给社区服务器造成尖峰
            val wait = throttle.delayBeforeRequestMs()
            if (wait > 0L) runCatching { Thread.sleep(wait) }
            throttle.recordRequest(bvid)

            var result: VideoSegments? = null
            var error: String? = null
            val servers = buildList {
                add(serverAddress.trimEnd('/'))
                MIRRORS.forEach { if (it != serverAddress.trimEnd('/')) add(it) }
            }
            for (server in servers) {
                val attempt = runCatching { fetch(server, bvid) }
                if (attempt.isSuccess) {
                    result = attempt.getOrNull()
                    error = null
                    break
                }
                error = attempt.exceptionOrNull()?.message ?: "未知错误"
            }
            val fetched = result
            if (fetched != null) {
                cache[bvid] = CacheEntry(fetched, System.currentTimeMillis())
            }
            val waiters = synchronized(pending) { pending.remove(bvid) } ?: return@execute
            Log.d(
                TAG,
                "网络请求完成：$bvid 片段=${result?.segments?.size ?: -1}" +
                    "（累计联网 ${throttle.networkRequestCount} 次，合并等待者 ${waiters.size}）",
            )
            waiters.forEach { callback ->
                runCatching { callback(result, if (result == null) error else null) }
            }
        }
    }

    /** 同步拉取（`onResult` 之外供单测使用）。 */
    fun fetch(serverAddress: String, bvid: String): VideoSegments {
        val url = "${serverAddress.trimEnd('/')}/api/skipSegments/${hashPrefix(bvid)}"
        val body = httpGet(url)
        return parse(bvid, body)
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            // 400 = 前缀非法；404 在部分镜像上表示空桶 —— 都当作「没有数据」
            if (code == 400 || code == 404) return "[]"
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 解析按前缀分桶的裸数组响应，挑出 [bvid] 对应的那一条。
     *
     * 同时做一次容错清洗：丢掉非区间（`full`）、零长/逆序、越界的片段。
     */
    internal fun parse(bvid: String, body: String): VideoSegments {
        val array = runCatching { JSONArray(body) }.getOrElse { return VideoSegments(bvid, 0L, emptyList()) }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("videoID") != bvid) continue
            val segments = parseSegments(item.optJSONArray("segments"))
            val durationSec = segments.maxOfOrNull { it.videoDurationMs } ?: 0L
            return VideoSegments(bvid, durationSec, segments)
        }
        return VideoSegments(bvid, 0L, emptyList())
    }

    private fun parseSegments(array: JSONArray?): List<SponsorSegment> {
        if (array == null) return emptyList()
        val out = ArrayList<SponsorSegment>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val range = json.optJSONArray("segment") ?: continue
            if (range.length() < 2) continue
            val startMs = (range.optDouble(0, 0.0) * 1000.0).toLong()
            val endMs = (range.optDouble(1, 0.0) * 1000.0).toLong()
            out += SponsorSegment(
                uuid = json.optString("UUID"),
                category = json.optString("category"),
                actionType = json.optString("actionType", ActionType.SKIP),
                cid = json.optString("cid"),
                startMs = startMs,
                endMs = endMs,
                videoDurationMs = (json.optDouble("videoDuration", 0.0) * 1000.0).toLong(),
                locked = json.optInt("locked", 0) != 0,
            )
        }
        // 发布时间序排序，跳过决策按顺序找第一个命中
        return out.sortedBy { it.startMs }
    }

    /** 清空缓存与节流状态（设置页「刷新片段」用）。 */
    fun clearCache() {
        cache.clear()
        throttle.reset()
        synchronized(pending) { pending.clear() }
    }

    fun shutdown() {
        runCatching { executor.shutdownNow() }
            .also { runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) } }
    }

    /** 便于日志输出服务端健康状态：`GET /api/ready`。 */
    fun ping(serverAddress: String): Boolean = runCatching {
        val conn = (URL("${serverAddress.trimEnd('/')}/api/ready").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.responseCode == 200
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)

    /** 供设置页「测试连接」展示的更详细结果。 */
    fun pingDetailed(serverAddress: String): String = runCatching {
        if (ping(serverAddress)) "连接正常" else "连接失败"
    }.getOrElse { "连接失败：${it.message}" }

    @Suppress("unused")
    private fun unusedJson(json: JSONObject) = json
}
