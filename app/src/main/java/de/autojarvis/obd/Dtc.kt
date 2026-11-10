package de.autojarvis.obd

/** Fehlercodes auslesen und auf Deutsch kurz erklären. */
object Dtc {

    /**
     * Wertet die Antwort auf "03" aus.
     * K-Line (Polo 9N): je Zeile "43" + 6 Bytes = 3 Codes, mit 0000 aufgefüllt.
     * CAN: "43" + Anzahl + 2 Bytes je Code. Erkennbar an der ungeraden Byteanzahl.
     */
    fun parse(antwort: String): List<String> {
        val codes = mutableListOf<String>()
        for (zeile in antwort.split('\r', '\n')) {
            val i = zeile.indexOf("43")
            if (i < 0) continue
            var bytes = ObdClient.hexBytes(zeile.substring(i + 2))
            if (bytes.size % 2 == 1) bytes = bytes.copyOfRange(1, bytes.size)
            var j = 0
            while (j + 1 < bytes.size) {
                val a = bytes[j]
                val b = bytes[j + 1]
                if (a != 0 || b != 0) codes += code(a, b)
                j += 2
            }
        }
        return codes.distinct()
    }

    fun code(a: Int, b: Int): String {
        val buchstabe = "PCBU"[(a shr 6) and 3]
        val ziffer1 = (a shr 4) and 3
        return buchstabe + ziffer1.toString() +
            Integer.toHexString(a and 0xF).uppercase() +
            String.format("%02X", b)
    }

    private val bekannte = mapOf(
        "P0100" to "Luftmassenmesser, Störung",
        "P0101" to "Luftmassenmesser, unplausibler Wert",
        "P0102" to "Luftmassenmesser, Signal zu niedrig",
        "P0103" to "Luftmassenmesser, Signal zu hoch",
        "P0105" to "Saugrohrdrucksensor, Störung",
        "P0106" to "Saugrohrdrucksensor, unplausibler Wert",
        "P0110" to "Ansauglufttemperatursensor, Störung",
        "P0115" to "Kühlmitteltemperatursensor, Störung",
        "P0116" to "Kühlmitteltemperatursensor, unplausibler Wert",
        "P0117" to "Kühlmitteltemperatursensor, Signal zu niedrig",
        "P0118" to "Kühlmitteltemperatursensor, Signal zu hoch",
        "P0120" to "Drosselklappensensor, Störung",
        "P0121" to "Drosselklappensensor, unplausibler Wert",
        "P0128" to "Kühlmitteltemperatur zu niedrig, Thermostat prüfen",
        "P0130" to "Lambdasonde vor Kat, Störung",
        "P0131" to "Lambdasonde vor Kat, Spannung zu niedrig",
        "P0132" to "Lambdasonde vor Kat, Spannung zu hoch",
        "P0133" to "Lambdasonde vor Kat reagiert zu langsam",
        "P0134" to "Lambdasonde vor Kat, kein Signal",
        "P0135" to "Lambdasonde vor Kat, Heizung defekt",
        "P0136" to "Lambdasonde nach Kat, Störung",
        "P0141" to "Lambdasonde nach Kat, Heizung defekt",
        "P0170" to "Gemischaufbereitung, Störung",
        "P0171" to "Gemisch zu mager",
        "P0172" to "Gemisch zu fett",
        "P0300" to "Zündaussetzer, mehrere Zylinder",
        "P0301" to "Zündaussetzer Zylinder 1",
        "P0302" to "Zündaussetzer Zylinder 2",
        "P0303" to "Zündaussetzer Zylinder 3",
        "P0304" to "Zündaussetzer Zylinder 4",
        "P0325" to "Klopfsensor, Störung",
        "P0335" to "Kurbelwellensensor, Störung",
        "P0340" to "Nockenwellensensor, Störung",
        "P0351" to "Zündspule 1, Störung",
        "P0352" to "Zündspule 2, Störung",
        "P0353" to "Zündspule 3, Störung",
        "P0354" to "Zündspule 4, Störung",
        "P0400" to "Abgasrückführung, Störung",
        "P0420" to "Katalysator, Wirkung zu gering",
        "P0440" to "Tankentlüftung, Störung",
        "P0441" to "Tankentlüftung, falscher Durchfluss",
        "P0442" to "Tankentlüftung, kleines Leck",
        "P0443" to "Tankentlüftungsventil, Störung",
        "P0455" to "Tankentlüftung, großes Leck, Tankdeckel prüfen",
        "P0500" to "Geschwindigkeitssensor, Störung",
        "P0505" to "Leerlaufregelung, Störung",
        "P0506" to "Leerlaufdrehzahl zu niedrig",
        "P0507" to "Leerlaufdrehzahl zu hoch",
        "P0560" to "Bordspannung, Störung",
        "P0562" to "Bordspannung zu niedrig",
        "P0563" to "Bordspannung zu hoch",
        "P0601" to "Motorsteuergerät, Speicherfehler",
        "P0650" to "Motorkontrollleuchte, Ansteuerung gestört",
    )

    private val gruppen = mapOf(
        "P00" to "Kraftstoff- und Luftmessung",
        "P01" to "Kraftstoff- und Luftmessung",
        "P02" to "Einspritzung",
        "P03" to "Zündung oder Zündaussetzer",
        "P04" to "Abgasreinigung",
        "P05" to "Leerlauf oder Geschwindigkeit",
        "P06" to "Steuergerät oder Bordnetz",
        "P07" to "Getriebe",
        "P08" to "Getriebe",
    )

    fun erklaere(code: String): String =
        bekannte[code] ?: gruppen[code.take(3)]?.let { "Bereich $it" } ?: "unbekannter Fehler"

    /** Für die Sprachausgabe: "P0133" -> "P 0 1 3 3". */
    fun zumSprechen(code: String): String = code.toCharArray().joinToString(" ")
}
