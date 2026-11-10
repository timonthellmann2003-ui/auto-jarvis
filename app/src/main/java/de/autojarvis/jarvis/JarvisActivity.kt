package de.autojarvis.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import de.autojarvis.Einstellungen
import de.autojarvis.MainActivity
import de.autojarvis.Ui
import de.autojarvis.obd.ObdClient
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** Der Jarvis-Bildschirm auf dem Radio: Dashboard, Ansagen, Fragen per Knopf. */
class JarvisActivity : Activity() {

    companion object {
        private const val SPRACHE_ANFRAGE = 7
        private const val MOTOR_HEISS = 110
        private const val MOTOR_WIEDER_OK = 105
        private const val LADESPANNUNG_MIN = 13.0
        private const val BATTERIE_MIN = 12.0
    }

    private lateinit var ui: Ui
    private lateinit var tempo: Ui.Kachel
    private lateinit var drehzahl: Ui.Kachel
    private lateinit var kuehlwasser: Ui.Kachel
    private lateinit var batterie: Ui.Kachel
    private lateinit var statusText: TextView

    private var tts: TextToSpeech? = null
    private var ttsBereit = false
    private val wartendeAnsagen = mutableListOf<String>()

    @Volatile private var aktiv = false
    @Volatile private var fahrzeug = Fahrzeug()
    private val fehlerAnfrage = AtomicBoolean(false)
    private var begruesst = false
    private var warnungHeiss = false
    private var warnungLaden = false
    private var warnungBatterie = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)

        val layout = ui.spalte()
        statusText = ui.text("Suche Brücke …", 16f).apply { setTextColor(Ui.GEDAEMPFT) }
        layout.addView(statusText)

        tempo = ui.Kachel("Tempo km/h")
        drehzahl = ui.Kachel("Drehzahl")
        kuehlwasser = ui.Kachel("Kühlwasser °C")
        batterie = ui.Kachel("Batterie V")
        val kacheln = ui.zeile().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            listOf(tempo, drehzahl, kuehlwasser, batterie).forEach { addView(it.ansicht) }
        }
        layout.addView(kacheln)

        val knoepfe = ui.zeile()
        fun knopf(text: String, farbe: Int, aktion: () -> Unit) =
            ui.knopf(text, farbe, aktion).apply {
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(72), 1f).apply {
                    setMargins(ui.dp(6), ui.dp(6), ui.dp(6), 0)
                }
            }
        knoepfe.addView(knopf("🎤 Sprechen", Ui.AKZENT) { hoereZu() })
        knoepfe.addView(knopf("Fehler prüfen", Ui.KACHEL) { fehlerAnfrage.set(true) })
        knoepfe.addView(knopf("Einstellungen", Ui.KACHEL) { zeigeEinstellungen() })
        layout.addView(knoepfe)

        setContentView(layout)

        // Läuft weiter, auch wenn Navi oder Musik im Vordergrund sind, damit Warnungen ankommen.
        aktiv = true
        thread(name = "obd") { obdSchleife() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.GERMAN)
                ttsBereit = true
                wartendeAnsagen.forEach { sage(it) }
                wartendeAnsagen.clear()
            }
        }
    }

    override fun onDestroy() {
        aktiv = false
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- OBD ----------

    private fun obdSchleife() {
        while (aktiv) {
            var client: ObdClient? = null
            try {
                val (ip, fabrik) = findeBruecke() ?: throw Exception("Kein WLAN. Ist der Handy-Hotspot an?")
                zeigeStatus("Verbinde mit Brücke $ip …")
                client = ObdClient(fabrik)
                client.verbinde(ip, Einstellungen.PORT)
                zeigeStatus("Verbinde mit dem Auto …")
                client.initialisiere()
                zeigeStatus("Verbunden")
                abfragen(client)
            } catch (e: Exception) {
                zeigeStatus("Keine Verbindung: ${e.message}")
                Thread.sleep(3000)
            } finally {
                client?.trenne()
            }
        }
    }

    /** Fragt die Werte reihum ab. K-Line ist langsam, darum Tempo/Drehzahl oft, den Rest seltener. */
    private fun abfragen(obd: ObdClient) {
        var runde = 0
        while (aktiv) {
            var f = fahrzeug
            obd.leseModus01("0D")?.let { f = f.copy(tempoKmh = it.getOrNull(0)) }
            obd.leseModus01("0C")?.let { if (it.size >= 2) f = f.copy(drehzahl = (it[0] * 256 + it[1]) / 4) }
            if (runde % 5 == 0) {
                obd.leseModus01("05")?.let { f = f.copy(kuehlwasserC = it.getOrNull(0)?.minus(40)) }
                obd.leseSpannung()?.let { f = f.copy(batterieV = it) }
            }
            if (!begruesst || fehlerAnfrage.getAndSet(false)) {
                val fehler = try { obd.leseFehlercodes() } catch (_: Exception) { null }
                f = f.copy(fehler = fehler)
                fahrzeug = f
                if (!begruesst) {
                    begruesst = true
                    sage(Sprache.begruessung(f))
                } else {
                    sage(Sprache.fehlerSatz(fehler))
                }
            }
            fahrzeug = f
            pruefeWarnungen(f)
            runOnUiThread { zeigeWerte(f) }
            runde++
        }
    }

    private fun pruefeWarnungen(f: Fahrzeug) {
        val t = f.kuehlwasserC
        if (t != null) {
            if (t >= MOTOR_HEISS && !warnungHeiss) {
                warnungHeiss = true
                sage("Achtung, Motor zu heiß: $t Grad.")
            } else if (t < MOTOR_WIEDER_OK) warnungHeiss = false
        }
        val v = f.batterieV ?: return
        if (f.motorLaeuft) {
            if (v < LADESPANNUNG_MIN && !warnungLaden) {
                warnungLaden = true
                sage("Achtung, Ladespannung niedrig: ${f.spannungText()} Volt. Lichtmaschine prüfen.")
            }
        } else if (v < BATTERIE_MIN && !warnungBatterie) {
            warnungBatterie = true
            sage("Batterie schwach: ${f.spannungText()} Volt.")
        }
    }

    /** Findet das Handy: Im Hotspot ist das Handy das Gateway des WLANs. */
    private fun findeBruecke(): Pair<String, SocketFactory>? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wlan = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val fabrik = wlan?.socketFactory ?: SocketFactory.getDefault()

        val manuell = Einstellungen.brueckeIp(this)
        if (manuell.isNotEmpty()) return manuell to fabrik

        val gateway = wlan?.let { cm.getLinkProperties(it) }?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        if (gateway != null) return gateway to fabrik

        @Suppress("DEPRECATION")
        val g = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        val ip = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
        return ip to fabrik
    }

    // ---------- Anzeige ----------

    private fun zeigeWerte(f: Fahrzeug) {
        tempo.zeige(f.tempoKmh?.toString() ?: "–")
        drehzahl.zeige(f.drehzahl?.toString() ?: "–")
        kuehlwasser.zeige(f.kuehlwasserC?.toString() ?: "–", (f.kuehlwasserC ?: 0) >= MOTOR_HEISS)
        val v = f.batterieV
        val vWarnung = v != null && (if (f.motorLaeuft) v < LADESPANNUNG_MIN else v < BATTERIE_MIN)
        batterie.zeige(f.spannungText() ?: "–", vWarnung)
    }

    private fun zeigeStatus(text: String) = runOnUiThread { statusText.text = text }

    // ---------- Sprache ----------

    private fun sage(text: String) = runOnUiThread {
        if (ttsBereit) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        else wartendeAnsagen += text
    }

    private fun hoereZu() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Frag Jarvis …")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SPRACHE_ANFRAGE)
        } catch (_: ActivityNotFoundException) {
            sage("Spracherkennung ist auf diesem Gerät nicht installiert.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPRACHE_ANFRAGE || resultCode != RESULT_OK) return
        val frage = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        when (val antwort = Sprache.antworte(frage, fahrzeug)) {
            is Sprache.Antwort.Text -> sage(antwort.text)
            Sprache.Antwort.FehlerLesen -> fehlerAnfrage.set(true)
        }
    }

    // ---------- Einstellungen ----------

    private fun zeigeEinstellungen() {
        val ipFeld = EditText(this).apply {
            hint = "leer = automatisch"
            setText(Einstellungen.brueckeIp(this@JarvisActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setMessage("IP-Adresse des Handys (nur nötig, wenn die automatische Suche nicht klappt):")
            .setView(ipFeld)
            .setPositiveButton("Speichern") { _, _ -> Einstellungen.setzeBrueckeIp(this, ipFeld.text.toString()) }
            .setNeutralButton("Gerätemodus ändern") { _, _ ->
                Einstellungen.setzeModus(this, null)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
