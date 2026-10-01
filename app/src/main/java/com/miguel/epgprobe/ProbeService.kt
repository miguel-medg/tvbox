package com.miguel.epgprobe

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio de prueba: cada vez que cambia la pantalla de otra app,
 * lee todos los textos accesibles y los muestra en un recuadro y en un log.
 * Sirve para saber si Xuper TV expone el nombre del canal como texto.
 */
class ProbeService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var lastSignature = ""

    private val scanRunnable = Runnable { scanScreen() }
    private val hideRunnable = Runnable { overlay?.visibility = View.GONE }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        // Espera un poco para agrupar cambios seguidos
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 400)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        super.onDestroy()
    }

    private fun scanScreen() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: "?"
        if (pkg == packageName) return

        val items = LinkedHashSet<String>()
        collect(root, items, 0)

        val body = if (items.isEmpty()) {
            "(sin texto accesible: probablemente video o canvas)"
        } else {
            items.take(15).joinToString("\n")
        }

        val signature = pkg + "|" + body
        if (signature == lastSignature) return
        lastSignature = signature

        val text = "[$pkg]\n$body"
        showOverlay(text)
        appendLog(text)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 25 || out.size >= 60) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        val id = node.viewIdResourceName?.substringAfter(":id/") ?: ""
        val value = when {
            !t.isNullOrEmpty() -> t
            !d.isNullOrEmpty() -> "(desc) $d"
            else -> null
        }
        if (value != null) out.add(if (id.isNotEmpty()) "$id: $value" else value)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun showOverlay(text: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (overlay == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
                textSize = 14f
                maxWidth = 800
                maxLines = 18
                setPadding(24, 16, 24, 16)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 40
            }
            wm.addView(tv, lp)
            overlay = tv
        }
        overlay?.text = text
        overlay?.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 8000)
    }

    private fun appendLog(text: String) {
        try {
            val file = File(getExternalFilesDir(null), "probe_log.txt")
            if (file.exists() && file.length() > 200_000) file.writeText("")
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("=== $stamp ===\n$text\n\n")
        } catch (_: Exception) {
        }
    }
}
