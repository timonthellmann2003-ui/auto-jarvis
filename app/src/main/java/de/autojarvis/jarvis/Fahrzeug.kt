package de.autojarvis.jarvis

/** Aktueller Stand der Fahrzeugwerte. null = (noch) unbekannt. */
data class Fahrzeug(
    val tempoKmh: Int? = null,
    val drehzahl: Int? = null,
    val kuehlwasserC: Int? = null,
    val batterieV: Double? = null,
    val fehler: List<String>? = null,
    /** Liter pro Stunde, geschätzt. */
    val verbrauchLh: Double? = null,
    val schnittFahrt: Double? = null,
    val schnittGesamt: Double? = null,
    /** Verbrauchte Liter und Kosten dieser Fahrt. */
    val fahrtLiter: Double? = null,
    val fahrtEuro: Double? = null,
    /** Euro pro 100 km aus dem Gesamt-Durchschnitt. */
    val euroPro100: Double? = null,
) {
    val motorLaeuft: Boolean get() = (drehzahl ?: 0) > 400

    /** L/100 km beim Fahren, null im Stand (dann zählt [verbrauchLh]). */
    val verbrauchAktuell: Double?
        get() {
            val lh = verbrauchLh ?: return null
            val v = tempoKmh ?: return null
            return if (v >= 5) lh / v * 100 else null
        }

    fun spannungText(): String? = batterieV?.let { komma(it) }

    /** "6,4 L/100" beim Fahren, "0,8 L/h" im Stand. */
    fun verbrauchText(): String? =
        verbrauchAktuell?.let { "${komma(it)} L/100" } ?: verbrauchLh?.let { "${komma(it)} L/h" }

    companion object {
        fun komma(x: Double): String = String.format("%.1f", x).replace('.', ',')
        fun euro(x: Double): String = String.format("%.2f", x).replace('.', ',')
    }
}
