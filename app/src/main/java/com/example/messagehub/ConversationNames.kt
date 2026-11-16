package com.example.messagehub

import java.util.Locale

/** Normalize presentation-only notification counters, never remove arbitrary name text. */
object ConversationNames {
    private val counters = Regex("[（(]\\s*\\d+\\s*(?:条\\s*)?(?:新消息|未读消息|消息)\\s*[）)]", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")
    fun clean(raw: String): String = raw.replace(counters, "").replace(spaces, " ").trim().ifEmpty { "未知会话" }
    fun key(raw: String): String = clean(raw).lowercase(Locale.ROOT)
}
