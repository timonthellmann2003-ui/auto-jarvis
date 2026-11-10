# Auto Jarvis

Sprachassistent für den VW Polo 9N. Liest Fahrzeugdaten über einen ELM327-OBD2-Adapter (Vgate iCar Pro) und zeigt sie auf dem Android-Radio an.

## So hängt alles zusammen

```
Polo (OBD-Buchse) → Vgate-Adapter → Bluetooth → Handy (Brücke) → Hotspot/WLAN → Radio (Jarvis)
```

- **Handy = Brücke:** verbindet sich per klassischem Bluetooth mit dem Adapter (das Radio kann das nicht) und stellt ihn auf Port 35000 bereit, wie ein WLAN-OBD-Adapter. Ruft alle 2 s per UDP-Broadcast (Port 35001) ins WLAN, damit das Radio es findet – im Hotspot genauso wie im WLAN von kabellosem Android Auto.
- **Radio = Jarvis:** läuft als Hintergrunddienst, fragt die Werte ab, spricht und zeigt eine schwebende Leiste über allen Apps (auch über Android Auto). Das große Dashboard ist die App selbst.

Es ist **eine** App. Beim ersten Start wählt man, ob das Gerät Brücke oder Jarvis ist.

## Funktionen (Version 0.1)

- Kurze Rückmeldung beim Start: „Alles in Ordnung.“ oder „2 Fehler gefunden. Vorlesen?“ – vorgelesen wird erst nach Tippen auf „Vorlesen“ oder „Ja“
- Schwebende Leiste über Android Auto: Kühlwasser, Spannung, Verbrauch, Ø der Fahrt; rot bei Warnungen; verschiebbar
- Dashboard: Tempo, Drehzahl, Kühlwasser, Batteriespannung, Verbrauch jetzt, Ø Fahrt, Ø gesamt
- Verbrauch geschätzt aus Saugrohrdruck, Drehzahl und Ansauglufttemperatur (oder Luftmasse, falls vorhanden); Hubraum und Korrekturfaktor in den Einstellungen
- Fehlercodes lesen und kurz auf Deutsch erklären
- Warnungen: Motor über 110 °C, Ladespannung unter 13,0 V bei laufendem Motor, Batterie unter 12,0 V bei stehendem Motor
- Fragen per Knopf „Sprechen“: Temperatur, Batterie, Drehzahl, Tempo, Verbrauch, Fehler, Status

## Installation

Die APK wird bei jedem Push automatisch von GitHub Actions gebaut und unter **Releases** veröffentlicht:
`https://github.com/<benutzer>/auto-jarvis/releases/latest/download/auto-jarvis.apk`

1. Link im Browser öffnen (Handy und Radio), APK herunterladen und öffnen.
2. Beim ersten Mal „Installation aus unbekannten Quellen“ für den Browser erlauben.
3. Handy: „Brücke“ wählen, Adapter „Android-Vlink“ auswählen, „Brücke starten“.
4. Handy-Hotspot einschalten, Radio mit dem Hotspot verbinden.
5. Radio: „Jarvis“ wählen und „Über anderen Apps anzeigen“ erlauben (für die Leiste). Der Rest passiert automatisch.

## Automatischer Start (Samsung)

Einstellungen → Modi und Routinen → Routine hinzufügen:
- **Wenn:** Bluetooth-Gerät verbunden → „BT-2B25“ (das Radio)
- **Dann:** Mobiler Hotspot an, App öffnen → Auto Jarvis

## Test ohne Jarvis

Die Brücke verhält sich wie ein WLAN-OBD-Adapter. Car Scanner auf dem Radio kann sich deshalb auch direkt verbinden: Verbindungstyp WLAN, IP = IP des Handys im Hotspot, Port 35000.
