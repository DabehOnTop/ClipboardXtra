package com.xtra.clipboardx

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat

class ClipboardService : Service() {

    companion object {
        private const val CH = "clipboardx"
        private const val NOTIF_ID = 42
        // Default: 500 lines OR 50,000 chars per chunk, whichever hits first
        var linesPerChunk = 500
        var maxCharsPerChunk = 50_000
    }

    private lateinit var cm: ClipboardManager
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleText: TextView? = null

    private var chunks: List<String> = emptyList()
    private var index = 0
    private var lastClipHash: Int = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cm.addPrimaryClipChangedListener(clipListener)
        startForeground(NOTIF_ID, buildNotification("Waiting for a copy…"))
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cm.removePrimaryClipChangedListener(clipListener)
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------- Clipboard watch ----------

    private fun onClipboardChanged() {
        if (!cm.hasPrimaryClip()) return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isBlank()) return

        val h = text.hashCode()
        // Ignore the changes WE caused when copying a chunk
        if (h == lastClipHash) return

        // Only split if it's big enough to be worth it
        if (text.length < 5_000 && text.count { it == '\n' } < linesPerChunk) {
            // small copy — do nothing, just reset previous run
            chunks = emptyList(); index = 0
            updateBubble("idle")
            return
        }

        splitAndPrime(text)
    }

    private fun splitAndPrime(full: String) {
        val result = mutableListOf<String>()
        val lines = full.split("\n")
        val sb = StringBuilder()
        var lineCount = 0

        for (line in lines) {
            val projected = sb.length + line.length + 1
            if (lineCount >= linesPerChunk || projected > maxCharsPerChunk) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString().trimEnd('\n'))
                    sb.setLength(0); lineCount = 0
                }
                // hard-split a monster single line
                if (line.length > maxCharsPerChunk) {
                    var s = 0
                    while (s < line.length) {
                        val e = minOf(s + maxCharsPerChunk, line.length)
                        result.add(line.substring(s, e)); s = e
                    }
                    continue
                }
            }
            sb.append(line).append('\n'); lineCount++
        }
        if (sb.isNotEmpty()) result.add(sb.toString().trimEnd('\n'))

        chunks = result
        index = 0
        if (chunks.isEmpty()) return

        // Put chunk 1 on the clipboard immediately
        putChunkOnClipboard(0)
        updateBubble("1/${chunks.size}")
        updateNotification("Copied chunk 1/${chunks.size} — paste, then tap the bubble")
    }

    private fun putChunkOnClipboard(i: Int) {
        val chunk = chunks[i]
        lastClipHash = chunk.hashCode()
        cm.setPrimaryClip(ClipData.newPlainText("ClipboardXtra ${i + 1}/${chunks.size}", chunk))
    }

    // ---------- Bubble ----------

    private fun addBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 300
        }

        val v = LayoutInflater.from(this).inflate(R.layout.bubble, null)
        bubbleText = v.findViewById(R.id.bubbleText)

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) handleBubbleTap()
                    else snapToEdge(params, v)
                    true
                }
                else -> false
            }
        }

        wm.addView(v, params)
        bubble = v
        updateBubble("idle")
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, v: View) {
        val screenW = resources.displayMetrics.widthPixels
        params.x = if (params.x + v.width / 2 < screenW / 2) 0 else screenW - v.width
        wm.updateViewLayout(v, params)
    }

    private fun handleBubbleTap() {
        if (chunks.isEmpty()) {
            updateBubble("idle"); return
        }
        if (index >= chunks.size - 1) {
            updateBubble("✓ done")
            updateNotification("All chunks delivered")
            return
        }
        index++
        putChunkOnClipboard(index)
        updateBubble("${index + 1}/${chunks.size}")
        updateNotification("Copied chunk ${index + 1}/${chunks.size}")
    }

    private fun updateBubble(state: String) {
        val t = bubbleText ?: return
        when {
            state == "idle" -> { t.text = "CLP"; t.setBackgroundResource(R.drawable.bubble_bg) }
            state.startsWith("✓") -> { t.text = state; t.setBackgroundResource(R.drawable.bubble_bg) }
            else -> { t.text = state; t.setBackgroundResource(R.drawable.bubble_bg) }
        }
    }

    // ---------- Notification ----------

    private fun buildNotification(msg: String): Notification {
        val chan = "clipx"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chan) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chan, "ClipboardXtra",
                        NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, chan)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("ClipboardXtra")
            .setContentText(msg)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(msg))
    }
}