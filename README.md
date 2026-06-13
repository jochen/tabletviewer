# tabletviewer

Recipe display for a wall-mounted kitchen tablet, driven by clawdpi1
(OpenClaw/Gaston). Shows a Gaston-curated selection of the Markdown recipes from
the OpenClaw workspace.

> The German `README.de.md` is the primary, authoritative version; this English
> `README.md` is kept in sync from it (GitHub default). Code, commit and PR
> language: English.

## Target platform

- **Device:** Amazon Fire 7, 5th gen (2015), **Android 5.1**, resolution **1024×600**.
- **Display:** WallPanel WebView (already installed). Chosen over Fully Kiosk
  because Fully dropped Android 5 support. Maintained fork:
  `TheTimeWalker/wallpanel-android` (wallpanel.xyz) if a reinstall is needed.
- **Main risk:** the **old WebView component** on Android 5.1 does not render
  modern CSS/JS reliably. → The web app MUST be deliberately plain: simple
  HTML/CSS, no modern framework trickery.
- Optimized for 1024×600, **large type (readable without glasses)**, fat-finger friendly.

## Interaction model (two modes, one surface)

- **"Selection list" mode (default/idle):** a few large tiles (3–6),
  **curated by Gaston** from the meal plan for the next days — deliberately NOT
  the whole recipe library. Tapping → "Active recipe".
- **"Active recipe" mode:** swipeable cards. **Ingredients = one card, then one
  preparation step per card.** Navigation via two large left/right tap zones
  (next/back), NO free scrolling (slips while cooking with greasy fingers). Plus
  a **back button** to return to the selection list. Rationale for 7": type stays
  large, so a recipe never fits on a single screen.
- **Display wake / no auto-dimming:** this is **WallPanel configuration**, NOT
  the web app's job. Turn screen timeout off / long while cooking.

## Architecture

- Lean web app on **clawdpi1** (the Pi running OpenClaw/Gaston). NOT on grogu
  (internet server, explicitly unwanted). Everything local on the same Wi-Fi.
- **Direct connection** tablet ↔ clawdpi1. **No MQTT** (reserved for home
  automation). No Honcho (abandoned).
- **Push from Gaston to the tablet:** Gaston says "jump to recipe X now" or sets
  the curated selection list. **Transport: WebSocket** (the tablet keeps the
  connection open), **polling as an automatic fallback** if the Android 5.1
  WebView struggles with WS.
- Petra's existing browser extension keeps sending recipes to Gaston. The tablet
  is display + curated selection, **no input of its own**.

## Data source

- **Single source of truth:** the Markdown recipes in
  `/home/pi/.openclaw/workspace/rezepte/` (Gaston's fixed filing rule: always in
  `rezepte/`, descriptive file names). No new data model.
- **Recipe content** is rendered from those files; the **selection** (which tiles
  appear in the overview) comes from Gaston, not from a directory scan.

## Gaston's role (needs a tool/filing instruction)

Gaston must know the constraints so it curates and displays sensibly:

- Which recipes belong on the selection list right now (3–6, from the meal plan).
- How it triggers "show recipe X as active now".
- The **recipe format** (see below) — which we can standardize on demand and give
  Gaston as a writing/filing rule.

## Recipe format

The existing files are regular Markdown — a standard renderer covers most of it.
Layout of each file:

1. `# Title` (H1, often with an emoji at the start or end)
2. **Metadata block:** `**Key:** Value` lines with hard breaks (2 spaces),
   sometimes pipe-separated. Keys vary a lot (source, link, category, date,
   prep time, resting time, quantity, servings, difficulty, calories, rating …).
3. Optional `---` divider
4. `## Ingredients` — optional `### For X` subgroups, `-` lists (sometimes **bold**)
5. `## Preparation` — numbered list (sometimes with **bold** lead words)
6. Optional: `## Serving` / tips / storage / source at the end

### Rendering notes

- The numbered `## Preparation` list maps 1:1 onto one step card each;
  `## Ingredients` becomes the ingredients card. Clean for the card-swipe mode.
- The metadata block is the only unstructured part. Optional parsing via regex
  `^\*\*(.+?):\*\*\s*(.+)$` → badges. Do not force strict field extraction, as
  keys are inconsistent.
- Use the leading title emoji as the card icon.
- **Standardize on demand:** the format can be fixed as a schema and given to
  Gaston as a filing rule, making rendering even simpler. **Decision:** start
  with the as-is state (tolerant renderer over the existing 9 files); impose a
  schema only once the viewer exists and we see what breaks.
