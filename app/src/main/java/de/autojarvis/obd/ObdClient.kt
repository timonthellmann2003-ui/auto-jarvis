package de.autojarvis.obd

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * Spricht mit einem ELM327-Adapter über TCP (hier: die Brücke auf dem Handy).
 * Nicht threadsicher: nur von einem Hintergrund-Faden benutzen.
 */
class ObdClient(private val socketFactory: SocketFactory = SocketFactory.getDefault()) {

    private var socket: Socket? = null
    private var ein: InputStream? = null
    private var aus: OutputStream? = null

    val verbunden: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun verbinde(host: String, port: Int) {
        trenne()
        val s = socketFactory.createSocket()
        s.connect(InetSocketAddress(host, port), 4000)
        s.tcpNoDelay = true
        socket = s
        ein = s.getInputStream()
        aus = s.getOutputStream()
    }

    fun trenne() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        ein = null
        aus = null
    }

    /** Adapter einrichten. Der erste "0100" startet beim Polo 9N die K-Line-Protokollsuche (kann ~10 s dauern). */
    fun initialisiere() {
        sende("ATZ", 5000)
        Thread.sleep(500)
        for (befehl in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) sende(befehl, 3000)
        val antwort = sende("0100", 20000)
        if (!antwort.contains("4100")) throw IOException("Auto antwortet nicht: $antwort")
    }

    /** Schickt einen Befehl und liest bis zum ">"-Prompt. Gibt die Antwort ohne Leerzeichen/Prompt zurück. */
    fun sende(befehl: String, timeoutMs: Int = 3000): String {
        val s = socket ?: throw IOException("Nicht verbunden")
        val e = ein ?: throw IOException("Nicht verbunden")
        val a = aus ?: throw IOException("Nicht verbunden")
        s.soTimeout = timeoutMs
        a.write((befehl + "\r").toByteArray())
        a.flush()
        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (true) {
            val b = try {
                e.read()
            } catch (_: SocketTimeoutException) {
                throw IOException("Zeitüberschreitung bei $befehl")
            }
            if (b < 0) throw IOException("Verbindung beendet")
            if (b == '>'.code) break
            sb.append(b.toChar())
            if (System.currentTimeMillis() - start > timeoutMs) throw IOException("Zeitüberschreitung bei $befehl")
        }
        return sb.toString()
            .replace("SEARCHING...", "")
            .replace("BUS INIT: ...OK", "")
            .replace("BUS INIT: OK", "")
            .replace(" ", "")
            .replace("\u0000", "")
            .trim()
    }

    /** Liest einen Modus-01-Wert und gibt die Datenbytes zurück, oder null wenn nicht unterstützt. */
    fun leseModus01(pid: String): IntArray? {
        val antwort = sende("01$pid")
        val kopf = "41$pid"
        for (zeile in antwort.split('\r', '\n')) {
            val i = zeile.indexOf(kopf)
            if (i >= 0) return hexBytes(zeile.substring(i + kopf.length))
        }
        return null
    }

    /** Batteriespannung, wie der Adapter sie an der OBD-Buchse misst. */
    fun leseSpannung(): Double? {
        val antwort = sende("ATRV")
        return antwort.replace("V", "").replace(",", ".").trim().toDoubleOrNull()
    }

    /** Gespeicherte Fehlercodes (Modus 03), z. B. ["P0133"]. */
    fun leseFehlercodes(): List<String> = Dtc.parse(sende("03", 8000))

    fun loescheFehlercodes(): Boolean = sende("04", 8000).contains("44")

    companion object {
        fun hexBytes(hex: String): IntArray {
            val sauber = hex.filter { it.isLetterOrDigit() }
            val n = sauber.length / 2
            return IntArray(n) { sauber.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: 0 }
        }
    }
}
