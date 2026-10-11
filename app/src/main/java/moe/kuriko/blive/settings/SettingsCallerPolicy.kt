package moe.kuriko.blive.settings

/**
 * Provider 调用方准入策略 —— 纯逻辑，可单测。
 *
 * 这段判断有两个方向的风险，都很隐蔽：
 *
 *  - **放太松**：任何 App 都能读到模块设置（虽然只是跳过偏好，但没有理由泄漏）；
 *  - **卡太死**：宿主被误判成非法调用方 ⇒ Provider 返回 null ⇒ 宿主静默用默认配置 ⇒
 *    表现成「设置页改了不生效」。这一条是真实踩过的坑：Android 11+ 的包可见性可能让
 *    `PackageManager.getPackagesForUid()` 拿不到（或只拿到部分）包名，
 *    原来的「三条全满足才放行」会直接把宿主判死。
 *
 * 结论：**以系统给出的 `callingPackage` 为准**（它由调用方 UID 反查而来，调用方伪造不了），
 * 只有它为 null 时才退回按 UID 反查包名；能反查到 uid 包列表时再核对一次一致性。
 */
object SettingsCallerPolicy {

    /**
     * @param callingPackage `ContentProvider.getCallingPackage()`
     * @param uidPackages `PackageManager.getPackagesForUid(getCallingUid())`，拿不到时为 null/空
     * @param allowedPackages 允许的包名集合（模块自己 + 目标宿主）
     */
    fun isAllowed(
        callingPackage: String?,
        uidPackages: Array<String>?,
        allowedPackages: Set<String>,
    ): Boolean {
        if (!callingPackage.isNullOrEmpty()) {
            if (callingPackage !in allowedPackages) return false
            // 反查得到 uid 的包列表时再核对一致性，防止包名冒用
            if (!uidPackages.isNullOrEmpty() && callingPackage !in uidPackages) return false
            return true
        }
        // 拿不到 callingPackage：只能靠 uid 反查
        if (uidPackages.isNullOrEmpty()) return false
        return uidPackages.any { it in allowedPackages }
    }
}
