package de.autojarvis.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import de.autojarvis.Einstellungen
import de.autojarvis.MainActivity
import de.autojarvis.Ui

/** Großes Dashboard auf dem Radio. Die eigentliche Arbeit macht der [JarvisService] im Hintergrund. */
class JarvisActivity : Activity() {

    companion object {
        private const val SPRACHE_ANFRAGE = 7
    }

    private lateinit var ui: Ui
    private lateinit var tempo: Ui.Kachel
    private lateinit var drehzahl: Ui.Kachel
    private lateinit var kuehlwasser: Ui.Kachel
    private lateinit var batterie: Ui.Kachel
    private lateinit var verbrauchJetzt: Ui.Kachel
    private lateinit var schnittFahrt: Ui.Kachel
    private lateinit var schnittGesamt: Ui.Kachel
    private lateinit var statusText: TextView
    private lateinit var leisteKnopf: View

    private val handler = Handler(Looper.getMainLooper())
    private val aktualisieren = object : Runnable {
        override fun run() {
            zeigeWerte(JarvisService.fahrzeug)
            statusText.text = JarvisService.status
            leisteKnopf.visibility = if (Leiste.erlaubt(this@JarvisActivity)) View.GONE else View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        val layout = ui.spalte()
        val kopf = ui.zeile().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        kopf.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 22f
            typeface = Ui.SPORT
            setTextColor(Ui.AKZENT)
            letterSpacing = 0.1f
            setPadding(ui.dp(4), 0, ui.dp(16), 0)
        })
        statusText = ui.text("", 14f).apply { setTextColor(Ui.GEDAEMPFT); typeface = Ui.SPORT_SCHMAL }
        kopf.addView(statusText)
        layout.addView(kopf)

        tempo = ui.Kachel("Tempo km/h")
        drehzahl = ui.Kachel("Drehzahl")
        kuehlwasser = ui.Kachel("Kühlwasser °C")
        batterie = ui.Kachel("Batterie V")
        verbrauchJetzt = ui.Kachel("Verbrauch jetzt")
        schnittFahrt = ui.Kachel("Ø diese Fahrt")
        schnittGesamt = ui.Kachel("Ø gesamt")
        fun reihe(vararg kacheln: Ui.Kachel) = ui.zeile().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            kacheln.forEach { addView(it.ansicht) }
        }
        layout.addView(reihe(tempo, drehzahl, kuehlwasser, batterie))
        layout.addView(reihe(verbrauchJetzt, schnittFahrt, schnittGesamt))

        fun knopf(text: String, farbe: Int, aktion: () -> Unit) =
            ui.knopf(text, farbe, aktion).apply {
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(64), 1f).apply {
                    setMargins(ui.dp(6), ui.dp(6), ui.dp(6), 0)
                }
            }
        val knoepfe = ui.zeile()
        knoepfe.addView(knopf("🎤 Sprechen", Ui.AKZENT) { hoereZu() })
        knoepfe.addView(knopf("Fehler prüfen", Ui.KACHEL) { JarvisService.instanz?.pruefeFehler() })
        knoepfe.addView(knopf("Kosten", Ui.KACHEL) { zeigeKosten() })
        leisteKnopf = knopf("Leiste erlauben", Ui.WARNUNG) { erlaubeLeiste() }
        knoepfe.addView(leisteKnopf)
        knoepfe.addView(knopf("Einstellungen", Ui.KACHEL) { zeigeEinstellungen() })
        layout.addView(knoepfe)
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        if (!Leiste.erlaubt(this)) erlaubeLeiste()

        JarvisService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Falls die Leiste gerade erst erlaubt wurde.
        JarvisService.instanz?.zeigeLeiste()
        handler.post(aktualisieren)
    }

    override fun onPause() {
        handler.removeCallbacks(aktualisieren)
        super.onPause()
    }

    /** Kosten nur auf Abruf: kurz anzeigen und ansagen. */
    private fun zeigeKosten() {
        val f = JarvisService.fahrzeug
        val text = Sprache.kostenSatz(f)
        JarvisService.instanz?.sage(text)
        AlertDialog.Builder(this)
            .setTitle("Kosten")
            .setMessage(
                "Diese Fahrt: ${f.fahrtEuro?.let { Fahrzeug.euro(it) + " €" } ?: "–"}" +
                    "\nLiter: ${f.fahrtLiter?.let { Fahrzeug.komma(it) } ?: "–"}" +
                    "\nPro 100 km: ${f.euroPro100?.let { Fahrzeug.euro(it) + " €" } ?: "–"}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun erlaubeLeiste() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setMessage("Bitte in den Android-Einstellungen bei Auto Jarvis „Über anderen Apps anzeigen“ erlauben.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun zeigeWerte(f: Fahrzeug) {
        tempo.zeige(f.tempoKmh?.toString() ?: "–")
        drehzahl.zeige(f.drehzahl?.toString() ?: "–")
        kuehlwasser.zeige(f.kuehlwasserC?.toString() ?: "–", (f.kuehlwasserC ?: 0) >= JarvisService.MOTOR_HEISS)
        batterie.zeige(f.spannungText() ?: "–", JarvisService.spannungWarnung(f))
        verbrauchJetzt.zeige(f.verbrauchText() ?: "–")
        schnittFahrt.zeige(f.schnittFahrt?.let { Fahrzeug.komma(it) } ?: "–")
        schnittGesamt.zeige(f.schnittGesamt?.let { Fahrzeug.komma(it) } ?: "–")
    }

    // ---------- Sprechen ----------

    private fun hoereZu() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Frag Jarvis …")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SPRACHE_ANFRAGE)
        } catch (_: ActivityNotFoundException) {
            JarvisService.instanz?.sage("Spracherkennung ist auf diesem Gerät nicht installiert.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPRACHE_ANFRAGE || resultCode != RESULT_OK) return
        val frage = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        val jarvis = JarvisService.instanz ?: return
        if (jarvis.offeneFehler.isNotEmpty() && frage.lowercase().let { it.contains("ja") || it.contains("vorlesen") }) {
            jarvis.liesFehlerVor()
            return
        }
        when (val antwort = Sprache.antworte(frage, JarvisService.fahrzeug)) {
            is Sprache.Antwort.Text -> jarvis.sage(antwort.text)
            Sprache.Antwort.FehlerLesen -> jarvis.pruefeFehler()
        }
    }

    // ---------- Einstellungen ----------

    private fun zeigeEinstellungen() {
        val prefs = getSharedPreferences("auto_jarvis", Context.MODE_PRIVATE)
        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        fun feld(beschriftung: String, wert: String, typ: Int): EditText {
            spalte.addView(TextView(this).apply { text = beschriftung })
            return EditText(this).apply {
                setText(wert)
                inputType = typ
                spalte.addView(this)
            }
        }
        val ip = feld("IP des Handys (leer = automatisch)", Einstellungen.brueckeIp(this), InputType.TYPE_CLASS_TEXT)
        val hubraum = feld("Hubraum in cm³ (Fahrzeugschein P.1)", prefs.getInt("hubraum", 1198).toString(), InputType.TYPE_CLASS_NUMBER)
        val preis = feld(
            "Spritpreis in €/Liter",
            Fahrzeug.euro(prefs.getFloat("preis", 1.79f).toDouble()),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val korrektur = feld(
            "Verbrauch-Korrektur in % (100 = keine)",
            Math.round(prefs.getFloat("korrektur", 1f) * 100).toString(),
            InputType.TYPE_CLASS_NUMBER
        )

        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(spalte)
            .setPositiveButton("Speichern") { _, _ ->
                Einstellungen.setzeBrueckeIp(this, ip.text.toString())
                prefs.edit()
                    .putInt("hubraum", hubraum.text.toString().toIntOrNull() ?: 1198)
                    .putFloat("korrektur", (korrektur.text.toString().toIntOrNull() ?: 100) / 100f)
                    .putFloat("preis", preis.text.toString().replace(',', '.').toFloatOrNull() ?: 1.79f)
                    .apply()
            }
            .setNeutralButton("Mehr …") { _, _ -> zeigeMehr() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun waehleStimme() {
        val jarvis = JarvisService.instanz ?: return
        val stimmen = jarvis.stimmen()
        if (stimmen.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Keine deutsche Offline-Stimme gefunden. Bitte in den Android-Einstellungen unter Text-in-Sprache die deutschen Sprachdaten herunterladen.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Stimme wählen (antippen zum Anhören)")
            .setItems(stimmen.toTypedArray()) { _, i -> jarvis.setzeStimme(stimmen[i]) }
            .show()
    }

    private fun zeigeMehr() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Stimme wählen", "Ø-Verbrauch gesamt zurücksetzen", "Gerätemodus ändern")) { _, i ->
                when (i) {
                    0 -> waehleStimme()
                    1 -> JarvisService.instanz?.verbrauch?.setzeGesamtZurueck()
                    2 -> {
                        stopService(Intent(this, JarvisService::class.java))
                        Einstellungen.setzeModus(this, null)
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            }
            .show()
    }
}
