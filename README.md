# tabletviewer

Rezept-Anzeige für ein wandmontiertes Küchen-Tablet, bedient/gespeist über
clawdpi1 (OpenClaw/Gaston). Zeigt eine von Gaston kuratierte Auswahl der
Markdown-Rezepte aus dem OpenClaw-Workspace an.

> Voller Konzeptstand auch im MemPalace: Wing `clawdpi1-home-pi`, Room
> `decisions`. Diese README ist die lokale Arbeitsfassung.

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
- **Modus „Aktives Rezept":** durchblätterbare Karten. **Zutaten = eine Karte,
  danach je ein Zubereitungsschritt pro Karte.** Navigation über zwei große
  Tap-Zonen links/rechts (vor/zurück), KEIN freies Scrollen (verrutscht beim
  Kochen mit fettigen Fingern). Plus **Zurück-Button** zurück zur Auswahlliste.
  Begründung 7-Zoll: Schrift groß, daher passt ein Rezept nie auf einen Screen.
- **Display-Wecken / kein Auto-Dimmen:** ist **WallPanel-Konfiguration**, NICHT
  Sache der Web-App. Beim Kochen Screen-Timeout aus / lang stellen.

## Architektur

- Schlanke Web-App auf **clawdpi1** (Pi mit OpenClaw/Gaston). NICHT auf grogu
  (Internet-Server, ausdrücklich unerwünscht). Alles lokal im selben WLAN.
- **Direkte Verbindung** Tab ↔ clawdpi1. **Kein MQTT** (bleibt der Haussteuerung
  vorbehalten). Kein Honcho (abgeschafft).
- **Push von Gaston ans Tab:** Gaston sagt „spring jetzt auf Rezept X" bzw. setzt
  die kuratierte Auswahlliste. WebSocket bevorzugt, Polling als simpler Fallback.
- Petras bestehende Browser-Extension schickt Rezepte weiterhin an Gaston. Das
  Tab ist Anzeige + kuratierte Auswahl, **keine eigene Eingabe**.

## Datenquelle

- **Single source of truth:** die Markdown-Rezepte in
  `/home/pi/.openclaw/workspace/rezepte/` (Gastons feste Ablageregel: immer in
  `rezepte/`, sprechende Dateinamen). Kein neues Datenmodell.
- **Rezept-Inhalt** wird aus diesen Dateien gerendert; die **Auswahl** (welche
  Kacheln in der Übersicht) kommt von Gaston, nicht aus einem Verzeichnis-Scan.

## Gastons Rolle (Tool-/Ablageanweisung nötig)

Gaston muss über die Gegebenheiten Bescheid wissen, damit er sinnvoll kuratiert
und anzeigt:

- Welche Rezepte gerade auf die Auswahlliste sollen (3–6, aus dem Wochenplan).
- Wie er „zeige Rezept X jetzt aktiv" auslöst.
- Das **Rezept-Format** (siehe unten) — das wir bei Bedarf vereinheitlichen und
  Gaston als Schreib-/Ablageregel vorgeben können.

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
