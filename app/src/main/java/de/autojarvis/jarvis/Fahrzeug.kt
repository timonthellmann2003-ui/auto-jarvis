package de.autojarvis.jarvis

/** Aktueller Stand der Fahrzeugwerte. null = (noch) unbekannt. */
data class Fahrzeug(
    val tempoKmh: Int? = null,
    val drehzahl: Int? = null,
    val kuehlwasserC: Int? = null,
    val batterieV: Double? = null,
    val fehler: List<String>? = null,
) {
    val motorLaeuft: Boolean get() = (drehzahl ?: 0) > 400

    fun spannungText(): String? = batterieV?.let { String.format("%.1f", it).replace('.', ',') }
}
