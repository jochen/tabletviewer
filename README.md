# tabletviewer

Rezept-Anzeige für ein wandmontiertes Tablet (WallPanel/WebView), bedient über
clawdpi1. Zeigt die Markdown-Rezepte aus dem OpenClaw-Workspace als Karten an.

## Konzept

- **Gerät:** wandmontiertes Tablet, Anzeige via WallPanel (WebView, eingeschränkter Browser)
- **Architektur:** Web-App läuft direkt auf **clawdpi1**, direkte Verbindung — kein MQTT, kein Honcho
- **Datenquelle:** Markdown-Rezepte unter `/home/pi/.openclaw/workspace/rezepte/`
- **Zwei Bedienmodi:**
  - **Übersicht / Kachel-Modus** — Rezepte als Karten (Titel + Emoji-Icon + 1–2 Meta-Badges)
  - **Detail / Koch-Modus** — volles, gerendertes Rezept

## Rezept-Format

Die Dateien sind regelmäßiges Markdown — ein Standard-Renderer
(marked.js / markdown-it) deckt den Großteil ab. **Keine Frontmatter-Migration
nötig**, der Viewer arbeitet mit dem Ist-Zustand.

Aufbau jeder Datei:

1. `# Titel` (H1, oft mit Emoji am Anfang oder Ende)
2. **Metadaten-Block:** `**Key:** Value`-Zeilen mit Hard-Break (2 Spaces),
   manchmal pipe-getrennt. Keys variieren stark (Quelle, Link, Kategorie,
   Datum, Zubereitungszeit, Wartezeit, Menge, Portionen, Schwierigkeit,
   Kalorien, Bewertung …).
3. Optionaler `---` Trenner
4. `## Zutaten` — optional `### Für X`-Untergruppen, `-`-Listen (teils **fett**)
5. `## Zubereitung` — nummerierte Liste (teils mit **fetten** Leadwörtern)
6. Optional: `## Servieren`, Tipps, Aufbewahrung, Quelle am Ende

### Rendering-Hinweise

- Metadaten-Block ist das einzig Unstrukturierte. **Optionales** Parsing per
  Regex `^\*\*(.+?):\*\*\s*(.+)$` → Badges/Chips. Keine strikte Feld-Extraktion
  erzwingen, da die Keys inkonsistent sind.
- Führendes Emoji im Titel als Karten-Icon nutzen.
