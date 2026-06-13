"""tabletviewer - aiohttp-Server fuer das Küchen-Tablet.

Siehe README.de.md fuer das Gesamtkonzept. Kurzfassung:

- Statische ES5-UI unter static/ wird unter GET / ausgeliefert.
- In-Memory-State (Liste kuratierter Rezepte + aktives Rezept + Karten-
  Index), persistiert nach state.json im Projektordner.
- Markdown-Rezepte aus RECIPES_DIR werden server-seitig geparst (parser.py)
  und als simple JSON-Struktur an die UI gegeben.
- WebSocket /ws pusht Zustandsaenderungen an alle verbundenen Clients;
  Polling von /api/state ist der Fallback fuer alte WebViews.
"""

import asyncio
import json
import logging
import os
import re

from aiohttp import web, WSMsgType

from parser import parse_recipe

# --- Konfiguration (per Umgebungsvariable ueberschreibbar) ---
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

RECIPES_DIR = os.environ.get("TABLETVIEWER_RECIPES_DIR", "/home/pi/.openclaw/workspace/rezepte")
PORT = int(os.environ.get("TABLETVIEWER_PORT", "8080"))
STATE_FILE = os.path.join(BASE_DIR, "state.json")
STATIC_DIR = os.path.join(BASE_DIR, "static")

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
        return parse_recipe(name, text)
    except Exception as e:
        log.error("Parser-Fehler bei Rezept %s: %s", name, e)
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


def build_state_payload():
    """Baut die JSON-Antwort fuer GET /api/state und WS-Broadcasts."""
    payload = {
        "list": [list_entry(name) for name in STATE["list"]],
        "active": STATE["active"],
        "card": STATE["card"],
        "recipe": None,
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
            # Der Viewer schreibt nur ueber HTTP (/api/list, /api/active);
            # eingehende WS-Nachrichten werden nicht ausgewertet.
    finally:
        WS_CLIENTS.discard(ws)
        log.info("WS-Client getrennt (%d verbleibend)", len(WS_CLIENTS))

    return ws


def create_app():
    app = web.Application()
    app.router.add_get("/", handle_index)
    app.router.add_get("/api/state", handle_get_state)
    app.router.add_post("/api/list", handle_post_list)
    app.router.add_post("/api/active", handle_post_active)
    app.router.add_get("/ws", handle_ws)
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
