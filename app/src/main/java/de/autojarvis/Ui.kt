package de.autojarvis

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Kleine Helfer, um Oberflächen ohne XML zu bauen. Dunkles Design fürs Auto. */
class Ui(private val c: Context) {

    companion object {
        val HINTERGRUND = Color.rgb(12, 14, 18)
        val KACHEL = Color.rgb(28, 32, 40)
        val TEXT = Color.rgb(235, 238, 242)
        val GEDAEMPFT = Color.rgb(140, 148, 160)
        val AKZENT = Color.rgb(0, 170, 255)
        val WARNUNG = Color.rgb(255, 80, 60)
    }

    fun dp(wert: Int): Int = (wert * c.resources.displayMetrics.density).toInt()

    fun spalte(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(HINTERGRUND)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun zeile(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun scroll(inhalt: View): ScrollView = ScrollView(c).apply {
        setBackgroundColor(HINTERGRUND)
        addView(inhalt)
    }

    fun titel(text: String): TextView = TextView(c).apply {
        this.text = text
        textSize = 28f
        setTextColor(TEXT)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(12))
    }

    fun text(text: String, groesse: Float = 18f): TextView = TextView(c).apply {
        this.text = text
        textSize = groesse
        setTextColor(TEXT)
        setPadding(0, dp(4), 0, dp(4))
    }

    fun knopf(text: String, farbe: Int = AKZENT, aktion: () -> Unit): Button = Button(c).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(farbe)
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(12) }
        setOnClickListener { aktion() }
    }

    /** Große Anzeige für einen Wert, z. B. "Kühlwasser 90 °C". */
    inner class Kachel(beschriftung: String) {
        val wert: TextView = TextView(c).apply {
            text = "–"
            textSize = 44f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val ansicht: LinearLayout = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(KACHEL)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(8), dp(12), dp(8), dp(12))
            addView(TextView(c).apply {
                text = beschriftung
                textSize = 16f
                setTextColor(GEDAEMPFT)
                gravity = Gravity.CENTER
            })
            addView(wert)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
        }

        fun zeige(text: String, warnung: Boolean = false) {
            wert.text = text
            wert.setTextColor(if (warnung) WARNUNG else TEXT)
        }
    }
}
