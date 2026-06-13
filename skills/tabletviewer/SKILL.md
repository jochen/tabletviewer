---
name: tabletviewer
description: Kuechen-Tablet Rezeptanzeige steuern - kuratierte Auswahlliste setzen und ein Rezept aktiv anzeigen (HTTP/curl).
version: 0.1.0
metadata:
  openclaw:
    emoji: "🍽"
    requires:
      bins:
        - curl
    envVars:
      - name: TABLETVIEWER_URL
        required: false
        description: Basis-URL des Viewers (Default http://localhost:8080).
---

# tabletviewer steuern

Du bespielst ein wandmontiertes Kuechen-Tablet (WallPanel-WebView) mit Rezepten.
Das Tablet ist reine Anzeige + Auswahl — **du** steuerst, was darauf erscheint.

## Voraussetzung

Der `tabletviewer`-Dienst muss laufen und erreichbar sein (lokaler Dienst auf
demselben Pi/WLAN wie das Tablet, siehe `tabletviewer`-Repo). Basis-URL:
`$TABLETVIEWER_URL` falls gesetzt, sonst `http://localhost:8080`.

Rezepte sind die Markdown-Dateien im Rezept-Verzeichnis des Viewers (per Default
`~/.openclaw/workspace/rezepte/`). Rezeptnamen = **Dateiname ohne `.md`**
(z.B. `butter-chicken`). Verfuegbare Namen: `ls` im Rezept-Verzeichnis.

## Was du tun kannst (via `exec`-Tool / curl)

### 1. Auswahlliste setzen (Default-Ansicht: 3-6 grosse Kacheln)

Kuratiere die naechsten Tage aus dem Essensplan — **nicht** die ganze Bibliothek.

```bash
curl -s -X POST "${TABLETVIEWER_URL:-http://localhost:8080}/api/list" \
  -d '{"recipes":["butter-chicken","naan-brot","ofennudeln-mozzarella"]}'
```

Unbekannte Namen werden mit HTTP 404 abgelehnt (Antwort listet sie unter
`unknown`) — dann Schreibweise/Dateinamen pruefen.

### 2. Direkt ein Rezept aktiv anzeigen

Das Tablet springt sofort in die Karten-Ansicht (Titelseite, dann Zutaten, dann
Schritt fuer Schritt).

```bash
curl -s -X POST "${TABLETVIEWER_URL:-http://localhost:8080}/api/active" \
  -d '{"recipe":"butter-chicken"}'
```

### 3. Zurueck zur Auswahlliste

```bash
curl -s -X POST "${TABLETVIEWER_URL:-http://localhost:8080}/api/active" -d '{"recipe":null}'
```

### 4. Zustand abfragen (was zeigt das Tablet gerade?)

```bash
curl -s "${TABLETVIEWER_URL:-http://localhost:8080}/api/state"
```

## Hinweise

- Display-Wecken / Nicht-Dimmen beim Kochen ist **WallPanel-Sache** (am Tablet
  eingestellt), nicht ueber diese API steuerbar.
- Die Bewohner blaettern am Tablet selbst durch die Karten (Tap links/rechts) und
  kommen ueber den Zurueck-Button zur Liste — dafuer musst du nichts tun.
- Optionales Titelbild: liegt im Rezept-Verzeichnis unter
  `images/<name>.{jpg,png,webp}` ein Bild, zeigt die Titelseite es automatisch.
