package com.example.messagehub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.BufferedReader

class MainActivity : Activity() {
    private lateinit var store: MessageStore
    private lateinit var root: LinearLayout
    private val importCode = 101
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store = MessageStore(this); showHome() }
    private fun line(text: String, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = if (bold) 19f else 15f; setPadding(6, 12, 6, 12)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }
    private fun showHome() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 12) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(line("消息汇总助手 · MessageHub", true))
        root.addView(line("仅在明确开启后收集通知；所有数据默认保存在手机本地。"))
        val collect = Switch(this).apply { text = "允许本地收集新通知"; isChecked = prefs.getBoolean("collect", false) }
        collect.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("collect", checked).apply() }
        root.addView(collect)
        button("打开 Android 通知访问设置") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        val cloud = Switch(this).apply { text = "允许未来使用云端 AI（当前不上传）"; isChecked = prefs.getBoolean("cloud_opt_in", false) }
        cloud.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("cloud_opt_in", checked).apply() }
        root.addView(cloud)
        root.addView(line("云端分析尚未接入。开启此项只保存你的偏好，不发送任何消息。"))
        button("导入 WhatsApp / 通用 TXT 历史记录") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE) }, importCode)
        }
        button("刷新统计") { showHome() }
        button("删除全部本地消息") {
            android.app.AlertDialog.Builder(this).setTitle("确定删除全部记录？").setMessage("此操作无法撤销，不会影响微信、QQ、WhatsApp 原始聊天。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> store.clearAll(); showHome() }.show()
        }
        root.addView(line("按平台统计", true))
        val counts = store.counts()
        root.addView(line(if (counts.isEmpty()) "暂无记录" else counts.entries.joinToString("  |  ") { "${it.key}: ${it.value}" }))
        val rows = store.list()
        root.addView(line("最近消息 (${rows.size} 条，最多显示 300 条)", true))
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        rows.forEach { m ->
            root.addView(line("${fmt.format(Date(m.timestamp))}  ${m.platform} · ${m.sender}\n${m.category}  ${m.mention}  [${m.origin}]\n${m.body.take(280)}"))
            root.addView(View(this).apply { setBackgroundColor(0xFFDDDDDD.toInt()); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT_WIDTH, 1) })
        }
    }
    @Deprecated("Uses document picker result for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importCode || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        Thread {
            var count = 0
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val regex = Regex("^\\[?(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}),?\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[APap][Mm])?)\\]?\\s*[-–]?\\s*([^:]+):\\s*(.*)$")
                    var index = 0L
                    reader.forEachLine { raw ->
                        if (index++ >= 100000) return@forEachLine
                        val s = raw.trimStart('\u200e', '\u200f', ' ')
                        val match = regex.matchEntire(s)
                        val sender = match?.groupValues?.get(3)?.trim() ?: "历史记录"
                        val body = match?.groupValues?.get(4) ?: s
                        if (body.isNotBlank()) {
                            // Preserve import order; dates in different locales require a future dedicated parser.
                            val stamp = System.currentTimeMillis() + index
                            if (store.add("历史导入", sender, body, stamp, "TXT导入")) count++
                        }
                    }
                }
                runOnUiThread { Toast.makeText(this, "导入 $count 条文本记录（日期尚未标准化）", Toast.LENGTH_LONG).show(); showHome() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
