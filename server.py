"""tabletviewer - aiohttp-Server fuer das Küchen-Tablet.

Siehe README.de.md fuer das Gesamtkonzept. Kurzfassung:

- Statische ES5-UI unter static/ wird unter GET / ausgeliefert.
- In-Memory-State (Liste kuratierter Rezepte + aktives Rezept + Karten-
  Index), persistiert nach state.json im Projektordner.
- Markdown-Rezepte aus RECIPES_DIR werden server-seitig geparst (parser.py)
  und als simple JSON-Struktur an die UI gegeben.
- WebSocket /ws pusht Zustandsaenderungen an alle verbundenen Clients;
  Polling von /api/state ist der Fallback fuer alte WebViews.
- POST /api/timer ist eine Kuechentimer-Senke nach dem Vertrag
  TIMER_INTERFACE.md des Sprachassistenten (openclaw_voice_assist): der
  Assistent haelt die Uhr, hier wird nur angezeigt und geklingelt.
"""

import asyncio
import json
import logging
import os
import re
import time

from aiohttp import web, WSMsgType

from parser import parse_recipe

# --- Konfiguration (per Umgebungsvariable ueberschreibbar) ---
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

RECIPES_DIR = os.environ.get("TABLETVIEWER_RECIPES_DIR", "/home/pi/.openclaw/workspace/rezepte")
PORT = int(os.environ.get("TABLETVIEWER_PORT", "8080"))
STATE_FILE = os.path.join(BASE_DIR, "state.json")
STATIC_DIR = os.path.join(BASE_DIR, "static")
# Optional: wenn gesetzt, muss der Assistent "Authorization: Bearer <token>" senden.
TIMER_TOKEN = os.environ.get("TABLETVIEWER_TIMER_TOKEN", "")
# So lange wartet POST /api/timer bei einem frisch abgelaufenen Timer darauf,
# dass ein Tablet "klingelt" meldet (Vertrag: hoechstens ~3 s).
TIMER_ACK_WAIT_SEC = 3.0

# Optionale Titelbilder: liegt unter RECIPES_DIR/images/<name>.<ext> ein Bild,
# zeigt die Titelseite es an. Existiert noch nicht — der Slot ist vorbereitet,
# damit der OpenClaw-Bot (Gaston) spaeter nur Bilder dort ablegen muss.
IMAGES_DIR = os.path.join(RECIPES_DIR, "images")
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".gif")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tabletviewer")


# --- State ---
# { "list": [name, ...], "active": name|None, "card": int }
DEFAULT_STATE = {"list": [], "active": None, "card": 0}


def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            state = dict(DEFAULT_STATE)
            state["list"] = data.get("list", []) if isinstance(data.get("list"), list) else []
            active = data.get("active")
            state["active"] = active if isinstance(active, str) else None
            card = data.get("card", 0)
            state["card"] = card if isinstance(card, int) else 0
            return state
        except (OSError, ValueError, json.JSONDecodeError) as e:
            log.warning("Konnte %s nicht laden (%s), starte mit Default-State.", STATE_FILE, e)
    return dict(DEFAULT_STATE)


def save_state(state):
    try:
        tmp_path = STATE_FILE + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, STATE_FILE)
    except OSError as e:
        log.error("Konnte State nicht speichern: %s", e)


# --- Rezept-Helfer ---

def _recipe_path(name):
    """Sicherer Pfad fuer einen Rezeptnamen (ohne .md). Verhindert
    Path-Traversal, erlaubt aber Unicode-Zeichen (z.B. 'ß', 'ö'), da
    mehrere echte Dateinamen solche Zeichen enthalten
    (panna-cotta-mit-erdbeersoße.md)."""
    # Nur den reinen Dateinamen verwenden (keine Pfadtrennzeichen, kein "..").
    safe = os.path.basename(name)
    safe = safe.replace("..", "")
    return os.path.join(RECIPES_DIR, safe + ".md")


def recipe_exists(name):
    return os.path.isfile(_recipe_path(name))


def load_recipe(name):
    """Liest und parst ein Rezept. Gibt None zurueck, falls die Datei
    nicht existiert oder nicht lesbar ist."""
    path = _recipe_path(name)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        log.error("Konnte Rezept %s nicht lesen: %s", name, e)
        return None
    try:
        recipe = parse_recipe(name, text)
    except Exception as e:
        log.error("Parser-Fehler bei Rezept %s: %s", name, e)
        return None
    recipe["image"] = _recipe_image_url(name)
    return recipe


def _recipe_image_url(name):
    """Liefert die URL eines Titelbilds, falls eines unter
    RECIPES_DIR/images/<name>.<ext> existiert, sonst None."""
    safe = os.path.basename(name).replace("..", "")
    for ext in IMAGE_EXTS:
        if os.path.isfile(os.path.join(IMAGES_DIR, safe + ext)):
            return "/recipe-images/" + safe + ext
    return None


def list_entry(name):
    """Liefert {name, title, emoji} fuer eine Listen-Kachel. Bei
    Parser-/Lesefehlern wird ein Fallback-Eintrag mit dem Dateinamen als
    Titel zurueckgegeben, damit ein einzelnes defektes Rezept nicht die
    gesamte Liste zum Absturz bringt."""
    recipe = load_recipe(name)
    if recipe is None:
        return {"name": name, "title": name, "emoji": None}
    return {"name": name, "title": recipe["title"], "emoji": recipe["icon"]}


# --- Globaler Zustand & WebSocket-Clients ---
STATE = load_state()
WS_CLIENTS = set()

# Kuechentimer: letzter Stand vom Assistenten + monotone Empfangszeit. Nicht
# persistiert - der Assistent schickt spaetestens alle 30 s den ganzen Stand.
TIMER = {"stand": None, "empfangen": 0.0}
# id -> True, sobald ein Tablet das Klingeln fuer diesen Ablauf bestaetigt hat
TIMER_KLINGELT = {}
TIMER_ACK_EVENT = asyncio.Event()


def timer_payload():
    """Stand fuer die Clients, Restzeiten auf JETZT umgerechnet (die Tablet-
    Uhr wird nie gebraucht, der Client rechnet ab Empfang weiter)."""
    stand = TIMER["stand"]
    if not stand:
        return None
    vergangen = time.monotonic() - TIMER["empfangen"]
    timer = []
    for t in stand.get("timer", []):
        t = dict(t)
        t["rest_s"] = round(float(t.get("rest_s", 0)) - vergangen, 1)
        # nachlauf_s je Timer (seit 2026-10-10), sonst der globale Wert
        if t["rest_s"] < -float(t.get("nachlauf_s") or stand.get("nachlauf_max_s", 1800)):
            continue
        timer.append(t)
    return {
        "seq": stand.get("seq"),
        "klingel_abstand_s": stand.get("klingel_abstand_s", 5),
        "nachlauf_max_s": stand.get("nachlauf_max_s", 1800),
        "timer": timer,
    }


def build_state_payload():
    """Baut die JSON-Antwort fuer GET /api/state und WS-Broadcasts."""
    payload = {
        "list": [list_entry(name) for name in STATE["list"]],
        "active": STATE["active"],
        "card": STATE["card"],
        "recipe": None,
        "timer": timer_payload(),
    }
    if STATE["active"] is not None:
        payload["recipe"] = load_recipe(STATE["active"])
    return payload


async def broadcast_state():
    payload = build_state_payload()
    text = json.dumps(payload, ensure_ascii=False)
    stale = []
    for ws in WS_CLIENTS:
        try:
            await ws.send_str(text)
        except ConnectionResetError:
            stale.append(ws)
    for ws in stale:
        WS_CLIENTS.discard(ws)


# --- HTTP-Handler ---

async def handle_index(request):
    return web.FileResponse(os.path.join(STATIC_DIR, "index.html"))


async def handle_get_state(request):
    return web.json_response(build_state_payload())


async def handle_post_list(request):
    try:
        data = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"error": "invalid JSON"}, status=400)

    recipes = data.get("recipes")
    if not isinstance(recipes, list):
        return web.json_response({"error": "'recipes' must be a list"}, status=400)

    new_list = []
    unknown = []
    for entry in recipes:
        if not isinstance(entry, str):
            continue
        name = entry[:-3] if entry.endswith(".md") else entry
        name = name.strip()
        if not name:
            continue
        if recipe_exists(name):
            new_list.append(name)
        else:
            unknown.append(name)

    if unknown:
        return web.json_response(
            {"error": "unknown recipes", "unknown": unknown}, status=404
        )

    STATE["list"] = new_list
    save_state(STATE)
    await broadcast_state()
    return web.json_response(build_state_payload())


async def handle_post_active(request):
    try:
        data = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"error": "invalid JSON"}, status=400)

    recipe = data.get("recipe", "__missing__")
    if recipe == "__missing__":
        return web.json_response({"error": "'recipe' is required"}, status=400)

    if recipe is None:
        STATE["active"] = None
        STATE["card"] = 0
        save_state(STATE)
        await broadcast_state()
        return web.json_response(build_state_payload())

    if not isinstance(recipe, str):
        return web.json_response({"error": "'recipe' must be a string or null"}, status=400)

    name = recipe[:-3] if recipe.endswith(".md") else recipe

    if not recipe_exists(name):
        return web.json_response({"error": "recipe not found", "recipe": name}, status=404)

    STATE["active"] = name
    STATE["card"] = 0
    save_state(STATE)
    await broadcast_state()
    return web.json_response(build_state_payload())


async def broadcast_message(obj):
    """Sendet eine Nachricht ausser dem Rezept-State an alle WS-Clients."""
    text = json.dumps(obj, ensure_ascii=False)
    sent = 0
    for ws in list(WS_CLIENTS):
        try:
            await ws.send_str(text)
            sent += 1
        except ConnectionResetError:
            WS_CLIENTS.discard(ws)
    return sent


async def handle_post_reload(request):
    """Laesst alle verbundenen Tablets die Seite neu laden (nach einem
    Update - WallPanel laedt sonst nie selbst neu)."""
    sent = await broadcast_message({"type": "reload"})
    log.info("Reload an %d WS-Client(s) gesendet", sent)
    return web.json_response({"sent_to": sent})


async def handle_ws(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    WS_CLIENTS.add(ws)
    log.info("WS-Client verbunden (%d gesamt)", len(WS_CLIENTS))

    try:
        # Aktuellen State sofort senden.
        await ws.send_str(json.dumps(build_state_payload(), ensure_ascii=False))

        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
            # Der Viewer schreibt nur ueber HTTP (/api/list, /api/active).
            # Einzige WS-Nachricht vom Client: der Heartbeat "ping". Die
            # Protokoll-Pings (heartbeat=30) sieht JavaScript nicht; ohne
            # Antwort erkennt die Seite eine halb offene Verbindung nie
            # (z. B. nach IP-Wechsel des Servers).
            if msg.type == WSMsgType.TEXT and msg.data == "ping":
                await ws.send_str('{"type":"pong"}')
    finally:
        WS_CLIENTS.discard(ws)
        log.info("WS-Client getrennt (%d verbleibend)", len(WS_CLIENTS))

    return ws


def _faellige_ids(stand):
    """Abgelaufen, nicht still und noch nicht bestaetigt."""
    return [t["id"] for t in stand.get("timer", [])
            if float(t.get("rest_s", 1)) <= 0 and not t.get("still")
            and t.get("id") not in TIMER_KLINGELT]


async def handle_post_timer(request):
    """Senke nach TIMER_INTERFACE.md: ganzer Stand rein, an die Tablets
    weiter, Antwort {"anzeigen": n, "klingelt": [ids]}. Ist ein Timer gerade
    abgelaufen, wird bis TIMER_ACK_WAIT_SEC auf die Bestaetigung eines Tablets
    gewartet - sonst klingelt der Assistent selbst."""
    if TIMER_TOKEN and request.headers.get("Authorization") != "Bearer " + TIMER_TOKEN:
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        stand = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"error": "invalid JSON"}, status=400)
    if not isinstance(stand, dict) or stand.get("art") != "kuechentimer":
        return web.json_response({"error": "art must be 'kuechentimer'"}, status=400)

    alt = TIMER["stand"]
    TIMER["stand"] = stand
    TIMER["empfangen"] = time.monotonic()
    # Bestaetigungen vergessen fuer Timer, die weg oder wieder laufend sind
    # (verlaengert = neuer Ablauf, darf wieder klingeln).
    abgelaufen = {t["id"] for t in stand.get("timer", []) if float(t.get("rest_s", 1)) <= 0}
    for tid in list(TIMER_KLINGELT):
        if tid not in abgelaufen:
            del TIMER_KLINGELT[tid]

    if not alt or alt.get("seq") != stand.get("seq"):
        log.info("Timer-Stand seq=%s: %s", stand.get("seq"), ", ".join(
            "%s %+.0fs%s" % (t.get("name") or "-", float(t.get("rest_s", 0)),
                             " still" if t.get("still") else "")
            for t in stand.get("timer", [])) or "(leer)")
    await broadcast_message({"type": "timer", "timer": timer_payload()})

    faellig = _faellige_ids(stand)
    if faellig and WS_CLIENTS:
        frist = time.monotonic() + TIMER_ACK_WAIT_SEC
        while any(tid not in TIMER_KLINGELT for tid in faellig):
            rest = frist - time.monotonic()
            if rest <= 0:
                break
            TIMER_ACK_EVENT.clear()
            try:
                await asyncio.wait_for(TIMER_ACK_EVENT.wait(), rest)
            except asyncio.TimeoutError:
                break
        log.info("Timer abgelaufen %s, Tablet klingelt: %s",
                 faellig, [tid for tid in faellig if tid in TIMER_KLINGELT])
    return web.json_response({
        "anzeigen": len(WS_CLIENTS),
        "klingelt": [tid for tid in TIMER_KLINGELT],
    })


async def handle_post_timer_klingelt(request):
    """Vom Tablet: das Klingeln fuer diese id hat begonnen (WebAudio lief)."""
    try:
        data = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"error": "invalid JSON"}, status=400)
    tid = str(data.get("id", ""))
    if tid:
        TIMER_KLINGELT[tid] = True
        TIMER_ACK_EVENT.set()
        log.info("Tablet %s klingelt fuer Timer %s (%s)", request.remote, tid,
                 data.get("audio"))
    return web.json_response({"ok": True})


def create_app():
    app = web.Application()
    app.router.add_get("/", handle_index)
    app.router.add_get("/api/state", handle_get_state)
    app.router.add_post("/api/list", handle_post_list)
    app.router.add_post("/api/active", handle_post_active)
    app.router.add_get("/ws", handle_ws)
    app.router.add_post("/api/timer", handle_post_timer)
    app.router.add_post("/api/timer/klingelt", handle_post_timer_klingelt)
    app.router.add_post("/api/reload", handle_post_reload)
    # Titelbilder ausliefern. Verzeichnis sicherstellen, damit die Route IMMER
    # existiert - auch wenn images/ erst nach dem Serverstart befuellt wird
    # (Gaston legt Bilder spaeter ab). add_static braucht ein existierendes Dir.
    try:
        os.makedirs(IMAGES_DIR, exist_ok=True)
    except OSError as e:
        log.warning("Konnte Bildverzeichnis %s nicht anlegen: %s", IMAGES_DIR, e)
    if os.path.isdir(IMAGES_DIR):
        app.router.add_static("/recipe-images/", IMAGES_DIR, name="recipe_images")
    app.router.add_static("/static/", STATIC_DIR, name="static")
    # Sonstige statische Dateien (style.css, app.js) direkt unter / erlauben,
    # damit index.html sie ohne /static/-Praefix referenzieren kann.
    app.router.add_static("/", STATIC_DIR, name="root_static")
    return app


def main():
    app = create_app()
    log.info("tabletviewer startet auf Port %d, RECIPES_DIR=%s", PORT, RECIPES_DIR)
    web.run_app(app, host="0.0.0.0", port=PORT)


if __name__ == "__main__":
    main()
