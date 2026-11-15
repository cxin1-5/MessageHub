package com.example.messagehub

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MessageNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("collect", false)) return
        val platform = when (sbn.packageName) {
            "com.tencent.mm" -> "微信"
            "com.tencent.mobileqq" -> "QQ"
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            else -> return
        }
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val e = n.extras
        val sender = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank() || sender.isBlank()) return
        // Notification metadata varies by app/version; @ detection is conservative.
        val mention = text.contains("@我") || text.contains("mentioned you", true)
        MessageStore(this).add(platform, sender, text, sbn.postTime, "通知", mention)
    }
}
