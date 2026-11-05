"""Flur-Tablet: Home Assistant im iframe, Kalender als Overlay darueber.

Gesamtkonzept siehe README.de.md, Abschnitt "Flur-Tablet". Kurzfassung:

- GET /flur liefert die Wrapper-Seite (static/flur.html). WallPanel zeigt sie
  statt HA direkt an. HA laeuft darin als Vollbild-iframe und bleibt geladen,
  das Overlay liegt nur darueber.
- POST /api/flur/events: das Abrufskript (tools/flur_kalender.py) schickt die
  Termine der naechsten Wochen. Fakten (Datum, Zeit, Titel) kommen nur von
  dort; die Darstellung baut der Server, die Tablet-Uhr wird nicht gebraucht.
- POST /api/flur/show, /api/flur/hide: Node-RED steuert, WANN das Overlay
  erscheint. Ein Tap auf das Overlay blendet es aus und gilt als "gesehen".
- WS /ws/flur pusht Aenderungen, GET /api/flur/state ist der Polling-Fallback.
"""

import asyncio
import datetime as dt
import json
import logging
import os
import time
from zoneinfo import ZoneInfo

from aiohttp import web, WSMsgType

log = logging.getLogger("tabletviewer.flur")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
FLUR_STATE_FILE = os.path.join(BASE_DIR, "flur_state.json")

TZ = ZoneInfo(os.environ.get("TABLETVIEWER_TZ", "Europe/Berlin"))
HA_URL = os.environ.get("TABLETVIEWER_FLUR_HA_URL",
                        "http://192.168.7.2:8123/lovelace/default_view")
# Optional: wenn gesetzt, brauchen die schreibenden Flur-Routen (ausser dem
# Tap-Hide vom Tablet selbst) "Authorization: Bearer <token>".
FLUR_TOKEN = os.environ.get("TABLETVIEWER_FLUR_TOKEN", "")

DEFAULT_SHOW_SECONDS = 900
# fokus "auto": ab dieser Stunde zeigt das Overlay "Morgen" statt "Heute"
MORGEN_AB_STUNDE = int(os.environ.get("TABLETVIEWER_FLUR_MORGEN_AB", "18"))
WOCHE_TAGE = 6          # Tage nach dem Fokustag in der Wochenleiste
DEMNAECHST_TAGE = 42    # Horizont fuer "Demnaechst", ab heute gerechnet
DEMNAECHST_MAX = 10    # 2 Spalten x 5 Zeilen, mehr passt auf 1280x800 nicht

WT_KURZ = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
WT_LANG = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag",
           "Samstag", "Sonntag"]
MONATE = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli",
          "August", "September", "Oktober", "November", "Dezember"]


# --- Zustand ---

def _leerer_zustand():
    return {
        "events": [],
        "events_stand": None,        # ISO-Zeit des letzten erfolgreichen Abrufs
        "events_quelle": None,
        "overlay": {
            "view": None,            # None | "kalender"
            "fokus": "heute",        # "heute" | "morgen"
            "bis": None,             # Epoch-Sekunden oder None (= bis Tap)
            "gezeigt_um": None,
            "gesehen_um": None,      # letzter Tap auf das Overlay
        },
    }


def _laden():
    z = _leerer_zustand()
    try:
        with open(FLUR_STATE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data.get("events"), list):
            z["events"] = data["events"]
        z["events_stand"] = data.get("events_stand")
        z["events_quelle"] = data.get("events_quelle")
        if isinstance(data.get("overlay"), dict):
            z["overlay"].update(data["overlay"])
    except FileNotFoundError:
        pass
    except (OSError, ValueError) as e:
        log.warning("Konnte %s nicht laden (%s), starte leer.", FLUR_STATE_FILE, e)
    return z


def _speichern():
    try:
        tmp = FLUR_STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(FLUR, f, ensure_ascii=False, indent=1)
        os.replace(tmp, FLUR_STATE_FILE)
    except OSError as e:
        log.error("Konnte Flur-Zustand nicht speichern: %s", e)


FLUR = _laden()
FLUR_WS = set()
_hide_handle = None
# Letzter Poll einer Flur-SEITE (GET /api/flur/state?von=seite). Faellt die
# WebSocket-Verbindung auf dem Tablet aus, pollt die Seite - dann zaehlt sie
# trotzdem als aktiv. Node-RED fragt ohne ?von=seite und zaehlt nicht mit.
_letzter_seiten_poll = 0.0
SEITE_POLL_FRISCH_S = 15


# --- Termine aufbereiten ---

def _parse_zeit(s):
    """'2026-11-03' -> date, ISO mit Zeit -> aware datetime in TZ."""
    if len(s) == 10:
        return dt.date.fromisoformat(s)
    d = dt.datetime.fromisoformat(s)
    if d.tzinfo is None:
        d = d.replace(tzinfo=TZ)
    return d.astimezone(TZ)


def _normalisiere(ev):
    """Rohes Event vom Abrufskript -> interne Form mit Tagen. None bei Murks."""
    try:
        start = _parse_zeit(ev["start"])
        ende = _parse_zeit(ev.get("ende") or ev["start"])
    except (KeyError, ValueError, TypeError):
        return None
    ganztags = isinstance(start, dt.date) and not isinstance(start, dt.datetime)
    if ganztags:
        if not isinstance(ende, dt.date) or isinstance(ende, dt.datetime):
            ende = start + dt.timedelta(days=1)
        erster = start
        letzter = max(start, ende - dt.timedelta(days=1))   # Ende exklusiv
        start_ms = int(dt.datetime.combine(start, dt.time(), TZ).timestamp() * 1000)
        ende_ms = int(dt.datetime.combine(ende, dt.time(), TZ).timestamp() * 1000)
    else:
        if not isinstance(ende, dt.datetime) or ende < start:
            ende = start
        erster = start.date()
        letzter = (ende - dt.timedelta(seconds=1)).date() if ende > start else erster
        letzter = max(erster, letzter)
        start_ms = int(start.timestamp() * 1000)
        ende_ms = int(ende.timestamp() * 1000)
    return {
        "id": str(ev.get("id") or ""),
        "titel": str(ev.get("titel") or "(ohne Titel)").strip(),
        "ort": (str(ev["ort"]).strip() or None) if ev.get("ort") else None,
        "hinweis": (str(ev["hinweis"]).strip() or None) if ev.get("hinweis") else None,
        "wichtig": bool(ev.get("wichtig")),
        "vorab": _vorab(ev.get("vorab")),
        "ganztags": ganztags,
        "start": start,
        "ende": ende,
        "erster": erster,
        "letzter": letzter,
        "start_ms": start_ms,
        "ende_ms": ende_ms,
    }


def _vorab(liste):
    """[{ab: 'JJJJ-MM-TT', text}] -> [(date, text)], Murks wird ignoriert."""
    out = []
    for v in liste if isinstance(liste, list) else []:
        try:
            out.append((dt.date.fromisoformat(v["ab"]), str(v["text"]).strip()))
        except (KeyError, TypeError, ValueError):
            continue
    return [(ab, t) for ab, t in out if t]


def _hm(d):
    return "%d:%02d" % (d.hour, d.minute)


def _datum_kurz(d):
    return "%s %d.%d." % (WT_KURZ[d.weekday()], d.day, d.month)


def _zeit_text(e, tag):
    """Zeitangabe eines Events an einem bestimmten Tag."""
    if e["ganztags"]:
        if e["erster"] != e["letzter"]:
            return "bis %s" % _datum_kurz(e["letzter"]) if tag < e["letzter"] else "letzter Tag"
        return "ganztägig"
    if e["erster"] == e["letzter"]:
        if e["ende"] > e["start"]:
            return "%s – %s" % (_hm(e["start"]), _hm(e["ende"]))
        return _hm(e["start"])
    if tag == e["erster"]:
        return "ab %s" % _hm(e["start"])
    if tag == e["letzter"]:
        return "bis %s" % _hm(e["ende"])
    return "ganztägig"


def _sortkey(e):
    # Ganztaegiges zuerst, dann nach Beginn
    return (0 if e["ganztags"] else 1, e["start_ms"], e["titel"])


def _am_tag(events, tag):
    return sorted([e for e in events if e["erster"] <= tag <= e["letzter"]], key=_sortkey)


def baue_kalender(fokus="heute", jetzt=None):
    jetzt = jetzt or dt.datetime.now(TZ)
    heute = jetzt.date()
    fokustag = heute + dt.timedelta(days=1 if fokus == "morgen" else 0)
    events = [e for e in (_normalisiere(x) for x in FLUR["events"]) if e]

    fokus_events = []
    for e in _am_tag(events, fokustag):
        fokus_events.append({
            "id": e["id"], "titel": e["titel"], "ort": e["ort"],
            "hinweis": e["hinweis"], "wichtig": e["wichtig"],
            "ganztags": e["ganztags"], "zeit": _zeit_text(e, fokustag),
            "start_ms": e["start_ms"], "ende_ms": e["ende_ms"],
        })

    # Vorab-Hinweise: ab ihrem Datum bis vor den Termin unter dem Fokustag
    vorab = []
    for e in sorted(events, key=lambda e: e["start_ms"]):
        if e["erster"] <= fokustag:
            continue
        for ab, text in e["vorab"]:
            if ab <= fokustag:
                zeit = "" if e["ganztags"] else " " + _hm(e["start"])
                vorab.append({"text": text, "wichtig": e["wichtig"],
                              "fuer": "%s%s %s" % (_datum_kurz(e["erster"]), zeit, e["titel"]),
                              # Getrennt, damit das Tablet den Hinweis klar als
                              # Vorbereitung fuer einen spaeteren Tag zeigen kann.
                              "datum": _datum_kurz(e["erster"]),
                              "termin": e["titel"] + ("" if e["ganztags"] else ", %s Uhr" % _hm(e["start"])),
                              "in_tagen": (e["erster"] - fokustag).days})

    # Ist der Fokustag "heute" und leer, kurz sagen, was morgen ansteht.
    folgetag = None
    if fokus != "morgen" and not fokus_events:
        folgetag = []
        for e in _am_tag(events, fokustag + dt.timedelta(days=1)):
            folgetag.append({"zeit": "" if e["ganztags"] else _hm(e["start"]),
                             "titel": e["titel"]})

    woche = []
    for i in range(1, WOCHE_TAGE + 1):
        tag = fokustag + dt.timedelta(days=i)
        eintraege = []
        for e in _am_tag(events, tag):
            if e["ganztags"]:
                zeit = ""
            elif tag == e["erster"]:
                zeit = _hm(e["start"])
            else:
                zeit = "…"
            eintraege.append({"zeit": zeit, "titel": e["titel"], "wichtig": e["wichtig"]})
        woche.append({
            "wt": WT_KURZ[tag.weekday()],
            "datum": "%d.%d." % (tag.day, tag.month),
            "wochenende": tag.weekday() >= 5,
            "events": eintraege,
        })

    ab = fokustag + dt.timedelta(days=WOCHE_TAGE + 1)
    bis = heute + dt.timedelta(days=DEMNAECHST_TAGE)
    spaeter = sorted([e for e in events if ab <= e["erster"] <= bis],
                     key=lambda e: (e["erster"], _sortkey(e)))
    demnaechst = []
    for e in spaeter[:DEMNAECHST_MAX]:
        if e["erster"] != e["letzter"]:
            zeit = "bis %s" % _datum_kurz(e["letzter"])
        elif e["ganztags"]:
            zeit = ""
        else:
            zeit = _hm(e["start"])
        demnaechst.append({"datum": _datum_kurz(e["erster"]), "zeit": zeit,
                           "titel": e["titel"], "wichtig": e["wichtig"]})

    stand_alter = None
    if FLUR["events_stand"]:
        try:
            stand_alter = int((jetzt - _parse_zeit(FLUR["events_stand"])).total_seconds())
        except (ValueError, TypeError):
            pass

    return {
        "fokus": fokus,
        "fokus_titel": "Morgen" if fokus == "morgen" else "Heute",
        "fokus_datum": "%s, %d. %s" % (WT_LANG[fokustag.weekday()], fokustag.day,
                                       MONATE[fokustag.month - 1]),
        "fokus_events": fokus_events,
        "fokus_vorab": vorab,
        "folgetag": folgetag,
        "woche": woche,
        "demnaechst": demnaechst,
        "demnaechst_mehr": max(0, len(spaeter) - DEMNAECHST_MAX),
        "stand_alter_s": stand_alter,
    }


def payload():
    ov = FLUR["overlay"]
    rest = None
    if ov["view"] and ov["bis"]:
        rest = max(0, round(ov["bis"] - time.time()))
    poll_vor = (round(time.monotonic() - _letzter_seiten_poll)
                if _letzter_seiten_poll else None)
    return {
        "type": "flur",
        "jetzt_ms": int(time.time() * 1000),
        # Fuer den Gesundheitscheck (Node-RED): zeigt das Tablet /flur gerade?
        "seiten": {
            "ws": len(FLUR_WS),
            "poll_vor_s": poll_vor,
            "aktiv": len(FLUR_WS) > 0 or (poll_vor is not None and poll_vor <= SEITE_POLL_FRISCH_S),
        },
        "ha_url": HA_URL,
        "overlay": {
            "view": ov["view"],
            "rest_s": rest,
            "gezeigt_um": ov["gezeigt_um"],
            "gesehen_um": ov["gesehen_um"],
        },
        "kalender": baue_kalender(ov.get("fokus") or "heute"),
    }


async def broadcast(obj=None):
    text = json.dumps(obj if obj is not None else payload(), ensure_ascii=False)
    n = 0
    for ws in list(FLUR_WS):
        try:
            await ws.send_str(text)
            n += 1
        except (ConnectionResetError, RuntimeError):
            FLUR_WS.discard(ws)
    return n


# --- Overlay ein/aus ---

def _iso_jetzt():
    return dt.datetime.now(TZ).isoformat(timespec="seconds")


def _plane_hide(sekunden):
    global _hide_handle
    if _hide_handle:
        _hide_handle.cancel()
        _hide_handle = None
    if sekunden:
        loop = asyncio.get_event_loop()
        _hide_handle = loop.call_later(
            sekunden, lambda: asyncio.ensure_future(_verstecken("timeout")))


async def _verstecken(grund):
    global _hide_handle
    ov = FLUR["overlay"]
    if _hide_handle:
        _hide_handle.cancel()
        _hide_handle = None
    war = ov["view"]
    ov["view"] = None
    ov["bis"] = None
    if grund == "tap":
        ov["gesehen_um"] = _iso_jetzt()
    _speichern()
    if war:
        log.info("Flur-Overlay %s aus (%s)", war, grund)
    await broadcast()


def _auth_ok(request):
    return not FLUR_TOKEN or request.headers.get("Authorization") == "Bearer " + FLUR_TOKEN


async def _json_body(request):
    if not request.can_read_body:
        return {}
    try:
        data = await request.json()
    except (json.JSONDecodeError, ValueError):
        return None
    return data if isinstance(data, dict) else None


# --- HTTP-Handler ---

async def handle_page(request):
    return web.FileResponse(os.path.join(STATIC_DIR, "flur.html"))


async def handle_state(request):
    global _letzter_seiten_poll
    if request.query.get("von") == "seite":
        _letzter_seiten_poll = time.monotonic()
    return web.json_response(payload())


async def handle_events(request):
    if not _auth_ok(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    data = await _json_body(request)
    if data is None or not isinstance(data.get("events"), list):
        return web.json_response({"error": "'events' must be a list"}, status=400)
    gut, kaputt = [], 0
    for ev in data["events"]:
        if isinstance(ev, dict) and _normalisiere(ev):
            gut.append(ev)
        else:
            kaputt += 1
    FLUR["events"] = gut
    FLUR["events_stand"] = data.get("stand") or _iso_jetzt()
    FLUR["events_quelle"] = data.get("quelle")
    _speichern()
    log.info("Flur-Termine: %d uebernommen, %d verworfen", len(gut), kaputt)
    await broadcast()
    return web.json_response({"ok": True, "events": len(gut), "verworfen": kaputt})


async def handle_show(request):
    if not _auth_ok(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    data = await _json_body(request)
    if data is None:
        return web.json_response({"error": "invalid JSON"}, status=400)
    view = data.get("view", "kalender")
    if view != "kalender":
        return web.json_response({"error": "view must be 'kalender'"}, status=400)
    fokus = data.get("fokus", "heute")
    if fokus == "auto":
        fokus = "morgen" if dt.datetime.now(TZ).hour >= MORGEN_AB_STUNDE else "heute"
    if fokus not in ("heute", "morgen"):
        return web.json_response({"error": "fokus must be 'heute', 'morgen' or 'auto'"}, status=400)
    try:
        sekunden = int(data.get("seconds", DEFAULT_SHOW_SECONDS))
        skip = int(data.get("skip_if_seen_within_s") or 0)
    except (TypeError, ValueError):
        return web.json_response({"error": "seconds must be int"}, status=400)

    ov = FLUR["overlay"]
    if skip and ov["gesehen_um"]:
        try:
            alter = (dt.datetime.now(TZ) - _parse_zeit(ov["gesehen_um"])).total_seconds()
        except (ValueError, TypeError):
            alter = None
        if alter is not None and alter < skip:
            return web.json_response({"shown": False, "reason": "seen",
                                      "gesehen_um": ov["gesehen_um"]})

    ov["view"] = view
    ov["fokus"] = fokus
    ov["bis"] = time.time() + sekunden if sekunden > 0 else None
    ov["gezeigt_um"] = _iso_jetzt()
    _speichern()
    _plane_hide(sekunden if sekunden > 0 else None)
    n = await broadcast()
    log.info("Flur-Overlay %s/%s an fuer %ss (%d Client(s))", view, fokus,
             sekunden or "unbegrenzt", n)
    return web.json_response({"shown": True, "clients": n})


async def handle_hide(request):
    data = await _json_body(request) or {}
    grund = data.get("grund", "api")
    # Der Tap kommt vom Tablet selbst und braucht kein Token.
    if grund != "tap" and not _auth_ok(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    await _verstecken("tap" if grund == "tap" else "api")
    return web.json_response({"ok": True})


async def handle_reload(request):
    if not _auth_ok(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    data = await _json_body(request) or {}
    ziel = data.get("ziel", "seite")
    if ziel not in ("seite", "ha"):
        return web.json_response({"error": "ziel must be 'seite' or 'ha'"}, status=400)
    n = await broadcast({"type": "reload", "ziel": ziel})
    log.info("Flur-Reload (%s) an %d Client(s)", ziel, n)
    return web.json_response({"sent_to": n})


async def handle_ws(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    FLUR_WS.add(ws)
    log.info("Flur-WS verbunden (%d gesamt)", len(FLUR_WS))
    try:
        await ws.send_str(json.dumps(payload(), ensure_ascii=False))
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
            if msg.type == WSMsgType.TEXT and msg.data == "ping":
                await ws.send_str('{"type":"pong"}')
    finally:
        FLUR_WS.discard(ws)
        log.info("Flur-WS getrennt (%d verbleibend)", len(FLUR_WS))
    return ws


async def _on_startup(app):
    # Ein Overlay mit Ablaufzeit ueberlebt einen Neustart nur bis zu dieser Zeit.
    ov = FLUR["overlay"]
    if ov["view"] and ov["bis"]:
        rest = ov["bis"] - time.time()
        if rest <= 0:
            ov["view"] = None
            ov["bis"] = None
            _speichern()
        else:
            _plane_hide(rest)


def setup(app):
    app.router.add_get("/flur", handle_page)
    app.router.add_get("/api/flur/state", handle_state)
    app.router.add_post("/api/flur/events", handle_events)
    app.router.add_post("/api/flur/show", handle_show)
    app.router.add_post("/api/flur/hide", handle_hide)
    app.router.add_post("/api/flur/reload", handle_reload)
    app.router.add_get("/ws/flur", handle_ws)
    app.on_startup.append(_on_startup)
