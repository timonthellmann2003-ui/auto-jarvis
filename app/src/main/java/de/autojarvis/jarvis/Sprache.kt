package de.autojarvis.jarvis

import de.autojarvis.obd.Dtc

/** Baut Jarvis' Sätze. Immer kurz und sachlich. */
object Sprache {

    fun begruessung(f: Fahrzeug): String {
        val teile = mutableListOf<String>()
        f.kuehlwasserC?.let { teile += "Motor $it Grad." }
        f.spannungText()?.let { teile += "Batterie $it Volt." }
        teile += fehlerSatz(f.fehler)
        return teile.joinToString(" ")
    }

    fun fehlerSatz(fehler: List<String>?): String = when {
        fehler == null -> "Fehlerspeicher nicht lesbar."
        fehler.isEmpty() -> "Keine Fehler."
        fehler.size == 1 -> "Ein Fehler: " + fehlerDetail(fehler[0])
        else -> "${fehler.size} Fehler: " + fehler.joinToString(" ") { fehlerDetail(it) }
    }

    private fun fehlerDetail(code: String) = "${Dtc.zumSprechen(code)}, ${Dtc.erklaere(code)}."

    /** Beantwortet eine gesprochene Frage. Fragen nach Fehlern lösen eine neue Abfrage am Auto aus. */
    fun antworte(frage: String, f: Fahrzeug): Antwort {
        val q = frage.lowercase()
        return when {
            q.contains("fehler") || q.contains("code") || q.contains("kontrollleuchte") ->
                Antwort.FehlerLesen
            q.contains("warm") || q.contains("temperatur") || q.contains("kühl") || q.contains("heiß") ->
                Antwort.Text(f.kuehlwasserC?.let { "Kühlwasser $it Grad." } ?: "Kühlwasser unbekannt.")
            q.contains("batterie") || q.contains("volt") || q.contains("spannung") ->
                Antwort.Text(f.spannungText()?.let { "Batterie $it Volt." } ?: "Spannung unbekannt.")
            q.contains("drehzahl") || q.contains("touren") ->
                Antwort.Text(f.drehzahl?.let { "$it Umdrehungen." } ?: "Drehzahl unbekannt.")
            q.contains("schnell") || q.contains("tempo") || q.contains("geschwindigkeit") ->
                Antwort.Text(f.tempoKmh?.let { "$it Kilometer pro Stunde." } ?: "Tempo unbekannt.")
            q.contains("verbrauch") || q.contains("sprit") || q.contains("liter") ->
                Antwort.Text(verbrauchSatz(f))
            q.contains("status") || q.contains("alles") || q.contains("check") || q.contains("zustand") ->
                Antwort.Text(begruessung(f))
            else -> Antwort.Text("Das kann ich noch nicht.")
        }
    }

    private fun verbrauchSatz(f: Fahrzeug): String {
        val teile = mutableListOf<String>()
        f.verbrauchAktuell?.let { teile += "Aktuell ${Fahrzeug.komma(it)} Liter auf 100." }
            ?: f.verbrauchLh?.let { teile += "Im Stand ${Fahrzeug.komma(it)} Liter pro Stunde." }
        f.schnittFahrt?.let { teile += "Diese Fahrt ${Fahrzeug.komma(it)}." }
        f.schnittGesamt?.let { teile += "Gesamt ${Fahrzeug.komma(it)}." }
        return if (teile.isEmpty()) "Verbrauch noch unbekannt." else teile.joinToString(" ")
    }

    sealed class Antwort {
        data class Text(val text: String) : Antwort()
        object FehlerLesen : Antwort()
    }
}
