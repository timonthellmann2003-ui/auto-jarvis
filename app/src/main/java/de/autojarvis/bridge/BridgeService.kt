package de.autojarvis.bridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import de.autojarvis.Einstellungen
import de.autojarvis.Entdeckung
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Läuft auf dem Handy im Hintergrund.
 *
 * Hält die Bluetooth-Verbindung zum OBD-Adapter und öffnet einen TCP-Server auf Port 35000.
 * Alles, was das Radio per WLAN schickt, geht 1:1 an den Adapter und zurück.
 * Damit verhält sich das Handy wie ein WLAN-OBD-Adapter.
 */
class BridgeService : Service() {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val KANAL = "bruecke"

        @Volatile var status: String = "Gestoppt"
            private set
        @Volatile var laeuft = false
            private set

        fun start(c: Context) {
            val i = Intent(c, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, BridgeService::class.java))
        }
    }

    @Volatile private var aktiv = false
    private var server: ServerSocket? = null
    @Volatile private var bt: BluetoothSocket? = null
    @Volatile private var aktuellesRadio: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        zeigeBenachrichtigung("Startet …")
        if (!aktiv) {
            aktiv = true
            laeuft = true
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autojarvis:bruecke")
                .apply { acquire() }
            thread(name = "bruecke-server") { serverSchleife() }
            thread(name = "bruecke-ruf") { rufSchleife() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        aktiv = false
        laeuft = false
        setzeStatus("Gestoppt")
        try { server?.close() } catch (_: IOException) {}
        schliesseBt()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Ruft alle 2 Sekunden ins WLAN, damit das Radio das Handy findet. */
    private fun rufSchleife() {
        try {
            Entdeckung.sendeSocket().use { socket ->
                while (aktiv) {
                    Entdeckung.rufe(socket)
                    Thread.sleep(2000)
                }
            }
        } catch (_: Exception) {}
    }

    private fun serverSchleife() {
        while (aktiv) {
            try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(Einstellungen.PORT))
                    server = s
                    setzeStatus("Wartet auf Radio (Port ${Einstellungen.PORT})")
                    while (aktiv) {
                        val radio = s.accept()
                        bedieneRadio(radio)
                    }
                }
            } catch (e: IOException) {
                if (aktiv) {
                    setzeStatus("Serverfehler: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
    }

    /** Eine Verbindung vom Radio: Befehle an den Adapter weiterreichen, bis das Radio trennt. */
    private fun bedieneRadio(radio: Socket) {
        radio.tcpNoDelay = true
        val adapter = try {
            verbindeBt()
        } catch (e: Exception) {
            setzeStatus("Adapter nicht erreichbar: ${e.message}")
            try { radio.getOutputStream().write("UNABLE TO CONNECT\r\r>".toByteArray()) } catch (_: IOException) {}
            radio.close()
            return
        }
        aktuellesRadio = radio
        setzeStatus("Verbunden: Radio ↔ Adapter")
        try {
            val puffer = ByteArray(1024)
            val ein = radio.getInputStream()
            while (true) {
                val n = ein.read(puffer)
                if (n < 0) break
                adapter.outputStream.write(puffer, 0, n)
                adapter.outputStream.flush()
            }
        } catch (_: IOException) {
            // Radio weg oder Adapter weg; der Lesefaden merkt Letzteres selbst.
        } finally {
            aktuellesRadio = null
            try { radio.close() } catch (_: IOException) {}
        }
        if (aktiv) setzeStatus("Wartet auf Radio (Port ${Einstellungen.PORT})")
    }

    /** Liest dauerhaft vom Adapter und schickt alles an das gerade verbundene Radio. */
    private fun adapterLeser(socket: BluetoothSocket) {
        val puffer = ByteArray(1024)
        try {
            val ein = socket.inputStream
            while (true) {
                val n = ein.read(puffer)
                if (n < 0) break
                try {
                    aktuellesRadio?.getOutputStream()?.apply { write(puffer, 0, n); flush() }
                } catch (_: IOException) {}
            }
        } catch (_: IOException) {
        }
        if (bt === socket) {
            schliesseBt()
            try { aktuellesRadio?.close() } catch (_: IOException) {}
            if (aktiv) setzeStatus("Adapter getrennt, verbinde beim nächsten Mal neu")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun verbindeBt(): BluetoothSocket {
        bt?.let { if (it.isConnected) return it }
        schliesseBt()
        val adresse = Einstellungen.adapterAdresse(this)
            ?: throw IOException("Kein Adapter ausgewählt")
        val btAdapter: BluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IOException("Kein Bluetooth")
        if (!btAdapter.isEnabled) throw IOException("Bluetooth ist aus")
        val geraet = btAdapter.getRemoteDevice(adresse)
        setzeStatus("Verbinde mit ${geraet.name ?: adresse} …")
        // Braucht ab Android 12 die Scan-Berechtigung; ohne läuft die Verbindung trotzdem.
        try { btAdapter.cancelDiscovery() } catch (_: SecurityException) {}
        val socket = try {
            geraet.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            // Manche Adapter brauchen die unsichere Variante.
            geraet.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        }
        bt = socket
        thread(name = "adapter-leser") { adapterLeser(socket) }
        return socket
    }

    @Synchronized
    private fun schliesseBt() {
        try { bt?.close() } catch (_: IOException) {}
        bt = null
    }

    private fun setzeStatus(text: String) {
        status = text
        if (aktiv) zeigeBenachrichtigung(text)
    }

    private fun zeigeBenachrichtigung(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL, "OBD-Brücke", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, BridgeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, KANAL) else Notification.Builder(this)
        val n = builder
            .setContentTitle("Auto Jarvis Brücke")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
