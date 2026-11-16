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
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store = MessageStore(this); store.migrateOldMessages(); showHome() }
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
        root.addView(line("V3：旧消息先放入待分类；通知无法保证识别所有群聊、@我或自己发送的内容。"))
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
        button("联系人文件夹") { showFolders("PERSON") }
        button("群聊文件夹") { showFolders("GROUP") }
        button("待分类旧消息") { showFolders("UNKNOWN") }
        button("群聊 @ 我的消息") { showMessages("群聊 @ 我",store.mentions()) }
        button("统一联系人 / 手动合并") { showContacts() }
        button("补录我发送的消息") { showCompose() }
        root.addView(line("按平台统计", true))
        val counts = store.counts()
        root.addView(line(if (counts.isEmpty()) "暂无记录" else counts.entries.joinToString("  |  ") { "${it.key}: ${it.value}" }))
        val rows = store.list()
        root.addView(line("最近消息 (${rows.size} 条，最多显示 300 条)", true))
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        rows.forEach { m ->
            root.addView(line("${fmt.format(Date(m.timestamp))}  ${m.platform} · ${m.sender}\n${m.category}  ${m.mention}  [${m.origin}]\n${m.body.take(280)}"))
            button("删除此消息 #${m.id}") { confirmDelete(m.id) { showHome() } }
                val separator = android.view.View(this)
                separator.setBackgroundColor(0xFFDDDDDD.toInt())
                separator.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
                root.addView(separator)
        }
    }
    private fun newPage(title:String) {
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(20,24,20,12)}
        setContentView(ScrollView(this).apply {addView(root)})
        root.addView(line(title,true))
        button("返回首页") {showHome()}
    }
    private fun confirmDelete(id:Long, back:()->Unit) {
        android.app.AlertDialog.Builder(this).setTitle("删除消息 #$id？").setMessage("仅删除 MessageHub 本地副本，不能撤销。")
            .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->store.deleteOne(id);back()}.show()
    }
    private fun showFolders(kind:String) {
        newPage(when(kind){"GROUP"->"群聊文件夹";"PERSON"->"联系人文件夹";else->"待分类消息"})
        val folders=store.folders(kind)
        if(folders.isEmpty()) root.addView(line("暂无记录。部分通知没有群身份信息，可在导入时手动选择群聊。"))
        folders.forEach { f -> button("📁 ${f.title} · ${f.platform} (${f.count}条)") {showThread(f.id,f.title)} }
    }
    private fun showThread(id:Long,title:String) {showMessages(title,store.threadMessages(id)) {showThread(id,title)} }
    private fun showMessages(title:String,items:List<MessageRow>,refresh:(()->Unit)?=null) {
        newPage(title)
        val fmt=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault())
        if(items.isEmpty()) root.addView(line("暂无消息"))
        items.forEach {m ->
            root.addView(line("${fmt.format(Date(m.timestamp))} · ${m.platform} · ${m.sender} [${m.origin}]\n${m.body}"))
            button("删除 #${m.id}"){confirmDelete(m.id){(refresh ?: {showMessages(title,store.mentions())})()}}
        }
    }
    private fun showContacts() {
        newPage("统一联系人")
        root.addView(line("手动将不同平台的会话名称关联到同一联系人。名称必须与文件夹标题完全一致。"))
        val name=EditText(this).apply {hint="统一联系人名称，例如：张工"};root.addView(name)
        val accounts=mutableListOf<Pair<Spinner,EditText>>()
        repeat(3) {i ->
            val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("微信","QQ","WhatsApp"));spinner.setSelection(i)
            val key=EditText(this).apply {hint="该平台的联系人名称（可留空）"}
            root.addView(spinner);root.addView(key);accounts.add(spinner to key)
        }
        button("保存合并关系") {
            val values=accounts.mapNotNull {(sp,key)->key.text.toString().trim().takeIf{it.isNotEmpty()}?.let{sp.selectedItem.toString() to it}}
            if(store.mergeAccounts(name.text.toString(),values)) {Toast.makeText(this,"合并关系已保存",Toast.LENGTH_SHORT).show();showContacts()}
            else Toast.makeText(this,"请填写联系人名称及至少一个平台名称",Toast.LENGTH_LONG).show()
        }
        store.unifiedContacts().forEach {(title,folders)->
            root.addView(line(title,true))
            folders.forEach {f->button("${f.platform} · ${f.title} (${f.count})") {showThread(f.id,f.title)}}
        }
    }
    private fun showCompose() {
        newPage("补录自己发送的消息")
        val platform=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("微信","QQ","WhatsApp","历史导入"))};root.addView(platform)
        val type=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("PERSON","GROUP"))};root.addView(type)
        val target=EditText(this).apply {hint="联系人或群聊文件夹名称"};root.addView(target)
        val body=EditText(this).apply {hint="我发送的消息内容";minLines=3};root.addView(body)
        button("保存到对应会话") {
            if(target.text.isBlank() || body.text.isBlank()) {Toast.makeText(this,"请填写会话名称和消息",Toast.LENGTH_SHORT).show();return@button}
            store.addV3(platform.selectedItem.toString(),"我",body.text.toString(),System.currentTimeMillis(),"手动补录",target.text.toString(),type.selectedItem.toString(),true)
            showHome()
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
                            if (store.addV3("历史导入", sender, body, stamp, "TXT导入", sender, "UNKNOWN")) count++
                        }
                    }
                }
                runOnUiThread { Toast.makeText(this, "导入 $count 条文本记录（日期尚未标准化）", Toast.LENGTH_LONG).show(); showHome() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
