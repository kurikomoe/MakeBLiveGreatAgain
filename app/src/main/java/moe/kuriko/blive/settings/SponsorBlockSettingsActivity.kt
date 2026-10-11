package moe.kuriko.blive.settings

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import moe.kuriko.blive.sponsorblock.SkipOption
import moe.kuriko.blive.sponsorblock.SponsorBlockApi
import moe.kuriko.blive.sponsorblock.SponsorBlockPrefs
import moe.kuriko.blive.sponsorblock.SponsorCategories

/**
 * 「空降助手」设置页 —— 用纯代码搭 UI（宿主模块不需要复杂资源，也避免资源 id 与宿主冲突）。
 *
 * 覆盖上游扩展里与「跳过」相关的设置：
 *  - 总开关、数据源地址；
 *  - 每个分类的跳过方式（禁用 / 仅提示 / 手动跳过 / 自动跳过）；
 *  - 静音片段、最小片段时长、跳过提示、进度条标记、精彩时刻自动跳；
 *  - 片段缓存刷新与连通性测试。
 *
 * 改动立即写回 SharedPreferences，宿主进程在下一个 tick 读到新值。
 */
class SponsorBlockSettingsActivity : Activity() {

    private lateinit var snapshot: SponsorBlockPrefs.Snapshot

    private var enableSwitch: Switch? = null
    private var hostStatusView: TextView? = null
    private var serverInput: EditText? = null
    private var minDurationInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = SponsorBlockPrefs.load(this)
        title = "空降助手设置"
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        refreshHostStatus()
        // 打开设置页时也推一次：宿主进程若在后台活着，就能立刻拿到最新配置
        SettingsBroadcastSender.pushSettings(this, "设置页打开")
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    /** 显示「宿主最近一次成功读取配置」的时间与来源包名。 */
    private fun refreshHostStatus() {
        val status = SponsorBlockPrefs.hostReadStatus(this)
        val text = if (status == null) {
            "尚未被宿主读取过"
        } else {
            val (at, by) = status
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(at))
            "宿主已读取：$time（来自 $by）"
        }
        hostStatusView?.text = text
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(sectionTitle("宿主连接状态"))
        hostStatusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(hostStatusView)
        root.addView(
            hint(
                "「宿主已读取」表示 B 站进程成功拿到了这里的设置；若一直显示「尚未读取」，" +
                    "说明模块没生效（LSPosed 未勾选作用域 / 宿主进程没冷启动）。",
            ),
        )

        root.addView(sectionTitle("基本"))
        enableSwitch = switchRow("启用跳过", snapshot.enabled) { snapshot = snapshot.copy(enabled = it) }
        root.addView(enableSwitch)
        root.addView(hint("关闭后模块完全不介入播放，宿主行为与未安装时一致。"))

        root.addView(sectionTitle("数据源"))
        serverInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(snapshot.server)
            hint = SponsorBlockApi.DEFAULT_SERVER
        }
        root.addView(serverInput!!)
        val testButton = Button(this).apply {
            text = "测试连接"
            setOnClickListener {
                val server = serverInput?.text?.toString().orEmpty().ifBlank { SponsorBlockApi.DEFAULT_SERVER }
                Toast.makeText(this@SponsorBlockSettingsActivity, "测试中…", Toast.LENGTH_SHORT).show()
                Thread {
                    val result = SponsorBlockApi.pingDetailed(server)
                    runOnUiThread {
                        Toast.makeText(this@SponsorBlockSettingsActivity, result, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        root.addView(testButton)
        root.addView(hint("默认 ${SponsorBlockApi.DEFAULT_SERVER}（小电视空降助手）。主站不可用时会自动尝试镜像。"))

        root.addView(sectionTitle("分类跳过方式"))
        SponsorCategories.all.forEach { category ->
            root.addView(categoryRow(category.id, category.label, category.colorHex))
        }
        root.addView(hint("「自动跳过」= 进入片段立即跳到片段结束；「手动跳过」「仅提示」只弹提示不跳转。"))

        root.addView(sectionTitle("行为"))
        root.addView(switchRow("静音片段静音播放", snapshot.muteSegments) { snapshot = snapshot.copy(muteSegments = it) })
        root.addView(switchRow("显示跳过提示", snapshot.showToast) { snapshot = snapshot.copy(showToast = it) })
        root.addView(switchRow("精彩时刻自动跳", snapshot.poiAutoSkip) { snapshot = snapshot.copy(poiAutoSkip = it) })
        root.addView(switchRow("在进度条标记片段", snapshot.progressMarker) { snapshot = snapshot.copy(progressMarker = it) })

        root.addView(sectionTitle("过滤"))
        minDurationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((snapshot.minDurationMs / 1000L).toString())
        }
        root.addView(label("最小片段时长（秒，0 = 不过滤）"))
        root.addView(minDurationInput!!)

        val clearCache = Button(this).apply {
            text = "清空片段缓存"
            setOnClickListener {
                SponsorBlockApi.clearCache()
                Toast.makeText(this@SponsorBlockSettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearCache)

        val saveButton = Button(this).apply {
            text = "保存"
            setOnClickListener {
                persist()
                Toast.makeText(this@SponsorBlockSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(saveButton)

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.GRAY)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun categoryRow(categoryId: String, label: String, colorHex: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = "$label（$colorHex）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val options = SkipOption.values().toList()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SponsorBlockSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.label },
            )
            setSelection(options.indexOf(snapshot.optionOf(categoryId)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val option = options[position]
                    snapshot = snapshot.copy(options = snapshot.options + (categoryId to option))
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner)
        return row
    }

    // ------------------------------------------------------------------ 持久化

    private fun persist() {
        val minSeconds = minDurationInput?.text?.toString()?.trim()?.toLongOrNull() ?: 0L
        snapshot = snapshot.copy(
            server = serverInput?.text?.toString()?.trim().orEmpty().ifBlank { SponsorBlockApi.DEFAULT_SERVER },
            minDurationMs = (minSeconds * 1000L).coerceAtLeast(0L),
        )
        SponsorBlockPrefs.save(this, snapshot)
        // 广播给宿主进程（它会把配置落到自己的私有目录，之后启动即可本地读取）
        SettingsBroadcastSender.pushSettings(this, "设置页保存")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
