# TOOLS_TABLETVIEWER.md - Küchen-Tablet Rezeptanzeige steuern

Diese Datei beschreibt, wie du (Gaston) das wandmontierte Küchen-Tablet mit
Rezepten bespielst. Das Tablet zeigt eine von dir kuratierte Auswahl an und
kann auf ein bestimmtes Rezept springen.

> Kopiere diese Datei bei Inbetriebnahme nach `/home/pi/.openclaw/workspace/`,
> damit sie zur Startsequenz gehört. Im Repo liegt sie unter `docs/` als Quelle.

---

## 🍽 Was ist das?

- **Gerät:** altes Amazon Fire 7 (Gen.5, Android 5.1) an der Küchenwand, läuft mit
  der WallPanel-App im Kiosk-Modus.
- **Dienst:** `tabletviewer` läuft lokal auf clawdpi1 und liefert die Anzeige.
- **Datenquelle:** die Markdown-Rezepte in `/home/pi/.openclaw/workspace/rezepte/`
  (deine gewohnte Ablage). Du musst nichts umschreiben — der Viewer rendert sie.
- **Rolle des Tablets:** reine Anzeige + Auswahl. Es nimmt keine Eingaben entgegen;
  **du** steuerst, was darauf erscheint.

---

## 💡 Wichtige Adresse

| Zweck                 | Adresse                                  |
|-----------------------|------------------------------------------|
| Steuer-API (lokal)    | `http://localhost:8080`                  |
| Aktueller Zustand     | `GET  http://localhost:8080/api/state`   |
| Auswahlliste setzen   | `POST http://localhost:8080/api/list`    |
| Rezept aktiv schalten | `POST http://localhost:8080/api/active`  |

Rezeptnamen sind die **Dateinamen ohne `.md`**, z.B. `butter-chicken`,
`panna-cotta-mit-erdbeersoße`, `saure_zipfel`. Welche es gibt:
`ls /home/pi/.openclaw/workspace/rezepte/`.

---

## 🔧 Befehle (curl)

### 1. Auswahlliste setzen (Default-Ansicht: 3–6 Kacheln)

Kuratiere die nächsten Tage aus dem Essensplan — **nicht** die ganze Bibliothek.

```bash
curl -s -X POST http://localhost:8080/api/list \
  -d '{"recipes":["butter-chicken","naan-brot","ofennudeln-mozzarella"]}'
```

Unbekannte Namen werden mit HTTP 404 abgelehnt (Antwort listet sie unter
`unknown`) — dann Schreibweise/Dateinamen prüfen.

### 2. Direkt ein Rezept aktiv anzeigen

Das Tablet springt sofort in die Karten-Ansicht dieses Rezepts (Zutaten zuerst,
dann Schritt für Schritt).

```bash
curl -s -X POST http://localhost:8080/api/active \
  -d '{"recipe":"butter-chicken"}'
```

### 3. Zurück zur Auswahlliste

```bash
curl -s -X POST http://localhost:8080/api/active -d '{"recipe":null}'
```

### 4. Zustand abfragen (was zeigt das Tablet gerade?)

```bash
curl -s http://localhost:8080/api/state | jq '{active, list: [.list[].name]}'
```

---

## 📝 Hinweise

- Das Display-Wecken / Nicht-Dimmen beim Kochen ist **WallPanel-Sache** (am
  Tablet eingestellt), nicht über diese API steuerbar.
- Familienmitglieder blättern am Tablet selbst durch die Karten (Tap links/rechts)
  und kommen über den Zurück-Button zur Liste — dafür musst du nichts tun.
- Petras Browser-Extension schickt neue Rezepte weiterhin an dich; du legst sie
  wie gewohnt in `rezepte/` ab, dann tauchen sie hier auf.
