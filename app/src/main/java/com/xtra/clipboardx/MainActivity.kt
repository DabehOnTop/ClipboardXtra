package com.xtra.clipboardx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnNotif   = findViewById<Button>(R.id.btnNotif)
        val btnStart   = findViewById<Button>(R.id.btnStart)
        val btnStop    = findViewById<Button>(R.id.btnStop)

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else toast("Overlay already granted")
        }

        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            } else toast("Not needed below Android 13")
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant overlay permission first")
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(
                this, Intent(this, ClipboardService::class.java)
            )
            toast("ClipboardXtra running — copy some code!")
            finish()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ClipboardService::class.java))
            toast("Stopped")
        }
    }

    override fun onResume() {
        super.onResume()
        val ov = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val nt = if (Build.VERSION.SDK_INT < 33) "n/a"
                 else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                          == android.content.pm.PackageManager.PERMISSION_GRANTED) "✓" else "✗"
        status.text = "Overlay: $ov    Notifications: $nt"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}