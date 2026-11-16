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
        val n=sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY)!=0) return
        val e=n.extras
        val title=e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body=(e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if(title.isBlank() || body.isBlank()) return
        // Only use group identity when the notification explicitly supplies a conversation title.
        val conversation=e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val group=conversation.isNotBlank()
        val sender=if(group) title else title
        val thread=if(group) conversation else title
        val explicit=group && (body.contains("@我") || body.contains("mentioned you",true) || body.contains("提到了你"))
        MessageStore(this).addV3(platform,sender,body,sbn.postTime,"通知",thread,if(group) "GROUP" else "PERSON",false,explicit)
    }
}
