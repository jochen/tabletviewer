# tabletviewer

Rezept-Anzeige für ein wandmontiertes Küchen-Tablet, bedient/gespeist über
clawdpi1 (OpenClaw/Gaston). Zeigt eine von Gaston kuratierte Auswahl der
Markdown-Rezepte aus dem OpenClaw-Workspace an.

> Voller Konzeptstand auch im MemPalace: Wing `clawdpi1-home-pi`, Room
> `decisions`. Diese README ist die lokale Arbeitsfassung.
>
> **Sprachkonvention:** `README.de.md` (diese Datei) ist die primäre Fassung;
> die englische `README.md` wird daraus mitgeführt (GitHub-Default). Code und
> Commit-/PR-Sprache: Englisch.

## Zielplattform

- **Gerät:** Amazon Fire 7, 5. Gen (2015), **Android 5.1**, Auflösung **1024×600**.
- **Anzeige:** WallPanel-WebView (bereits installiert). Gewählt statt Fully Kiosk,
  weil Fully den Android-5-Support eingestellt hat. Gepflegter Fork:
  `TheTimeWalker/wallpanel-android` (wallpanel.xyz), falls Neuinstallation nötig.
- **Hauptrisiko:** die **alte WebView-Komponente** auf Android 5.1 rendert
  moderne CSS/JS nicht zuverlässig. → Web-App MUSS bewusst schlicht sein:
  einfaches HTML/CSS, keine modernen Framework-Spielereien.
- Optimiert für 1024×600, **große Schrift (ohne Brille lesbar)**, fettfinger-tauglich.

## Bedienkonzept (zwei Modi, eine Oberfläche)

- **Modus „Auswahlliste" (Default/Leerlauf):** wenige große Kacheln (3–6),
  **kuratiert von Gaston** aus dem Wochen-/Essensplan der nächsten Tage —
  bewusst NICHT die ganze Rezept-Bibliothek. Tippen → „Aktives Rezept".
- **Modus „Aktives Rezept":** durchblätterbare Karten in fester Reihenfolge:
  1. **Titelseite** — großes Emoji-Icon (bzw. später Titelbild), Rezepttitel,
     Metadaten-Badges. Hält die Meta-Angaben von der Zutaten-Karte fern.
  2. **Zutaten** — bei Bedarf über mehrere Seiten (gruppenbewusst: Über­schriften
     werden nie verwaist, sondern auf der Folgeseite wiederholt).
  3. **Schritte** — **höhen-basierte Auto-Verteilung:** pro Karte so viele
     (kurze) Schritte, wie komfortabel passen; lange Schritte ggf. allein. Wird
     beim Drehen neu berechnet (clientseitig gemessen).
  Navigation über zwei große Tap-Zonen links/rechts (vor/zurück), KEIN freies
  Scrollen (verrutscht beim Kochen mit fettigen Fingern). Plus **Zurück-Button**
  zur Auswahlliste; ab Seite 2 läuft der Rezepttitel klein in der Kopfleiste mit.
- **Display-Schlaf / Wecken:** WallPanel läuft mit **„Prevent Sleep" an** — das
  Display wird nie wirklich abgeschaltet (sonst käme der Fire-Keyguard mit
  Wischsperre, der sich auf Android 5.1 nicht zuverlässig wegdrücken lässt).
  Das **Dimmen macht die Web-App selbst**: nach Inaktivität legt sich ein
  schwarzes Overlay drüber (Burn-in-/Strom-schonend), der **erste Tap weckt nur**
  und navigiert nicht. Ein Gaston-Push (Sprung zu Rezept X / neue Auswahl) weckt
  den Schirm ebenfalls. Timeout: `DIM_AFTER_MS` in `static/app.js`.

## Architektur

- Schlanke Web-App auf **clawdpi1** (Pi mit OpenClaw/Gaston). NICHT auf grogu
  (Internet-Server, ausdrücklich unerwünscht). Alles lokal im selben WLAN.
- **Direkte Verbindung** Tab ↔ clawdpi1. **Kein MQTT** (bleibt der Haussteuerung
  vorbehalten). Kein Honcho (abgeschafft).
- **Push von Gaston ans Tab:** Gaston sagt „spring jetzt auf Rezept X" bzw. setzt
  die kuratierte Auswahlliste. **Transport: WebSocket** (Tab hält die Verbindung
  offen), **Polling als automatischer Fallback**, falls die Android-5.1-WebView
  mit WS zickt.
- Petras bestehende Browser-Extension schickt Rezepte weiterhin an Gaston. Das
  Tab ist Anzeige + kuratierte Auswahl, **keine eigene Eingabe**.

## Datenquelle

- **Single source of truth:** die Markdown-Rezepte in
  `/home/pi/.openclaw/workspace/rezepte/` (Gastons feste Ablageregel: immer in
  `rezepte/`, sprechende Dateinamen). Kein neues Datenmodell.
- **Rezept-Inhalt** wird aus diesen Dateien gerendert; die **Auswahl** (welche
  Kacheln in der Übersicht) kommt von Gaston, nicht aus einem Verzeichnis-Scan.

## OpenClaw-Anbindung (Gaston → Viewer)

**Entscheidung:** Der Viewer ist ein **eigenständiger Mini-Webdienst auf clawdpi1**
mit schlichter HTTP-Steuer-API — **kein OpenClaw-Plugin**. Das folgt exakt dem
bestehenden Muster, mit dem Gaston schon andere Geräte steuert (vgl.
`TOOLS_WLED.md`, `TV-SKRIPT.md` im Workspace): ein `TOOLS_*.md` dokumentiert
`curl`-Befehle, die Gaston über sein Shell-Tool ausführt.

Begründung gegen ein Plugin (`openclaw-honcho`-Pfad): TypeScript + Build +
Allow-List + Plugin-Wartung wären Overkill und gegen „muss einfach sein". Der
Viewer bleibt so voll entkoppelt (separat startbar/testbar, eigener systemd-Dienst).
Der Plugin-Pfad (native, strukturierte Tool-Calls) bleibt als spätere Veredelung
offen.

### Steuer-API des Viewers

Eigener Dienst auf freiem Port (Vorschlag **8080**), NICHT über das OpenClaw-
Gateway (18789).

| Route | Zweck |
|---|---|
| `GET /` | die WallPanel-UI (statisches HTML/CSS/JS) |
| `GET /api/state` | aktueller Zustand (Liste + aktives Rezept); auch Polling-Fallback |
| `POST /api/list` `{"recipes":["butter-chicken",…]}` | kuratierte Kacheln setzen |
| `POST /api/active` `{"recipe":"butter-chicken"}` | „spring jetzt auf Rezept X" |
| `WS /ws` | Tab abonniert, Server pusht Zustandsänderungen |
| `POST /api/timer` | Küchentimer-Senke (siehe unten), nur für den Sprachassistenten |
| `POST /api/timer/klingelt` `{"id":…}` | das Tablet meldet: Klingeln hat begonnen |
| `POST /api/reload` | alle verbundenen Tablets laden die Seite neu (nach einem Update) |

### Küchentimer

Der Sprachassistent ([openclaw-voice-assistant](https://github.com/jochen/openclaw-voice-assistant),
Vertrag `TIMER_INTERFACE.md`) hält die Timer und schickt ihren ganzen Zustand
an `POST /api/timer`. Der Viewer zeigt sie unten rechts **über allem**, auch
über dem Dimm-Overlay. Laufende Timer sind dunkel, abgelaufene rot mit Minus
(„Nudel −2:30“), und ein klingelnder blinkt. Die Restzeit zählt das Tablet
mit der eigenen monotonen Uhr ab Empfang weiter, seine Wanduhr wird nicht
gebraucht.

Klingeln geht **nur über WebAudio**: Auf dem Fire 7 blockiert die WebView
`<audio>.play()` ohne Benutzergeste immer, WebAudio dagegen spielt auch nach
36 Minuten mit gedimmtem Bildschirm (gemessen 2026-10-10). Eine Folge besteht
aus vier Rechteck-Piepern mit 2,5 kHz; ein Sinus mit 440 Hz war zu leise. Hat
das Klingeln begonnen, meldet das Tablet es zurück. Erst dann antwortet
`/api/timer` dem Assistenten mit `"klingelt": [id]` und wartet dafür höchstens
3 s. Ohne diese Meldung klingelt der Assistent am eigenen Lautsprecher.

Optional `TABLETVIEWER_TIMER_TOKEN`: Dann muss der Assistent
`Authorization: Bearer <token>` senden.

### Gastons Anweisung — als OpenClaw-Skill

Die Steuerung ist als **offizieller OpenClaw-Skill** verpackt:
`skills/tabletviewer/SKILL.md`. Er erklärt dem Agenten Gerät und die
`curl`-Befehle, z. B.:

```bash
curl -s -X POST http://localhost:8080/api/active -d '{"recipe":"butter-chicken"}'
```

**Installation (auch für andere OpenClaw-Instanzen):** den Ordner
`skills/tabletviewer/` nach `~/.openclaw/workspace/skills/` kopieren — der Skill
lädt automatisch (kein Allow-List-Eintrag nötig). Prüfen mit
`openclaw skills list`, ggf. `openclaw gateway restart` oder `/new`.

Der Agent muss über die Gegebenheiten Bescheid wissen, damit er sinnvoll
kuratiert: welche Rezepte auf die Auswahlliste (3–6, aus dem Wochenplan), wie er
ein Rezept aktiv schaltet, und das **Rezept-Format** (siehe unten), das wir bei
Bedarf vereinheitlichen und als Schreib-/Ablageregel vorgeben können.

> **Skill vs. Plugin (OpenClaw-Terminologie):** ein *Skill* ist ein
> Workspace-Instruktionsset (`SKILL.md`), das dem Agenten beibringt, ein Tool zu
> nutzen — genau unser Fall. Ein *Plugin* wäre eine System-Erweiterung, die neue
> Tools/Channels hinzufügt (brauchen wir nicht).

## Rezept-Format

Die bestehenden Dateien sind regelmäßiges Markdown — ein Standard-Renderer reicht
für den Großteil. Aufbau jeder Datei:

1. `# Titel` (H1, oft mit Emoji am Anfang oder Ende)
2. **Metadaten-Block:** `**Key:** Value`-Zeilen mit Hard-Break (2 Spaces),
   teils pipe-getrennt. Keys variieren stark (Quelle, Link, Kategorie, Datum,
   Zubereitungszeit, Wartezeit, Menge, Portionen, Schwierigkeit, Kalorien,
   Bewertung …).
3. Optionaler `---` Trenner
4. `## Zutaten` — optional `### Für X`-Untergruppen, `-`-Listen (teils **fett**)
5. `## Zubereitung` — nummerierte Liste (teils mit **fetten** Leadwörtern)
6. Optional: `## Servieren`, Tipps, Aufbewahrung, Quelle am Ende

### Rendering-Hinweise

- Die nummerierte `## Zubereitung`-Liste mappt 1:1 auf je eine Schritt-Karte;
  `## Zutaten` wird die Zutaten-Karte. Sauber für den Karten-Blätter-Modus.
- Metadaten-Block ist das einzig Unstrukturierte. Optionales Parsing per Regex
  `^\*\*(.+?):\*\*\s*(.+)$` → Badges. Keine strikte Feld-Extraktion erzwingen,
  da Keys inkonsistent.
- Führendes Emoji im Titel als Karten-Icon nutzen.
- **Bei Bedarf vereinheitlichen:** Format kann als festes Schema definiert und
  Gaston als Ablageregel vorgegeben werden — dann wird das Rendering noch simpler.

## Betrieb

- **Stack:** Python 3 + `aiohttp` (auf clawdpi1 bereits system-weit vorhanden,
  keine zusätzlichen Abhängigkeiten, kein venv nötig).
- **Start:** `python3 server.py` (im Projektverzeichnis).
- **Env-Variablen:**
  - `TABLETVIEWER_PORT` — Port (Default `8080`)
  - `TABLETVIEWER_RECIPES_DIR` — Rezept-Verzeichnis (Default
    `/home/pi/.openclaw/workspace/rezepte`)
- **Als Dienst (systemd --user):** `systemd/tabletviewer.service` nach
  `~/.config/systemd/user/` kopieren, dann
  `systemctl --user enable --now tabletviewer`. Damit der Dienst ohne Login /
  nach Reboot läuft: `sudo loginctl enable-linger $USER`.
- **Laufzeit-Zustand:** wird in `state.json` gehalten (gitignored).
- **Gaston-/Agent-Steuerung:** als OpenClaw-Skill `skills/tabletviewer/SKILL.md`
  (`curl` gegen `/api/list`, `/api/active`) — siehe Abschnitt „OpenClaw-Anbindung".
- **Visual-QA:** `tools/shot.py` (Python-venv `.venv` + Playwright + System-
  Chromium) rendert die laufende Seite headless als PNG nach `shots/`, um Layout
  in Quer-/Hochformat ohne das echte Tablet zu beurteilen. Bedarf einmalig
  `apt install chromium fonts-noto-color-emoji` und `pip install playwright`.

## Flur-Tablet (Kalender-Overlay über Home Assistant)

Zweites Gerät: Fire HD 10 (5. Gen) im Flur, **1280×800 quer**, WallPanel. Die
Seite `GET /flur` zeigt **Home Assistant als Vollbild-iframe** und legt bei
Bedarf den **Familienkalender** darüber. Ein Tap blendet das Overlay aus, und HA
ist sofort bedienbar, ohne neu zu laden. Oben mittig gibt es einen kleinen
Knopf „Termine", um den Kalender von Hand zu holen.

Voraussetzung in HA: `http: use_x_frame_options: false` (danach ein **voller**
Neustart, der Schnellstart reicht nicht).

**Rollen:** Node-RED entscheidet, *wann* angezeigt wird (Bewegung, Uhrzeit),
das Abrufskript liefert die *Fakten*, der Server baut die Darstellung.
Gaston-Anmerkungen (`hinweis`, `wichtig`, `vorab`, `melden`) kommen aus dem
Gaston-Block der Terminbeschreibung (siehe unten).

| Route | Zweck |
|---|---|
| `GET /flur` | Wrapper-Seite (HA-iframe + Overlay) |
| `GET /api/flur/state` | Zustand inkl. fertig aufbereitetem Kalender; Polling-Fallback. `seiten: {ws, poll_vor_s, aktiv}` sagt, ob das Tablet `/flur` gerade zeigt (Gesundheitscheck für Node-RED; die Seite selbst pollt mit `?von=seite`) |
| `WS /ws/flur` | Push an die Flur-Seite |
| `POST /api/flur/events` | Termine vom Abrufskript `{"events":[…], "stand":ISO, "quelle":…}` |
| `POST /api/flur/show` | `{"view":"kalender", "fokus":"heute"\|"morgen"\|"auto", "seconds":900, "skip_if_seen_within_s":0}` → `{"shown":bool,…}`; `seconds:0` = bis Tap; `fokus:"auto"` = ab 18 Uhr „morgen" (`TABLETVIEWER_FLUR_MORGEN_AB`), so arbeitet auch der Knopf „Termine" |
| `POST /api/flur/hide` | Overlay aus; `{"grund":"tap"}` kommt vom Tablet und setzt `gesehen_um` |
| `POST /api/flur/reload` | `{"ziel":"seite"\|"ha"}` lädt die Seite bzw. nur das HA-iframe neu |
| `GET /api/flur/meldungen` | Diagnose der Handy-Meldungen: `geplant` (anstehend) und `verschickt` (je Person `ok`, `versuche`, `fehler`) |

Event-Format (vom Abrufskript): `id`, `titel`, `ort`, `beschreibung`,
`start`/`ende` (ISO mit Zeit oder `YYYY-MM-DD` für ganztägig, Ende exklusiv),
optional `hinweis`, `wichtig`, `vorab` (`[{ab, text}]`: ab dem Datum unter dem Fokustag gezeigt),
`melden` (`{an: [namen], um: "H:MM"|null}`).
Diese kommen aus dem **Gaston-Block** am Ende der Terminbeschreibung (`— Gaston —`, `📌 …`, `📌 ab JJJJ-MM-TT: …`, `❗`, `🔔 an: jochen, maja [(7:15)]`), den Gaston mit dem Skill `familienkalender` pflegt. Das Abrufskript wertet ihn aus und schneidet ihn aus der Beschreibung.

**Handy-Meldungen (🔔):** Gaston legt nur fest, *wer* gemeldet wird; *wann*
rechnet der Server aus den Fakten, eine verschobene Abfahrt wandert also mit:
mit `📌 Abfahrt H:MM` 15 min vor der Abfahrt (hohe Priorität), sonst 60 min vor
Beginn, mit fester Uhrzeit `(7:15)` dann am Termintag; ganztägig ohne Uhrzeit
gar nicht. Alle 30 s wird geprüft; eine verpasste Meldung (Neustart) wird bis
20 min später nachgeholt, aber nie nach Terminbeginn. Pro Person höchstens 3
Versuche, verschickte Meldungen stehen in `flur_state.json` (kein Doppelversand
nach Neustart). Verschickt wird über den Befehl in
`TABLETVIEWER_FLUR_MELDEN_CMD` (aufgerufen als `<cmd> <person> --titel … --text …
--tag … [--wichtig]`, erwartet JSON mit `alle_ok`), hier `kalender.py
benachrichtige` aus dem OpenClaw-Workspace: der kennt die Zuordnung Person →
HA-Instanz → `notify.mobile_app_*`. Ohne die Variable bleiben Meldungen aus.
Scheitert die Meldung an jemanden auch beim 3. Versuch, schickt der Server
einmal eine Sammelnachricht über `TABLETVIEWER_FLUR_RUECKFALL_CMD` (aufgerufen
als `<cmd> --text …`), hier `kalender.py hauskanal` → Telegram-Hauskanal.
Beide Variablen stehen in `~/.config/tabletviewer/tabletviewer.env` (von
`tabletviewer.service` gelesen, nicht im Repo).

Darstellung: oben groß der Fokustag („Heute" bzw. abends „Morgen"; vergangene
Termine verblasst, laufende grün), darunter die nächsten 6 Tage als
Wochenleiste, unten „Demnächst" bis 6 Wochen (max. 10). Ist der Kalenderstand
älter als 3 h, steht das sichtbar in Orange da.

**Kalenderabruf:** `tools/flur_kalender.py` (venv `.venv` mit `icalendar`,
`recurring-ical-events`) liest die geheime iCal-Adresse aus
`~/.config/tabletviewer/flur.env` (`FLUR_ICAL_URL=…`, Rechte 600, **nicht im
Repo**) und schickt die nächsten 45 Tage. Läuft alle 15 min über
`systemd/flur-kalender.{service,timer}`:

```bash
python3 -m venv .venv && .venv/bin/pip install icalendar recurring-ical-events
cp systemd/flur-kalender.* ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now flur-kalender.timer
```

Env: `TABLETVIEWER_FLUR_HA_URL` (Default `http://192.168.7.2:8123/lovelace/default_view`),
`TABLETVIEWER_FLUR_TOKEN` (optional, Bearer für die schreibenden Routen),
`TABLETVIEWER_FLUR_MELDEN_CMD`, `TABLETVIEWER_FLUR_RUECKFALL_CMD` (Handy-Meldungen, siehe oben),
`TABLETVIEWER_TZ` (Default `Europe/Berlin`).
