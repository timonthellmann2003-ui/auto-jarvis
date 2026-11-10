package de.autojarvis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.autojarvis.bridge.BridgeActivity
import de.autojarvis.jarvis.JarvisActivity

/** Startpunkt: Beim ersten Start wählt man, ob dieses Gerät Brücke (Handy) oder Jarvis (Radio) ist. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (Einstellungen.modus(this)) {
            Einstellungen.MODUS_BRUECKE -> oeffne(BridgeActivity::class.java)
            Einstellungen.MODUS_JARVIS -> oeffne(JarvisActivity::class.java)
            else -> zeigeAuswahl()
        }
    }

    private fun zeigeAuswahl() {
        val ui = Ui(this)
        val layout = ui.spalte()
        layout.addView(ui.titel("Auto Jarvis"))
        layout.addView(ui.text("Was ist dieses Gerät?"))
        layout.addView(ui.knopf("Handy: Brücke zum OBD-Adapter") {
            Einstellungen.setzeModus(this, Einstellungen.MODUS_BRUECKE)
            oeffne(BridgeActivity::class.java)
        })
        layout.addView(ui.knopf("Radio: Jarvis mit Dashboard") {
            Einstellungen.setzeModus(this, Einstellungen.MODUS_JARVIS)
            oeffne(JarvisActivity::class.java)
        })
        setContentView(ui.scroll(layout))
    }

    private fun oeffne(klasse: Class<*>) {
        startActivity(Intent(this, klasse))
        finish()
    }
}

object Einstellungen {
    const val MODUS_BRUECKE = "bruecke"
    const val MODUS_JARVIS = "jarvis"
    const val PORT = 35000

    private fun prefs(c: Context) = c.getSharedPreferences("auto_jarvis", Context.MODE_PRIVATE)

    fun modus(c: Context): String? = prefs(c).getString("modus", null)
    fun setzeModus(c: Context, modus: String?) = prefs(c).edit().putString("modus", modus).apply()

    fun adapterAdresse(c: Context): String? = prefs(c).getString("adapter", null)
    fun setzeAdapterAdresse(c: Context, adresse: String) = prefs(c).edit().putString("adapter", adresse).apply()

    /** Leer = Handy automatisch über den Hotspot finden. */
    fun brueckeIp(c: Context): String = prefs(c).getString("bruecke_ip", "") ?: ""
    fun setzeBrueckeIp(c: Context, ip: String) = prefs(c).edit().putString("bruecke_ip", ip.trim()).apply()
}
