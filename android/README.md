# Tablet-Agent (Android)

Kleine Open-Source-App für die Wand-Tablets (Fire OS 5 = Android 5.1, minSdk 21).
Läuft dauerhaft im Hintergrund, startet beim Booten und ist per MQTT fernsteuerbar.
Erster Schritt hin zum Ersatz von WallPanel. Das Overlay bleibt beim tabletviewer.

## MQTT (Basis `tablet/<geraet>`)

| Topic | Richtung | Inhalt |
|---|---|---|
| `online` | Tablet → | `online` / `offline` (retained, Last Will) |
| `status` | Tablet → | JSON (retained): Akku, WLAN, Bildschirm, RAM, Vordergrund-App, WebView-Version, … alle 60 s und nach jedem Befehl |
| `befehl` | → Tablet | JSON-Befehl, mehrere Schlüssel erlaubt, optional `"id"` |
| `antwort` | Tablet → | `{"id", "ok", "ergebnis", "fehler"}` |
| `bildschirmfoto` | Tablet → | JPEG |

Befehle (die WallPanel-Namen `brightness`, `wake`/`wakeTime`, `volume`, `speak` gehen auch):

```json
{"wecken": 240}
{"helligkeit": 128}
{"lautstaerke": 50}
{"sage": "Hallo"}
{"starte": "com.thanksmister.iot.wallpanel", "neu": true}
{"oeffne": "http://192.168.7.23:8080/flur?geraet=fire10"}
{"bildschirmfoto": {"breite": 800, "qualitaet": 70}}
{"apps": true}
{"bildschirm_aus_ms": 600000}
{"status": true}
```

Beispiel:

```bash
mosquitto_sub -h 192.168.7.76 -t 'tablet/fire10/#' -v
mosquitto_pub -h 192.168.7.76 -t tablet/fire10/befehl -m '{"id":"t1","status":true}'
mosquitto_sub -h 192.168.7.76 -t tablet/fire10/bildschirmfoto -C 1 > bild.jpg
```

## Installieren (ohne Kabel)

1. Am Tablet: Einstellungen → Sicherheit → *Apps aus unbekannten Quellen* erlauben.
2. Im Silk-Browser `http://192.168.7.23:8080/app/tablet-agent.apk` öffnen und installieren.
3. App „Tablet-Agent“ öffnen, Gerätenamen eintragen (`fire10`, `kueche`, …), *Speichern und verbinden*.
4. *Bildschirmfotos erlauben* → im Dialog „Nicht mehr anzeigen“ anhaken → *Jetzt starten*.

Mit Kabel geht die Einrichtung auch so:

```bash
adb install -r tablet-agent.apk
adb shell am start -n de.zerties.tabletagent/.MainActivity --es geraet fire10
```

## Bauen

Auf gastonllm: JDK unter `~/opt/jdk21`, Android SDK unter `~/Android/Sdk`
(Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24 wie entrance-key).

```bash
android/baue.sh     # -> android/dist/tablet-agent.apk + tablet-agent.json
```

Die Release-Signatur liegt **außerhalb von git** in `~/.config/tablet-agent/`
(`release.jks`, `keystore.properties`, Symlink nach `android/keystore.properties`).
Unbedingt sichern: Ohne diesen Schlüssel lassen sich Updates nicht mehr über
die installierte App spielen, dann hilft nur Deinstallieren.

## Grenzen von Android 5.1 ohne Root

- Die Vordergrund-App kommt aus `/proc` (bis Android 6 ohne Berechtigung lesbar).
  Den Nutzungsdatenzugriff versteckt Fire OS, er wird nicht gebraucht.

- Neustart des Tablets geht nicht (braucht API 24 oder Root).
- Frei auf Koordinaten tippen geht nicht (Gesten erst ab API 24). Geplant sind
  Bedienungshilfen für Zurück/Home und Knöpfe nach Text.
- Stilles Selbst-Update braucht später „Device Owner“ (einmalig per adb).
