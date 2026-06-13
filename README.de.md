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
- **Display-Wecken / kein Auto-Dimmen:** ist **WallPanel-Konfiguration**, NICHT
  Sache der Web-App. Beim Kochen Screen-Timeout aus / lang stellen.

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
