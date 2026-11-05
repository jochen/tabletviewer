"""Tuerkamera fuer das Flur-Overlay.

- Livebild: der Server liest den MJPEG-Stream der Kamera (mjpg-streamer auf
  camera1) nur, solange ein Tablet zuschaut, dreht und verkleinert jedes Bild
  und gibt es als eigenen MJPEG-Stream bzw. als Einzelbild weiter. So muss die
  alte WebView weder 1280x960 dekodieren noch per CSS drehen.
- Gesicht: das letzte Bild mit erkanntem Gesicht kommt per MQTT
  (camera/face, ohnehin selten und bereits richtig ausgerichtet).

Routen:
  GET /flur/kamera/live.mjpg   multipart/x-mixed-replace, hoechstens KAMERA_FPS
  GET /flur/kamera/live.jpg    neuestes Bild (Fallback: Tablet laedt im Kreis)
  GET /flur/kamera/gesicht.jpg letztes Gesicht (404, solange keins da ist)
"""

import asyncio
import io
import logging
import os
import time
import zlib

import aiohttp
from aiohttp import web

log = logging.getLogger("tabletviewer.kamera")

KAMERA_URL = os.environ.get("TABLETVIEWER_KAMERA_URL", "http://192.168.7.61:8080/?action=stream")
# Drehung im Uhrzeigersinn wie die CSS-Klasse x-rotated-270 von mjpg-streamer
KAMERA_DREHUNG = int(os.environ.get("TABLETVIEWER_KAMERA_DREHUNG", "270")) % 360
KAMERA_HOEHE = int(os.environ.get("TABLETVIEWER_KAMERA_HOEHE", "800"))
KAMERA_FPS = float(os.environ.get("TABLETVIEWER_KAMERA_FPS", "5"))
KAMERA_QUALITAET = int(os.environ.get("TABLETVIEWER_KAMERA_QUALITAET", "70"))
LEERLAUF_S = 15          # so lange nach dem letzten Zuschauer weiterlesen

MQTT_HOST = os.environ.get("TABLETVIEWER_MQTT_HOST", "192.168.7.76")
MQTT_PORT = int(os.environ.get("TABLETVIEWER_MQTT_PORT", "1883"))
GESICHT_TOPIC = os.environ.get("TABLETVIEWER_KAMERA_GESICHT_TOPIC", "camera/face")

BOUNDARY = "tvframe"


class Live:
    """Liest den Kamera-Stream bei Bedarf; haelt das neueste Rohbild."""

    def __init__(self):
        self.roh = None            # neuestes JPEG der Kamera
        self.roh_nr = 0
        self.fertig = None         # gedreht/verkleinert
        self.fertig_nr = -1
        self.bedarf_bis = 0.0
        self.task = None
        self.neu = asyncio.Event()
        self.fehler = None
        self._lock = asyncio.Lock()

    def bedarf(self):
        self.bedarf_bis = time.monotonic() + LEERLAUF_S
        if not self.task or self.task.done():
            self.task = asyncio.ensure_future(self._lesen())

    async def _lesen(self):
        log.info("Kamera-Stream an (%s)", KAMERA_URL)
        timeout = aiohttp.ClientTimeout(total=None, sock_connect=5, sock_read=10)
        try:
            while time.monotonic() < self.bedarf_bis:
                try:
                    async with aiohttp.ClientSession(timeout=timeout) as s:
                        async with s.get(KAMERA_URL) as r:
                            r.raise_for_status()
                            await self._frames(r.content)
                except asyncio.CancelledError:
                    raise
                except Exception as e:      # Kamera weg: kurz warten, neu
                    self.fehler = str(e) or e.__class__.__name__
                    log.warning("Kamera-Stream: %s", self.fehler)
                    await asyncio.sleep(2)
        finally:
            log.info("Kamera-Stream aus")

    async def _frames(self, inhalt):
        # mjpg-streamer: --boundary\r\nContent-Type..\r\nContent-Length: N\r\n..\r\n\r\n<N bytes>
        while time.monotonic() < self.bedarf_bis:
            laenge = None
            while True:
                zeile = await inhalt.readline()
                if not zeile:
                    raise ConnectionError("Stream zu Ende")
                z = zeile.strip().lower()
                if z.startswith(b"content-length:"):
                    laenge = int(z.split(b":", 1)[1])
                elif not z and laenge:
                    break
            daten = await inhalt.readexactly(laenge)
            self.roh = daten
            self.roh_nr += 1
            self.fehler = None
            self.neu.set()
            self.neu = asyncio.Event()

    async def bild(self):
        """Neuestes fertiges Bild (gedreht/verkleinert), None ohne Kamera."""
        async with self._lock:
            if self.roh is None:
                return None
            if self.fertig_nr != self.roh_nr:
                nr, roh = self.roh_nr, self.roh
                self.fertig = await asyncio.get_event_loop().run_in_executor(None, _umbauen, roh)
                self.fertig_nr = nr
            return self.fertig


def _umbauen(roh):
    from PIL import Image
    im = Image.open(io.BytesIO(roh))
    # JPEG nur so fein dekodieren wie noetig (Zielhoehe nach dem Drehen)
    seite = KAMERA_HOEHE if KAMERA_DREHUNG in (90, 270) else KAMERA_HOEHE * im.width // im.height
    im.draft("RGB", (seite, seite * im.height // im.width))
    if KAMERA_DREHUNG:
        # PIL dreht gegen den Uhrzeigersinn
        im = im.rotate(-KAMERA_DREHUNG, expand=True)
    if im.height > KAMERA_HOEHE:
        b = round(im.width * KAMERA_HOEHE / im.height)
        im = im.resize((b, KAMERA_HOEHE), Image.BILINEAR)
    out = io.BytesIO()
    im.convert("RGB").save(out, "JPEG", quality=KAMERA_QUALITAET)
    return out.getvalue()


LIVE = None
GESICHT = {"jpeg": None, "um": None}     # um = Epoch des Empfangs (None = retained)
_mqtt = None


def _mqtt_starten(loop):
    global _mqtt
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        log.warning("paho-mqtt fehlt, kein Gesichtsbild")
        return

    def on_connect(c, u, flags, rc, props=None):
        c.subscribe(GESICHT_TOPIC)

    def on_message(c, u, msg):
        if msg.payload[:2] != b"\xff\xd8":        # kein JPEG
            return
        GESICHT["jpeg"] = bytes(msg.payload)
        GESICHT["um"] = None if msg.retain else time.time()

    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tabletviewer-kamera")
    c.on_connect = on_connect
    c.on_message = on_message
    c.reconnect_delay_set(2, 60)
    try:
        c.connect_async(MQTT_HOST, MQTT_PORT, 60)
        c.loop_start()
        _mqtt = c
        log.info("Gesicht per MQTT: %s:%d %s", MQTT_HOST, MQTT_PORT, GESICHT_TOPIC)
    except Exception as e:
        log.warning("MQTT nicht gestartet: %s", e)


def status():
    """Fuer den Flur-Zustand."""
    g = GESICHT
    return {
        "gesicht": bool(g["jpeg"]),
        # Version fuer die Bild-URL, damit das Tablet nur Neues laedt
        "gesicht_v": zlib.crc32(g["jpeg"]) if g["jpeg"] else 0,
        "gesicht_um_ms": int(g["um"] * 1000) if g["um"] else None,
    }


# --- Routen ---

async def handle_live_mjpg(request):
    LIVE.bedarf()
    resp = web.StreamResponse(headers={
        "Content-Type": "multipart/x-mixed-replace; boundary=" + BOUNDARY,
        "Cache-Control": "no-cache, no-store",
        "Pragma": "no-cache",
    })
    await resp.prepare(request)
    abstand = 1.0 / KAMERA_FPS if KAMERA_FPS > 0 else 0
    gesendet = -1
    try:
        while True:
            LIVE.bedarf()
            # Auf ein NEUES Bild warten (auch vor dem allerersten). Wichtig:
            # jede Runde muss awaiten, sonst blockiert die Schleife den Server.
            if LIVE.roh is None or LIVE.roh_nr == gesendet:
                try:
                    await asyncio.wait_for(LIVE.neu.wait(), timeout=5)
                except asyncio.TimeoutError:
                    pass
                continue
            t0 = time.monotonic()
            jpeg = await LIVE.bild()
            gesendet = LIVE.fertig_nr
            await resp.write(b"--" + BOUNDARY.encode() + b"\r\nContent-Type: image/jpeg\r\n"
                             b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n"
                             + jpeg + b"\r\n")
            rest = abstand - (time.monotonic() - t0)
            if rest > 0:
                await asyncio.sleep(rest)
    except (ConnectionResetError, asyncio.CancelledError):
        pass
    return resp


async def handle_live_jpg(request):
    LIVE.bedarf()
    if LIVE.roh is None:
        try:
            await asyncio.wait_for(LIVE.neu.wait(), timeout=6)
        except asyncio.TimeoutError:
            return web.Response(status=503, text="Kamera nicht erreichbar: %s" % LIVE.fehler)
    jpeg = await LIVE.bild()
    return web.Response(body=jpeg, content_type="image/jpeg",
                        headers={"Cache-Control": "no-cache, no-store"})


async def handle_gesicht(request):
    if not GESICHT["jpeg"]:
        return web.Response(status=404, text="noch kein Gesicht")
    return web.Response(body=GESICHT["jpeg"], content_type="image/jpeg",
                        headers={"Cache-Control": "no-cache"})


async def _on_startup(app):
    global LIVE
    LIVE = Live()
    _mqtt_starten(asyncio.get_event_loop())


async def _on_cleanup(app):
    if LIVE and LIVE.task:
        LIVE.task.cancel()
    if _mqtt:
        _mqtt.loop_stop()


def setup(app):
    app.router.add_get("/flur/kamera/live.mjpg", handle_live_mjpg)
    app.router.add_get("/flur/kamera/live.jpg", handle_live_jpg)
    app.router.add_get("/flur/kamera/gesicht.jpg", handle_gesicht)
    app.on_startup.append(_on_startup)
    app.on_cleanup.append(_on_cleanup)
