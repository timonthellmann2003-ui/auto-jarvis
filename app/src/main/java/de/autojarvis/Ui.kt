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

/** Kleine Helfer, um Oberflächen ohne XML zu bauen. Sportliches, dunkles Design fürs Auto. */
class Ui(private val c: Context) {

    companion object {
        val HINTERGRUND = Color.rgb(6, 6, 8)
        val KACHEL = Color.rgb(22, 22, 26)
        val KACHEL_HELL = Color.rgb(34, 34, 40)
        val TEXT = Color.rgb(245, 245, 247)
        val GEDAEMPFT = Color.rgb(150, 150, 158)
        val AKZENT = Color.rgb(228, 24, 36)
        val WARNUNG = Color.rgb(200, 16, 24)
        val BLAU = Color.rgb(30, 170, 255)

        /** Schmale, kursive Schrift wie auf Rennsport-Anzeigen. */
        val SPORT: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
        val SPORT_SCHMAL: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    fun dp(wert: Int): Int = (wert * c.resources.displayMetrics.density).toInt()

    fun spalte(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(HINTERGRUND)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    fun zeile(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun scroll(inhalt: View): ScrollView = ScrollView(c).apply {
        setBackgroundColor(HINTERGRUND)
        addView(inhalt)
    }

    fun titel(text: String): TextView = TextView(c).apply {
        this.text = text.uppercase()
        textSize = 30f
        setTextColor(TEXT)
        typeface = SPORT
        letterSpacing = 0.05f
        setPadding(0, 0, 0, dp(4))
    }

    /** Roter Strich unter Überschriften. */
    fun strich(): View = View(c).apply {
        setBackgroundColor(AKZENT)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(4)).apply { bottomMargin = dp(12) }
    }

    fun text(text: String, groesse: Float = 18f): TextView = TextView(c).apply {
        this.text = text
        textSize = groesse
        setTextColor(TEXT)
        setPadding(0, dp(4), 0, dp(4))
    }

    fun knopf(text: String, farbe: Int = AKZENT, aktion: () -> Unit): Button = Button(c).apply {
        this.text = text.uppercase()
        textSize = 17f
        isAllCaps = false
        letterSpacing = 0.08f
        typeface = SPORT
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(farbe)
            cornerRadius = dp(4).toFloat()
            if (farbe != AKZENT && farbe != WARNUNG) setStroke(dp(1), KACHEL_HELL)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
        ).apply { topMargin = dp(10) }
        setOnClickListener { aktion() }
    }

    /** Große Anzeige für einen Wert, z. B. "KÜHLWASSER 90". Roter Streifen oben, bei Warnung ganz rot. */
    inner class Kachel(beschriftung: String) {
        private val hintergrund = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(KACHEL_HELL, KACHEL)
        ).apply { cornerRadius = dp(4).toFloat() }

        val wert: TextView = TextView(c).apply {
            text = "–"
            textSize = 46f
            setTextColor(TEXT)
            typeface = SPORT
            gravity = Gravity.CENTER
        }
        val ansicht: LinearLayout = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = hintergrund
            addView(View(c).apply { setBackgroundColor(AKZENT) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
            addView(TextView(c).apply {
                text = beschriftung.uppercase()
                textSize = 13f
                letterSpacing = 0.12f
                typeface = SPORT_SCHMAL
                setTextColor(BLAU)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), 0)
            })
            addView(wert, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }

        fun zeige(text: String, warnung: Boolean = false) {
            wert.text = text
            if (warnung) {
                hintergrund.setColors(intArrayOf(AKZENT, WARNUNG))
            } else {
                hintergrund.setColors(intArrayOf(KACHEL_HELL, KACHEL))
            }
        }
    }
}
