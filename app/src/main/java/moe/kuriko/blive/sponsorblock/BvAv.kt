package moe.kuriko.blive.sponsorblock

/**
 * BV 号 / AV 号互转。
 *
 * 算法移植自 `third/BilibiliSponsorBlock/src/utils/bvidAvidUtils.ts`
 * （原始来源：SocialSisterYi/bilibili-API-collect）。
 *
 * 为什么要它：SponsorBlock（小电视空降助手）的服务端索引键是 **BV 号**，
 * 而宿主播放器在播放链路里给到的是 **avid**（见 `Video.PlayableParams` /
 * `IResolveParams.getId()` 的 `"avid+cid"`），所以必须本地换算，避免多打一次网络请求。
 */
object BvAv {

    private const val XOR_CODE = 23442827791579L
    private const val MASK_CODE = 2251799813685247L
    private const val MAX_AVID = 1L shl 51
    private const val BASE = 58L
    private const val ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    /**
     * avid → bvid。
     *
     * 与官方实现一致：低 51 位参与编码，`(MAX_AVID or avid) xor XOR_CODE` 转 58 进制后
     * 回填到 `BV1` + 9 位里，最后按官方规则交换第 3/9 与第 4/7 位。
     *
     * @return 合法 bvid；`avid <= 0` 或超出 51 位可表示范围时返回空串。
     */
    fun aidToBvid(avid: Long): String {
        if (avid <= 0L) return ""
        if (avid >= MAX_AVID) return ""
        val chars = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
        var index = chars.size - 1
        var tmp = (MAX_AVID or avid) xor XOR_CODE
        while (tmp > 0L && index >= 3) {
            chars[index] = ALPHABET[(tmp % BASE).toInt()]
            tmp /= BASE
            index--
        }
        chars.swap(3, 9)
        chars.swap(4, 7)
        return String(chars)
    }

    /**
     * bvid → avid。用于把宿主给的 BV 号（若某条链路直接拿到 BV）统一成 avid 比较。
     */
    fun bvidToAid(bvid: String): Long {
        val arr = bvid.toCharArray()
        if (arr.size != 12) return -1L
        arr.swap(3, 9)
        arr.swap(4, 7)
        var value = 0L
        for (i in 3 until arr.size) {
            val digit = ALPHABET.indexOf(arr[i])
            if (digit < 0) return -1L
            value = value * BASE + digit
        }
        return (value and MASK_CODE) xor XOR_CODE
    }

    private fun CharArray.swap(left: Int, right: Int) {
        val tmp = this[left]
        this[left] = this[right]
        this[right] = tmp
    }
}
