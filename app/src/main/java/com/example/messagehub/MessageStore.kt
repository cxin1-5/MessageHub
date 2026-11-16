package com.example.messagehub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class MessageRow(val id: Long, val platform: String, val sender: String, val body: String, val category: String, val mention: String, val timestamp: Long, val origin: String)

class MessageStore(ctx: Context) : SQLiteOpenHelper(ctx, "messages.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, category TEXT NOT NULL, mention TEXT NOT NULL, timestamp INTEGER NOT NULL, origin TEXT NOT NULL, fingerprint TEXT UNIQUE NOT NULL)")
        createV2Tables(db)
        createV3Tables(db)
        createV4Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Tables(db)
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


    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS v3_threads (id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, thread_key TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, UNIQUE(platform,thread_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS v3_links (platform TEXT NOT NULL, account_key TEXT NOT NULL, contact_id INTEGER NOT NULL, PRIMARY KEY(platform,account_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS v3_flags (message_id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL, direction TEXT NOT NULL DEFAULT 'IN', mention_confirmed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_flags_thread ON v3_flags(thread_id)")
    }
    private fun createV4Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v4_threads_kind ON v3_threads(platform,kind,thread_key)")
        reconcileThreads(db)
    }

    /** Preserve all messages while collapsing only exact normalized names within the same platform and kind. */
    private fun reconcileThreads(db: SQLiteDatabase) {
        data class T(val id: Long, val platform: String, val kind: String, val key: String, val title: String)
        val threads = mutableListOf<T>()
        db.rawQuery("SELECT id,platform,kind,thread_key,title FROM v3_threads ORDER BY id", null).use { c ->
            while (c.moveToNext()) threads.add(T(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)))
        }
        val canonical = mutableMapOf<String, Long>()
        for (t in threads) {
            val clean = ConversationNames.clean(t.title)
            val normalized = ConversationNames.key(clean)
            val scope = t.platform + "\u0000" + t.kind + "\u0000" + normalized
            val target = canonical[scope]
            if (target == null) {
                canonical[scope] = t.id
                db.execSQL("UPDATE v3_threads SET title=? WHERE id=?", arrayOf(clean,t.id))
            } else {
                db.execSQL("UPDATE v3_flags SET thread_id=? WHERE thread_id=?", arrayOf(target,t.id))
                db.execSQL("UPDATE messages SET conversation_id=? WHERE conversation_id=?", arrayOf(target,t.id))
                db.execSQL("DELETE FROM v3_threads WHERE id=?", arrayOf(t.id))
            }
        }
        // Existing legacy keys are retained as aliases; incoming records resolve by normalized title.
    }

    /** Can be run repeatedly, including on databases already upgraded to V4. */
    fun normalizeExistingFolders() {
        val db = writableDatabase
        db.beginTransaction()
        try { reconcileThreads(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    data class Folder(val id: Long, val title: String, val platform: String, val kind: String, val count: Int)
    fun folders(kind: String): List<Folder> {
        val out = mutableListOf<Folder>()
        readableDatabase.rawQuery("SELECT t.id,t.title,t.platform,t.kind,COUNT(f.message_id) FROM v3_threads t LEFT JOIN v3_flags f ON f.thread_id=t.id WHERE t.kind=? GROUP BY t.id ORDER BY t.title", arrayOf(kind)).use { c ->
            while(c.moveToNext()) out.add(Folder(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)))
        }
        return out
    }
    fun threadMessages(id: Long): List<MessageRow> {
        val out=mutableListOf<MessageRow>()
        readableDatabase.rawQuery("SELECT m.id,m.platform,m.sender,m.body,m.category,m.mention,m.timestamp,m.origin FROM messages m JOIN v3_flags f ON f.message_id=m.id WHERE f.thread_id=? ORDER BY m.timestamp DESC,m.id DESC LIMIT 1000",arrayOf(id.toString())).use { c ->
            while(c.moveToNext()) out.add(MessageRow(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7)))
        }
        return out
    }
    fun mentions(): List<MessageRow> {
        val out=mutableListOf<MessageRow>()
        readableDatabase.rawQuery("SELECT m.id,m.platform,m.sender,m.body,m.category,m.mention,m.timestamp,m.origin FROM messages m JOIN v3_flags f ON f.message_id=m.id JOIN v3_threads t ON t.id=f.thread_id WHERE t.kind='GROUP' AND f.mention_confirmed=1 ORDER BY m.timestamp DESC LIMIT 500",null).use { c ->
            while(c.moveToNext()) out.add(MessageRow(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7)))
        }
        return out
    }
    fun addV3(platform:String, sender:String, body:String, timestamp:Long, origin:String, threadTitle:String=sender, kind:String="PERSON", outgoing:Boolean=false, explicitMention:Boolean=false):Boolean {
        if(body.isBlank()) return false
        val db=writableDatabase
        val normalizedKey=ConversationNames.key(threadTitle)
        val safeKind=if(kind=="GROUP") "GROUP" else if(kind=="PERSON") "PERSON" else "UNKNOWN"
        val key=safeKind + ":" + normalizedKey
        db.beginTransaction()
        try {
            val tv=ContentValues().apply { put("platform",platform);put("thread_key",key);put("title",ConversationNames.clean(threadTitle));put("kind",safeKind) }
            var existingId = 0L
            db.rawQuery("SELECT id,title FROM v3_threads WHERE platform=? AND kind=?", arrayOf(platform,safeKind)).use { c ->
                while (c.moveToNext()) if (ConversationNames.key(c.getString(1)) == normalizedKey) { existingId=c.getLong(0); break }
            }
            if (existingId == 0L) db.insertWithOnConflict("v3_threads",null,tv,SQLiteDatabase.CONFLICT_IGNORE)
            var tid=existingId
            if (tid == 0L) db.rawQuery("SELECT id FROM v3_threads WHERE platform=? AND thread_key=?",arrayOf(platform,key)).use { c -> if(c.moveToFirst()) tid=c.getLong(0) }
            val fingerprint=MessageDigest.getInstance("SHA-256").digest("$platform\u0000$sender\u0000$body\u0000$timestamp\u0000$origin".toByteArray()).joinToString(""){ "%02x".format(it) }
            val mv=ContentValues().apply {put("platform",platform);put("sender",sender);put("body",body.take(10000));put("category",Classifier.category(body));put("mention",Classifier.mention(body,explicitMention));put("timestamp",timestamp);put("origin",origin);put("fingerprint",fingerprint);put("conversation_id",tid);put("direction",if(outgoing) "OUT" else "IN")}
            var mid=db.insertWithOnConflict("messages",null,mv,SQLiteDatabase.CONFLICT_IGNORE)
            val inserted=mid!=-1L
            if(!inserted) db.rawQuery("SELECT id FROM messages WHERE fingerprint=?",arrayOf(fingerprint)).use { c -> if(c.moveToFirst()) mid=c.getLong(0) }
            if(mid>0) {
                val fv=ContentValues().apply {put("message_id",mid);put("thread_id",tid);put("direction",if(outgoing) "OUT" else "IN");put("mention_confirmed",if(explicitMention && safeKind=="GROUP") 1 else 0)}
                db.insertWithOnConflict("v3_flags",null,fv,SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {db.endTransaction()}
    }
    fun deleteOne(id:Long):Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {db.delete("v3_flags","message_id=?",arrayOf(id.toString())); val ok=db.delete("messages","id=?",arrayOf(id.toString()))>0;db.setTransactionSuccessful();return ok} finally {db.endTransaction()}
    }
    fun mergeAccounts(name:String, accounts:List<Pair<String,String>>):Boolean {
        if(name.isBlank() || accounts.isEmpty()) return false
        val db=writableDatabase
        db.beginTransaction()
        try {
            val cv=ContentValues().apply {put("display_name",name.trim());put("created_at",System.currentTimeMillis())}
            val id=db.insert("contacts",null,cv)
            if(id<0) return false
            for((platform,key) in accounts) {
                if(key.isBlank()) continue
                val a=ContentValues().apply {put("platform",platform);put("account_key",ConversationNames.key(key));put("contact_id",id)}
                db.insertWithOnConflict("v3_links",null,a,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun unifiedContacts():List<Pair<String,List<Folder>>> {
        val result=mutableListOf<Pair<String,List<Folder>>>()
        readableDatabase.rawQuery("SELECT id,display_name FROM contacts ORDER BY display_name",null).use { c ->
            while(c.moveToNext()) {
                val id=c.getLong(0);val name=c.getString(1)
                val items=mutableListOf<Folder>()
                readableDatabase.rawQuery("SELECT t.id,t.title,t.platform,t.kind,(SELECT COUNT(*) FROM v3_flags f WHERE f.thread_id=t.id) FROM v3_threads t JOIN v3_links l ON l.platform=t.platform AND l.account_key=t.thread_key WHERE l.contact_id=?",arrayOf(id.toString())).use { x -> while(x.moveToNext()) items.add(Folder(x.getLong(0),x.getString(1),x.getString(2),x.getString(3),x.getInt(4))) }
                result.add(name to items)
            }
        }
        return result
    }
    fun migrateOldMessages() {
        val old=list(100000)
        val db=writableDatabase
        for(m in old) {
            var exists=false
            db.rawQuery("SELECT 1 FROM v3_flags WHERE message_id=?",arrayOf(m.id.toString())).use { c -> exists=c.moveToFirst() }
            if(exists) continue
            val key="UNKNOWN:" + ConversationNames.key(m.sender)
            val tv=ContentValues().apply {put("platform",m.platform);put("thread_key",key);put("title",ConversationNames.clean(m.sender));put("kind","UNKNOWN")}
            db.insertWithOnConflict("v3_threads",null,tv,SQLiteDatabase.CONFLICT_IGNORE)
            db.rawQuery("SELECT id FROM v3_threads WHERE platform=? AND thread_key=?",arrayOf(m.platform,key)).use { c -> if(c.moveToFirst()) {
                val fv=ContentValues().apply {put("message_id",m.id);put("thread_id",c.getLong(0));put("direction","UNKNOWN");put("mention_confirmed",0)}
                db.insertWithOnConflict("v3_flags",null,fv,SQLiteDatabase.CONFLICT_IGNORE)
            }}
        }
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
    fun clearAll() { val db=writableDatabase; db.delete("v3_flags",null,null);db.delete("messages",null,null) }
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
