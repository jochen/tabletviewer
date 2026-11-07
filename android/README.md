# Tablet-Agent (Android)

Kleine Open-Source-App für die Wand-Tablets (Fire OS 5 = Android 5.1, minSdk 21).
Läuft dauerhaft im Hintergrund, startet beim Booten und ist per MQTT fernsteuerbar.
Seit 0.2 ersetzt sie WallPanel: ein Vollbild-Kiosk mit der Flur-Seite.
Das Overlay bleibt beim tabletviewer.

## MQTT (Basis `tablet/<geraet>`)

| Topic | Richtung | Inhalt |
|---|---|---|
| `online` | Tablet → | `online` / `offline` (retained, Last Will) |
| `status` | Tablet → | JSON (retained): Akku, WLAN, Bildschirm, RAM, Vordergrund-App, WebView-Version, … alle 60 s und nach jedem Befehl |
| `befehl` | → Tablet | JSON-Befehl, mehrere Schlüssel erlaubt, optional `"id"` |
| `antwort` | Tablet → | `{"id", "ok", "ergebnis", "fehler"}` |
| `bildschirmfoto` | Tablet → | JPEG |
| `konsole` | Tablet → | Fehler/Warnungen der Kiosk-Seite (auch aus dem HA-iframe), max. 30/min |
| `wallpanel/<geraet>/command` | → Tablet | WallPanel-Befehle (nur mit `wallpanel_topic`) |

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
{"autostart": true}
{"aktualisieren": true}
```

## Kiosk

```json
{"kiosk": {"aktiv": true, "start_url": "", "wallpanel_topic": true,
           "wecken_helligkeit": 200, "zurueck_s": 120, "immer_an": true}}
{"vorne": true}
{"laden": "http://…"}
{"neu_laden": true}
{"cache_leeren": true}
{"js": "document.title"}
```

- `start_url` leer = `http://192.168.7.23:8080/flur?geraet=<geraet>`.
- `wallpanel_topic`: hört zusätzlich auf `wallpanel/<geraet>/command`.
  - Unverändert: `brightness`, `wake`/`wakeTime`, `speak`, `volume`.
  - Übersetzt: `url` wird zu `laden`, `reload` zu `neu_laden`, `clearCache` zu
    `cache_leeren`, `eval` zu `js`, `relaunch` zu `vorne` + `neu_laden`.
  - Node-RED muss deshalb nichts ändern.
- `wecken_helligkeit`: Ist der Bildschirm beim Wecken dunkler, wird er für die
  Weckzeit so hell. Danach gilt der zuletzt per `brightness` gesetzte Wert. Die
  Zeitschaltuhr dunkelt während des Weckens nicht ab.
- `immer_an`: Der Bildschirm bleibt an. Geht er doch aus, weckt ihn der Agent
  innerhalb von 15 s und holt den Kiosk nach vorne.
- `zurueck_s`: Steht der Fire-Startbildschirm so lange vorne, kommt der Kiosk
  zurück. Einstellungen, Silk usw. bleiben unangetastet.
- Admin am Tablet: fünfmal schnell in die linke obere Ecke tippen. Das öffnet die
  Einstellungen, dort gibt es „Zum Kiosk“.
- Der Kiosk hat einen eigenen WebView-Speicher, HA muss einmal angemeldet werden.

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

- **Sperrbildschirm mit Werbung** (Fire „Spezialangebote“): Er lässt sich von
  einer App nicht wegschalten (`disableKeyguard` und `FLAG_DISMISS_KEYGUARD`
  helfen nicht). Er kommt nur, wenn der Bildschirm aus war, also praktisch nur
  nach einem Neustart: einmal wischen. Abhilfe: die Spezialangebote im
  Amazon-Konto entfernen oder einmal per Kabel
  `adb shell pm disable-user com.amazon.kindle.kso`.
- Die WebView ist Chrome 59 (Amazon WebView, nicht aktualisierbar). HA liefert
  `frontend_es5`, nutzt darin aber CSS mit `env(safe-area-inset-*)` (ab Chrome 69).
  Chrome 59 verwirft solche Regeln, unter anderem den Abstand unter der Kopfleiste:
  Die Kacheln rutschen unter die Leiste. **HaPatch** fängt die Skripte unter
  `/frontend_es5/` ab und ersetzt `env(…)` durch den Ersatzwert bzw. `0px`. Dazu
  kommt ein Polyfill für `scrollTo({…})`. Die gepatchten Dateien werden auf dem
  Tablet zwischengespeichert. Abschalten mit `{"kiosk":{"ha_patch":false}}`.

- Die Vordergrund-App kommt aus `/proc` (bis Android 6 ohne Berechtigung lesbar).
  Den Nutzungsdatenzugriff versteckt Fire OS, er wird nicht gebraucht.

- Neustart des Tablets geht nicht (braucht API 24 oder Root).
- Frei auf Koordinaten tippen geht nicht (Gesten erst ab API 24). Geplant sind
  Bedienungshilfen für Zurück/Home und Knöpfe nach Text.
- Stilles Selbst-Update braucht später „Device Owner“ (einmalig per adb).
