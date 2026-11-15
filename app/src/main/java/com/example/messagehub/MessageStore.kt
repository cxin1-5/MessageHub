package com.example.messagehub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class MessageRow(val id: Long, val platform: String, val sender: String, val body: String, val category: String, val mention: String, val timestamp: Long, val origin: String)

class MessageStore(ctx: Context) : SQLiteOpenHelper(ctx, "messages.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, category TEXT NOT NULL, mention TEXT NOT NULL, timestamp INTEGER NOT NULL, origin TEXT NOT NULL, fingerprint TEXT UNIQUE NOT NULL)")
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createV2Tables(db)
        }
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contact_accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                platform TEXT NOT NULL,
                account_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                UNIQUE(platform, account_key),
                FOREIGN KEY(contact_id) REFERENCES contacts(id)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                platform TEXT NOT NULL,
                conversation_key TEXT NOT NULL,
                title TEXT NOT NULL,
                type TEXT NOT NULL
                    CHECK(type IN ('PERSON','GROUP','UNKNOWN')),
                contact_id INTEGER,
                UNIQUE(platform, conversation_key),
                FOREIGN KEY(contact_id) REFERENCES contacts(id)
            )
        """.trimIndent())

        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                columns.add(c.getString(nameIndex))
            }
        }

        if (!columns.contains("conversation_id")) {
            db.execSQL("ALTER TABLE messages ADD COLUMN conversation_id INTEGER")
        }

        if (!columns.contains("direction")) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN direction TEXT NOT NULL DEFAULT 'UNKNOWN'"
            )
        }

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, timestamp)"
        )
    }

    fun deleteMessage(id: Long): Boolean {
        return writableDatabase.delete(
            "messages",
            "id=?",
            arrayOf(id.toString())
        ) > 0
    }

    fun add(platform: String, sender: String, body: String, timestamp: Long, origin: String, explicitMention: Boolean = false): Boolean {
        if (body.isBlank()) return false
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("$platform\u0000$sender\u0000$body\u0000$timestamp\u0000$origin".toByteArray()).joinToString("") { "%02x".format(it) }
        val v = ContentValues().apply {
            put("platform", platform); put("sender", sender); put("body", body.take(10000))
            put("category", Classifier.category(body)); put("mention", Classifier.mention(body, explicitMention))
            put("timestamp", timestamp); put("origin", origin); put("fingerprint", fingerprint)
        }
        return writableDatabase.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun list(limit: Int = 300): List<MessageRow> {
        val out = mutableListOf<MessageRow>()
        readableDatabase.query("messages", arrayOf("id","platform","sender","body","category","mention","timestamp","origin"), null, null, null, null, "timestamp DESC, id DESC", limit.toString()).use { c ->
            while (c.moveToNext()) out.add(MessageRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getString(7)))
        }
        return out
    }
    fun counts(): Map<String, Int> {
        val map = linkedMapOf<String,Int>()
        readableDatabase.rawQuery("SELECT platform,COUNT(*) FROM messages GROUP BY platform", null).use { c -> while(c.moveToNext()) map[c.getString(0)] = c.getInt(1) }
        return map
    }
    fun clearAll() { writableDatabase.delete("messages", null, null) }
}
object Classifier {
    fun category(s: String): String {
        val t = s.lowercase()
        return when {
            listOf("urgent","asap","紧急","立即","马上").any { t.contains(it) } -> "紧急"
            listOf("please confirm","请确认","请回复","reply","反馈","待确认").any { t.contains(it) } -> "待回复"
            listOf("drawing","cable","motor","site","vendor","设备","图纸","施工","电缆","调试").any { t.contains(it) } -> "工程工作"
            else -> "普通"
        }
    }
    fun mention(s: String, explicit: Boolean): String = when {
        explicit -> "确定@我"
        Regex("(^|\\s)@[^\\s]{1,30}").containsMatchIn(s) || s.contains("提到了你") || s.contains("mentioned you", true) -> "疑似@我"
        else -> "无"
    }
}
