package de.autojarvis.jarvis

import android.content.Context

/**
 * Schätzt den Spritverbrauch. Der Polo 9N meldet ihn nicht direkt, darum:
 * - mit Luftmassenmesser (PID 10): Luftmasse direkt
 * - sonst aus Saugrohrdruck, Drehzahl und Ansauglufttemperatur (Speed-Density, wie Car Scanner)
 * Benzin: Luft/Kraftstoff 14,7 : 1, Dichte 745 g/L.
 * Mit dem Korrekturfaktor lässt sich das nach ein paar Tankfüllungen genau einstellen.
 */
class Verbrauch(private val c: Context) {

    private val prefs = c.getSharedPreferences("auto_jarvis", Context.MODE_PRIVATE)

    private var fahrtLiter = 0.0
    private var fahrtKm = 0.0
    private var gesamtLiter = prefs.getFloat("gesamt_liter_v2", 0f).toDouble()
    private var gesamtKm = prefs.getFloat("gesamt_km_v2", 0f).toDouble()
    private var letzteSpeicherung = System.currentTimeMillis()

    val hubraumCcm: Int get() = prefs.getInt("hubraum", 1198)
    val korrektur: Double get() = prefs.getFloat("korrektur", 1f).toDouble()

    /** Liter pro Stunde aus der Luftmasse (g/s). */
    fun ausLuftmasse(gProS: Double): Double = gProS / 14.7 * 3600 / 745 * korrektur

    /** Liter pro Stunde aus Saugrohrdruck (kPa), Drehzahl und Ansauglufttemperatur (°C). */
    fun ausSaugrohrdruck(kPa: Int, drehzahl: Int, luftC: Int): Double {
        val liefergrad = 0.85
        val dichte = kPa * 1000.0 / (287.05 * (luftC + 273.15))      // kg/m³
        val volumen = hubraumCcm / 1e6 * drehzahl / 120.0 * liefergrad // m³/s (4-Takt)
        val luftGProS = dichte * volumen * 1000
        return ausLuftmasse(luftGProS)
    }

    /** Verbrauch über [sekunden] aufsummieren und Durchschnitt (L/100 km) für Fahrt und gesamt liefern. */
    fun addiere(lh: Double, kmh: Int, sekunden: Double): Pair<Double?, Double?> {
        if (sekunden <= 0 || sekunden > 10) return schnitte()
        val liter = lh * sekunden / 3600
        val km = kmh * sekunden / 3600
        fahrtLiter += liter; fahrtKm += km
        gesamtLiter += liter; gesamtKm += km
        if (System.currentTimeMillis() - letzteSpeicherung > 30_000) speichere()
        return schnitte()
    }

    private fun schnitte(): Pair<Double?, Double?> = Pair(
        if (fahrtKm > 0.5) fahrtLiter / fahrtKm * 100 else null,
        if (gesamtKm > 0.5) gesamtLiter / gesamtKm * 100 else null,
    )

    fun speichere() {
        letzteSpeicherung = System.currentTimeMillis()
        prefs.edit().putFloat("gesamt_liter_v2", gesamtLiter.toFloat()).putFloat("gesamt_km_v2", gesamtKm.toFloat()).apply()
    }

    fun setzeGesamtZurueck() {
        gesamtLiter = 0.0; gesamtKm = 0.0
        speichere()
    }
}
