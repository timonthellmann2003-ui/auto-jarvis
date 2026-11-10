package de.autojarvis.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.autojarvis.Ui

/**
 * Kleine schwebende Leiste über allen Apps (auch über Android Auto).
 * Zeigt "90 °C · 14,1 V · 6,4 L/100 · Ø 7,1", wird bei Warnungen rot und bietet "Vorlesen", wenn Fehler warten.
 * Lässt sich mit dem Finger verschieben; die Position wird gemerkt.
 */
class Leiste(
    private val c: Context,
    private val onVorlesen: () -> Unit,
    private val onSchliessen: () -> Unit,
) {
    companion object {
        fun erlaubt(c: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)
    }

    private val ui = Ui(c)
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = c.getSharedPreferences("auto_jarvis", Context.MODE_PRIVATE)

    private val hintergrund = GradientDrawable().apply {
        setColor(Color.argb(220, 12, 14, 18))
        cornerRadius = ui.dp(20).toFloat()
    }

    private val werte = TextView(c).apply {
        textSize = 18f
        setTextColor(Ui.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        text = "Jarvis …"
    }

    private val vorlesen = TextView(c).apply {
        textSize = 18f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            setColor(Ui.WARNUNG)
            cornerRadius = ui.dp(14).toFloat()
        }
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
        visibility = View.GONE
        setOnClickListener { onVorlesen() }
    }

    private val schliessen = TextView(c).apply {
        text = "✕"
        textSize = 18f
        setTextColor(Ui.GEDAEMPFT)
        setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6))
        visibility = View.GONE
        setOnClickListener { onSchliessen() }
    }

    private val ansicht = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = hintergrund
        setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        addView(werte)
        addView(vorlesen, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ui.dp(12) })
        addView(schliessen)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("leiste_x", ui.dp(8))
        y = prefs.getInt("leiste_y", ui.dp(8))
    }

    init {
        verschiebbar()
        wm.addView(ansicht, params)
    }

    fun zeige(f: Fahrzeug, offeneFehler: Int) {
        val teile = mutableListOf<String>()
        teile += f.kuehlwasserC?.let { "$it °C" } ?: "– °C"
        teile += f.spannungText()?.let { "$it V" } ?: "– V"
        teile += f.verbrauchText() ?: "– L/100"
        f.schnittFahrt?.let { teile += "Ø ${Fahrzeug.komma(it)}" }
        werte.text = teile.joinToString("  ·  ")

        val warnung = (f.kuehlwasserC ?: 0) >= JarvisService.MOTOR_HEISS || JarvisService.spannungWarnung(f)
        hintergrund.setColor(if (warnung) Color.argb(230, 180, 30, 20) else Color.argb(220, 12, 14, 18))

        if (offeneFehler > 0) {
            vorlesen.text = if (offeneFehler == 1) "1 Fehler · Vorlesen" else "$offeneFehler Fehler · Vorlesen"
            vorlesen.visibility = View.VISIBLE
            schliessen.visibility = View.VISIBLE
        } else {
            vorlesen.visibility = View.GONE
            schliessen.visibility = View.GONE
        }
    }

    fun zeigeStatus(text: String) {
        if (!text.startsWith("Verbunden")) werte.text = "Jarvis: $text"
    }

    fun entferne() {
        try { wm.removeView(ansicht) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun verschiebbar() {
        var startX = 0
        var startY = 0
        var fingerX = 0f
        var fingerY = 0f
        werte.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    fingerX = e.rawX; fingerY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - fingerX).toInt()
                    params.y = startY + (e.rawY - fingerY).toInt()
                    wm.updateViewLayout(ansicht, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("leiste_x", params.x).putInt("leiste_y", params.y).apply()
                    true
                }
                else -> false
            }
        }
    }
}
