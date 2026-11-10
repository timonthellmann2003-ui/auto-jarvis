package de.autojarvis.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import de.autojarvis.Einstellungen
import de.autojarvis.Entdeckung
import de.autojarvis.obd.ObdClient
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * Jarvis auf dem Radio, läuft dauerhaft im Hintergrund.
 * Liest die Werte über die Brücke, spricht Ansagen und zeigt die schwebende Leiste über Android Auto.
 */
class JarvisService : Service() {

    companion object {
        private const val KANAL = "jarvis"
        const val MOTOR_HEISS = 110
        private const val MOTOR_WIEDER_OK = 105
        const val LADESPANNUNG_MIN = 13.0
        const val BATTERIE_MIN = 12.0

        @Volatile var fahrzeug = Fahrzeug()
            private set
        @Volatile var status = "Gestoppt"
            private set
        @Volatile var instanz: JarvisService? = null
            private set

        fun start(c: Context) {
            val i = Intent(c, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun spannungWarnung(f: Fahrzeug): Boolean {
            val v = f.batterieV ?: return false
            return if (f.motorLaeuft) v < LADESPANNUNG_MIN else v < BATTERIE_MIN
        }
    }

    private val haupt = Handler(Looper.getMainLooper())
    @Volatile private var aktiv = false
    private val fehlerAnfrage = AtomicBoolean(false)
    private var begruesst = false
    private var warnungHeiss = false
    private var warnungLaden = false
    private var warnungBatterie = false

    /** Gefundene Fehler, die noch auf "Vorlesen" warten. */
    @Volatile var offeneFehler: List<String> = emptyList()
        private set

    private var tts: TextToSpeech? = null
    private var ttsBereit = false
    private val wartendeAnsagen = mutableListOf<String>()
    private var nachAnsage: (() -> Unit)? = null
    private var leiste: Leiste? = null
    lateinit var verbrauch: Verbrauch
        private set
    @Volatile private var gefundeneIp: String? = null
    @Volatile private var gefundenUm = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instanz = this
        verbrauch = Verbrauch(this)
        zeigeBenachrichtigung("Startet …")
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.GERMAN)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        val danach = nachAnsage ?: return
                        nachAnsage = null
                        haupt.post(danach)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {}
                })
                ttsBereit = true
                wartendeAnsagen.forEach { sage(it) }
                wartendeAnsagen.clear()
            }
        }
        zeigeLeiste()
        aktiv = true
        thread(name = "obd") { obdSchleife() }
        thread(name = "entdeckung") { lauscheAufBruecke() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        zeigeLeiste()
        return START_STICKY
    }

    override fun onDestroy() {
        aktiv = false
        instanz = null
        verbrauch.speichere()
        leiste?.entferne()
        tts?.shutdown()
        super.onDestroy()
    }

    /** Leiste anzeigen, sobald die Overlay-Berechtigung da ist. */
    fun zeigeLeiste() {
        if (leiste == null && Leiste.erlaubt(this)) {
            leiste = Leiste(this, onVorlesen = { liesFehlerVor() }, onSchliessen = { offeneFehler = emptyList(); aktualisiereLeiste() })
            aktualisiereLeiste()
        }
    }

    // ---------- OBD ----------

    private fun obdSchleife() {
        while (aktiv) {
            var client: ObdClient? = null
            try {
                val (ip, fabrik) = findeBruecke() ?: throw Exception("Handy nicht gefunden. Läuft die Brücke, und sind beide im selben WLAN?")
                setzeStatus("Verbinde mit Brücke $ip …")
                client = ObdClient(fabrik)
                client.verbinde(ip, Einstellungen.PORT)
                setzeStatus("Verbinde mit dem Auto …")
                client.initialisiere()
                setzeStatus("Verbunden")
                abfragen(client)
            } catch (e: Exception) {
                setzeStatus("Keine Verbindung: ${e.message}")
                Thread.sleep(3000)
            } finally {
                client?.trenne()
            }
        }
    }

    /** Fragt die Werte reihum ab. K-Line ist langsam, darum Tempo/Drehzahl/Druck oft, den Rest seltener. */
    private fun abfragen(obd: ObdClient) {
        val hatLuftmasse = obd.leseModus01("10") != null
        var luftC = 20
        var letzteZeit = System.currentTimeMillis()
        var runde = 0
        while (aktiv) {
            var f = fahrzeug
            obd.leseModus01("0D")?.let { f = f.copy(tempoKmh = it.getOrNull(0)) }
            obd.leseModus01("0C")?.let { if (it.size >= 2) f = f.copy(drehzahl = (it[0] * 256 + it[1]) / 4) }
            if (runde % 5 == 0) {
                obd.leseModus01("05")?.let { f = f.copy(kuehlwasserC = it.getOrNull(0)?.minus(40)) }
                obd.leseSpannung()?.let { f = f.copy(batterieV = it) }
                if (!hatLuftmasse) obd.leseModus01("0F")?.getOrNull(0)?.let { luftC = it - 40 }
            }

            // Verbrauch
            val lh: Double? = if (hatLuftmasse) {
                obd.leseModus01("10")?.takeIf { it.size >= 2 }?.let { verbrauch.ausLuftmasse((it[0] * 256 + it[1]) / 100.0) }
            } else {
                val kPa = obd.leseModus01("0B")?.getOrNull(0)
                val n = f.drehzahl
                if (kPa != null && n != null) verbrauch.ausSaugrohrdruck(kPa, n, luftC) else null
            }
            val jetzt = System.currentTimeMillis()
            if (lh != null) {
                val (fahrt, gesamt) = verbrauch.addiere(lh, f.tempoKmh ?: 0, (jetzt - letzteZeit) / 1000.0)
                f = f.copy(verbrauchLh = lh, schnittFahrt = fahrt, schnittGesamt = gesamt)
            }
            letzteZeit = jetzt

            if (!begruesst) {
                val fehler = try { obd.leseFehlercodes() } catch (_: Exception) { null }
                f = f.copy(fehler = fehler)
                begruesst = true
                begruesse(fehler)
            } else if (fehlerAnfrage.getAndSet(false)) {
                val fehler = try { obd.leseFehlercodes() } catch (_: Exception) { null }
                f = f.copy(fehler = fehler)
                sage(Sprache.fehlerSatz(fehler))
            }
            fahrzeug = f
            pruefeWarnungen(f)
            aktualisiereLeiste()
            runde++
        }
    }

    /** Kurze Rückmeldung beim Start. Fehler werden erst nach Bestätigung aufgezählt. */
    private fun begruesse(fehler: List<String>?) {
        when {
            fehler == null -> sage("Fehlerspeicher nicht lesbar.")
            fehler.isEmpty() -> sage("Alles in Ordnung.")
            else -> {
                offeneFehler = fehler
                val anzahl = if (fehler.size == 1) "Ein Fehler" else "${fehler.size} Fehler"
                sage("$anzahl gefunden. Vorlesen?") { hoereAufJa() }
            }
        }
    }

    fun liesFehlerVor() {
        val fehler = offeneFehler
        offeneFehler = emptyList()
        aktualisiereLeiste()
        if (fehler.isNotEmpty()) sage(Sprache.fehlerSatz(fehler))
    }

    fun pruefeFehler() = fehlerAnfrage.set(true)

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

    /** Hört auf den Ruf der Brücke, damit das Radio das Handy in jedem WLAN findet. */
    private fun lauscheAufBruecke() {
        @Suppress("DEPRECATION")
        val sperre = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("autojarvis").apply { setReferenceCounted(false); acquire() }
        try {
            Entdeckung.empfangsSocket().use { socket ->
                while (aktiv) {
                    val ip = try { Entdeckung.lausche(socket, 2000) } catch (_: Exception) { null }
                    if (ip != null) {
                        gefundeneIp = ip
                        gefundenUm = System.currentTimeMillis()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            sperre.release()
        }
    }

    /**
     * Findet das Handy. Reihenfolge: von Hand eingetragene IP, dann der Ruf der Brücke,
     * dann das Gateway des WLANs (im Handy-Hotspot ist das Handy das Gateway).
     */
    private fun findeBruecke(): Pair<String, SocketFactory>? {
        val manuell = Einstellungen.brueckeIp(this)
        if (manuell.isNotEmpty()) return manuell to SocketFactory.getDefault()

        // Kurz auf den Ruf warten, falls er noch nicht kam.
        repeat(6) {
            if (System.currentTimeMillis() - gefundenUm < 15_000) gefundeneIp?.let { return it to SocketFactory.getDefault() }
            Thread.sleep(500)
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wlan = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val fabrik = wlan?.socketFactory ?: SocketFactory.getDefault()
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

    // ---------- Sprache ----------

    /** Spricht einen Satz. [danach] läuft, wenn der Satz zu Ende gesprochen ist. */
    fun sage(text: String, danach: (() -> Unit)? = null) = haupt.post {
        if (!ttsBereit) {
            wartendeAnsagen += text
            return@post
        }
        nachAnsage = danach
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    /** Hört kurz zu, ob "Ja" gesagt wird. Klappt das nicht, bleibt der Knopf "Vorlesen" in der Leiste. */
    private fun hoereAufJa() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val erkenner = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { return }
        erkenner.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val worte = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (worte.any { w -> w.lowercase().let { it.contains("ja") || it.contains("vorlesen") || it.contains("okay") } }) {
                    liesFehlerVor()
                }
                erkenner.destroy()
            }
            override fun onError(error: Int) { erkenner.destroy() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        try { erkenner.startListening(intent) } catch (_: Exception) { erkenner.destroy() }
    }

    // ---------- Anzeige ----------

    private fun aktualisiereLeiste() = haupt.post { leiste?.zeige(fahrzeug, offeneFehler.size) }

    private fun setzeStatus(text: String) {
        status = text
        zeigeBenachrichtigung(text)
        haupt.post { leiste?.zeigeStatus(text) }
    }

    private fun zeigeBenachrichtigung(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(KANAL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, JarvisActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, KANAL) else Notification.Builder(this)
        val n = builder
            .setContentTitle("Auto Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(oeffnen)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }
}
