package de.autojarvis.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import de.autojarvis.Einstellungen
import de.autojarvis.MainActivity
import de.autojarvis.Ui

/** Bildschirm auf dem Handy: Adapter wählen, Brücke starten/stoppen, Status sehen. */
class BridgeActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var adapterText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val aktualisieren = object : Runnable {
        override fun run() {
            statusText.text = "Status: ${BridgeService.status}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val layout = ui.spalte()
        layout.addView(ui.titel("Brücke (Handy)"))
        layout.addView(ui.text("Das Handy verbindet sich per Bluetooth mit dem OBD-Adapter und reicht die Daten per Hotspot an das Radio weiter.", 16f))
        adapterText = ui.text("")
        layout.addView(adapterText)
        statusText = ui.text("Status: –")
        layout.addView(statusText)
        layout.addView(ui.knopf("Adapter auswählen") { waehleAdapter() })
        layout.addView(ui.knopf("Brücke starten") { starte() })
        layout.addView(ui.knopf("Brücke stoppen", Ui.KACHEL) { BridgeService.stop(this) })
        layout.addView(ui.knopf("Gerätemodus ändern", Ui.KACHEL) {
            BridgeService.stop(this)
            Einstellungen.setzeModus(this, null)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
        setContentView(ui.scroll(layout))

        frageBerechtigungen()
        zeigeAdapter()
        // Automatisch starten, wenn schon ein Adapter gewählt ist (z. B. über eine Samsung-Routine).
        if (Einstellungen.adapterAdresse(this) != null && hatBluetoothRecht()) starte()
    }

    override fun onResume() {
        super.onResume()
        handler.post(aktualisieren)
    }

    override fun onPause() {
        handler.removeCallbacks(aktualisieren)
        super.onPause()
    }

    private fun starte() {
        if (!hatBluetoothRecht()) {
            frageBerechtigungen()
            return
        }
        if (Einstellungen.adapterAdresse(this) == null) {
            waehleAdapter()
            return
        }
        BridgeService.start(this)
    }

    private fun hatBluetoothRecht(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun frageBerechtigungen() {
        val fehlend = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !hatBluetoothRecht()) fehlend += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) fehlend += Manifest.permission.POST_NOTIFICATIONS
        if (fehlend.isNotEmpty()) requestPermissions(fehlend.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        zeigeAdapter()
    }

    @SuppressLint("MissingPermission")
    private fun zeigeAdapter() {
        val adresse = Einstellungen.adapterAdresse(this)
        val name = if (adresse != null && hatBluetoothRecht()) {
            gekoppelteGeraete().firstOrNull { it.second == adresse }?.first ?: adresse
        } else adresse
        adapterText.text = "Adapter: ${name ?: "noch nicht gewählt"}"
    }

    @SuppressLint("MissingPermission")
    private fun gekoppelteGeraete(): List<Pair<String, String>> {
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        return bt.bondedDevices.map { (it.name ?: it.address) to it.address }
            .sortedByDescending { it.first.contains("vlink", true) || it.first.contains("obd", true) }
    }

    private fun waehleAdapter() {
        if (!hatBluetoothRecht()) {
            frageBerechtigungen()
            return
        }
        val geraete = gekoppelteGeraete()
        if (geraete.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Kein gekoppeltes Gerät")
                .setMessage("Bitte den Adapter zuerst in den Bluetooth-Einstellungen des Handys koppeln (PIN 1234 oder 0000).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("OBD-Adapter wählen")
            .setItems(geraete.map { it.first }.toTypedArray()) { _, i ->
                Einstellungen.setzeAdapterAdresse(this, geraete[i].second)
                zeigeAdapter()
            }
            .show()
    }
}
