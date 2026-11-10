package de.autojarvis

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Damit Radio und Handy sich in jedem gemeinsamen WLAN finden (Hotspot oder kabelloses Android Auto):
 * Die Brücke ruft alle 2 Sekunden "AUTOJARVIS" ins Netz, das Radio hört zu und merkt sich die Adresse.
 */
object Entdeckung {
    const val PORT = 35001
    private const val KENNUNG = "AUTOJARVIS"

    /** Handy: einmal rufen, auf allen Netzwerk-Schnittstellen. */
    fun rufe(socket: DatagramSocket) {
        val daten = "$KENNUNG ${Einstellungen.PORT}".toByteArray()
        val ziele = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(ziele) { it.broadcast }
            }
        } catch (_: Exception) {}
        for (ziel in ziele) {
            try { socket.send(DatagramPacket(daten, daten.size, ziel, PORT)) } catch (_: Exception) {}
        }
    }

    fun sendeSocket(): DatagramSocket = DatagramSocket().apply { broadcast = true }

    /** Radio: wartet bis zu [timeoutMs] auf einen Ruf und gibt die IP des Handys zurück. */
    fun lausche(socket: DatagramSocket, timeoutMs: Int): String? {
        socket.soTimeout = timeoutMs
        val puffer = ByteArray(64)
        val paket = DatagramPacket(puffer, puffer.size)
        return try {
            socket.receive(paket)
            val text = String(paket.data, 0, paket.length)
            if (text.startsWith(KENNUNG)) paket.address.hostAddress else null
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    fun empfangsSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(PORT))
    }
}
