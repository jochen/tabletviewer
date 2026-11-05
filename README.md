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
- **"Active recipe" mode:** paged cards in a fixed order:
  1. **Title page** — large emoji icon (or a future cover image), recipe title,
     metadata badges. Keeps the meta lines off the ingredients card.
  2. **Ingredients** — across multiple pages if needed (group-aware: headings are
     never orphaned, they repeat on the continuation page).
  3. **Steps** — **height-based auto-fill:** as many (short) steps per card as
     comfortably fit; long steps may stand alone. Recomputed on rotation
     (measured client-side).
  Navigation via two large left/right tap zones (next/back), NO free scrolling
  (slips while cooking with greasy fingers). Plus a **back button** to the
  selection list; from page 2 on, the recipe title shows small in the header.
- **Display sleep / wake:** WallPanel runs with **"Prevent Sleep" on** — the
  display is never actually turned off (otherwise the Fire keyguard with its
  swipe-to-unlock appears, which Android 5.1 won't reliably dismiss). The
  **dimming is done by the web app itself**: after inactivity a black overlay
  fades in (saves burn-in/power), and the **first tap only wakes** — it does not
  navigate. A Gaston push (jump to recipe X / new selection) wakes the screen
  too. Timeout: `DIM_AFTER_MS` in `static/app.js`.

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

## OpenClaw integration (Gaston → viewer)

**Decision:** the viewer is a **standalone small web service on clawdpi1** with a
plain HTTP control API — **not an OpenClaw plugin**. This follows the exact
pattern Gaston already uses to drive other devices (cf. `TOOLS_WLED.md`,
`TV-SKRIPT.md` in the workspace): a `TOOLS_*.md` documents `curl` commands that
Gaston runs via its shell tool.

Rationale against a plugin (the `openclaw-honcho` path): TypeScript + build +
allow-list + plugin maintenance would be overkill and against "keep it simple".
The viewer stays fully decoupled (separately startable/testable, its own systemd
service). The plugin path (native structured tool calls) remains a possible later
refinement.

### Viewer control API

Own service on a free port (proposed **8080**), NOT via the OpenClaw gateway (18789).

| Route | Purpose |
|---|---|
| `GET /` | the WallPanel UI (static HTML/CSS/JS) |
| `GET /api/state` | current state (list + active recipe); also polling fallback |
| `POST /api/list` `{"recipes":["butter-chicken",…]}` | set curated tiles |
| `POST /api/active` `{"recipe":"butter-chicken"}` | "jump to recipe X now" |
| `WS /ws` | tablet subscribes, server pushes state changes |
| `POST /api/timer` | kitchen-timer sink (see below), for the voice assistant only |
| `POST /api/timer/klingelt` `{"id":…}` | the tablet reports: ringing has started |
| `POST /api/reload` | all connected tablets reload the page (after an update) |

### Kitchen timer

The voice assistant ([openclaw-voice-assistant](https://github.com/jochen/openclaw-voice-assistant),
contract `TIMER_INTERFACE.md`) owns the timers and sends their whole state to
`POST /api/timer`. The viewer shows them bottom right, **above everything**,
including the dim overlay. Running timers are dark; expired ones are red with
a minus sign ("Nudel −2:30"); a ringing one blinks. The tablet counts the
remaining time on its own monotonic clock from the moment of receipt, so its
wall clock is never used.

Ringing uses **WebAudio only**: on the Fire 7 the WebView always blocks
`<audio>.play()` without a user gesture, while WebAudio plays even after
36 minutes with the screen dimmed (measured 2026-10-10). One ring is four
2.5 kHz square-wave beeps; a 440 Hz sine was too quiet. Once ringing has
started, the tablet reports back. Only then does `/api/timer` answer the
assistant with `"klingelt": [id]`, waiting at most 3 s for it. Without that
report the assistant rings on its own speaker.

Optional `TABLETVIEWER_TIMER_TOKEN`: if set, the assistant must send
`Authorization: Bearer <token>`.

### Agent instruction — as an OpenClaw skill

The control is packaged as an **official OpenClaw skill**:
`skills/tabletviewer/SKILL.md`. It teaches the agent the device and the `curl`
commands, e.g.:

```bash
curl -s -X POST http://localhost:8080/api/active -d '{"recipe":"butter-chicken"}'
```

**Install (also for other OpenClaw instances):** copy the `skills/tabletviewer/`
folder into `~/.openclaw/workspace/skills/` — the skill loads automatically (no
allowlist entry needed). Verify with `openclaw skills list`; if needed
`openclaw gateway restart` or `/new`.

The agent must know the constraints so it curates sensibly: which recipes go on
the selection list (3–6, from the meal plan), how to make a recipe active, and
the **recipe format** (see below), which we can standardize on demand.

> **Skill vs. plugin (OpenClaw terminology):** a *skill* is a workspace
> instruction set (`SKILL.md`) that teaches the agent to use a tool — exactly our
> case. A *plugin* would be a system extension that adds new tools/channels (not
> needed here).

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

## Running

- **Stack:** Python 3 + `aiohttp` (already present system-wide on clawdpi1, no
  extra dependencies, no venv needed).
- **Start:** `python3 server.py` (from the project directory).
- **Env vars:**
  - `TABLETVIEWER_PORT` — port (default `8080`)
  - `TABLETVIEWER_RECIPES_DIR` — recipe directory (default
    `/home/pi/.openclaw/workspace/rezepte`)
- **As a service (systemd --user):** copy `systemd/tabletviewer.service` to
  `~/.config/systemd/user/`, then `systemctl --user enable --now tabletviewer`.
  To keep it running without login / after reboot: `sudo loginctl enable-linger $USER`.
- **Runtime state:** kept in `state.json` (gitignored).
- **Agent control:** OpenClaw skill `skills/tabletviewer/SKILL.md` (`curl` against
  `/api/list`, `/api/active`) — see the "OpenClaw integration" section.
- **Visual QA:** `tools/shot.py` (Python venv `.venv` + Playwright + system
  Chromium) renders the running page headless to PNGs in `shots/`, to judge the
  layout in portrait/landscape without the real tablet. One-time setup:
  `apt install chromium fonts-noto-color-emoji` and `pip install playwright`.

## Hallway tablet (calendar overlay on top of Home Assistant)

Second device: Fire HD 10 (5th gen) in the hallway, **1280×800 landscape**,
WallPanel. The page `GET /flur` shows **Home Assistant as a full-screen
iframe** and lays the **family calendar** over it when needed. A tap hides the
overlay and HA is usable immediately, without reloading. A small "Termine"
button at the top centre brings the calendar back by hand.

Prerequisite in HA: `http: use_x_frame_options: false` (followed by a **full**
restart; a quick reload is not enough).

**Roles:** Node-RED decides *when* to show it (motion, time of day), the fetch
script supplies the *facts*, the server builds the view. Gaston annotations
(`hinweis`, `wichtig`, `vorab`, `melden`) come from the Gaston block in the
event description (see below).

| Route | Purpose |
|---|---|
| `GET /flur` | wrapper page (HA iframe + overlay) |
| `GET /api/flur/state` | state including the ready-made calendar; polling fallback. `seiten: {ws, poll_vor_s, aktiv}` tells whether the tablet is showing `/flur` right now (health check for Node-RED; the page itself polls with `?von=seite`) |
| `WS /ws/flur` | push to the hallway page |
| `POST /api/flur/events` | events from the fetch script `{"events":[…], "stand":ISO, "quelle":…}` |
| `POST /api/flur/show` | `{"view":"kalender", "fokus":"heute"\|"morgen"\|"auto", "seconds":900, "skip_if_seen_within_s":0}` → `{"shown":bool,…}`; `seconds:0` = until tapped; `fokus:"auto"` = "morgen" from 18:00 (`TABLETVIEWER_FLUR_MORGEN_AB`), also used by the "Termine" button |
| `POST /api/flur/hide` | overlay off; `{"grund":"tap"}` comes from the tablet and sets `gesehen_um` |
| `POST /api/flur/reload` | `{"ziel":"seite"\|"ha"}` reloads the page or just the HA iframe |
| `GET /api/flur/meldungen` | phone notification diagnostics: `geplant` (upcoming) and `verschickt` (per person `ok`, `versuche`, `fehler`) |

Event format (from the fetch script): `id`, `titel`, `ort`, `beschreibung`,
`start`/`ende` (ISO with time, or `YYYY-MM-DD` for all-day, end exclusive),
optional `hinweis`, `wichtig`, `vorab` (`[{ab, text}]`: shown under the focus day from that date on),
`melden` (`{an: [names], um: "H:MM"|null}`).
These come from the **Gaston block** at the end of the event description (`— Gaston —`, `📌 …`, `📌 ab YYYY-MM-DD: …`, `❗`, `🔔 an: jochen, maja [(7:15)]`), which Gaston maintains with the `familienkalender` skill. The fetch script parses it and strips it from the description.

**Phone notifications (🔔):** Gaston only decides *who* is notified; the server
works out *when* from the facts, so a moved departure moves the notification
too: with `📌 Abfahrt H:MM` 15 min before departure (high priority), otherwise
60 min before the start, with a fixed time `(7:15)` at that time on the event
day; all-day events without a time get none. Checked every 30 s; a missed
notification (restart) is caught up to 20 min late, but never after the event
has started. At most 3 attempts per person; sent notifications are kept in
`flur_state.json` (no duplicates after a restart). Sending uses the command in
`TABLETVIEWER_FLUR_MELDEN_CMD` (called as `<cmd> <person> --titel … --text …
--tag … [--wichtig]`, expects JSON with `alle_ok`), here `kalender.py
benachrichtige` from the OpenClaw workspace, which knows the mapping person →
HA instance → `notify.mobile_app_*`. Without the variable, no notifications
are sent. It lives in `~/.config/tabletviewer/tabletviewer.env` (read by
`tabletviewer.service`, not in the repo).

Layout: the focus day large at the top ("Heute" = today, or "Morgen" =
tomorrow in the evening; past events faded, running ones green), below it the
next 6 days as a week strip, and at the bottom "Demnächst" (upcoming) up to
6 weeks ahead (max. 10). If the calendar data is older than 3 h, this is shown
in orange.

**Calendar fetch:** `tools/flur_kalender.py` (venv `.venv` with `icalendar`,
`recurring-ical-events`) reads the secret iCal address from
`~/.config/tabletviewer/flur.env` (`FLUR_ICAL_URL=…`, mode 600, **not in the
repo**) and posts the next 45 days, every 15 min via
`systemd/flur-kalender.{service,timer}`:

```bash
python3 -m venv .venv && .venv/bin/pip install icalendar recurring-ical-events
cp systemd/flur-kalender.* ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now flur-kalender.timer
```

Env: `TABLETVIEWER_FLUR_HA_URL` (default `http://192.168.7.2:8123/lovelace/default_view`),
`TABLETVIEWER_FLUR_TOKEN` (optional bearer token for the write routes),
`TABLETVIEWER_FLUR_MELDEN_CMD` (phone notifications, see above),
`TABLETVIEWER_TZ` (default `Europe/Berlin`).
