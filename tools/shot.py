#!/usr/bin/env python3
"""Screenshot-Helfer fuer den tabletviewer.

Rendert die laufende Viewer-Seite headless (System-Chromium via Playwright)
und legt ein PNG ab — damit man das Layout ohne das echte Tablet beurteilen
kann. Naehert das Aussehen an; die echte Android-5.1-WebView rendert Fonts/
Emojis minimal anders, fuer Layout/Verteilung ist es aber repraesentativ.

Beispiele:
  tools/shot.py --recipe panna-cotta-mit-erdbeersoße --card 0 --orient land
  tools/shot.py --recipe saure_zipfel --card 1 --orient port
  tools/shot.py --list --orient land

Voraussetzung: der Server laeuft (Default http://localhost:8080).
Aufruf mit dem venv-Python: .venv/bin/python tools/shot.py ...
"""

import argparse
import os
import time
import urllib.request

from playwright.sync_api import sync_playwright

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS_DIR = os.path.join(BASE_DIR, "shots")

LAND = (1024, 600)
PORT = (600, 1024)


def set_active(base_url, recipe):
    """Aktives Rezept serverseitig setzen (recipe=None -> Liste)."""
    import json
    body = json.dumps({"recipe": recipe}).encode("utf-8")
    req = urllib.request.Request(
        base_url + "/api/active", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=5).read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--recipe", default=None, help="Dateiname ohne .md; weglassen fuer Liste")
    ap.add_argument("--card", type=int, default=0, help="Karten-Index (0=Zutaten)")
    ap.add_argument("--list", action="store_true", help="Auswahlliste zeigen")
    ap.add_argument("--orient", choices=["land", "port"], default="land")
    ap.add_argument("--out", default=None, help="Ziel-PNG (Default: shots/<name>.png)")
    args = ap.parse_args()

    w, h = LAND if args.orient == "land" else PORT

    # Server-State setzen.
    if args.list or not args.recipe:
        set_active(args.url, None)
        label = "liste"
    else:
        set_active(args.url, args.recipe)
        label = args.recipe + "_card%d" % args.card

    out = args.out or os.path.join(SHOTS_DIR, "%s_%s.png" % (label, args.orient))
    os.makedirs(os.path.dirname(out), exist_ok=True)

    with sync_playwright() as p:
        b = p.chromium.launch(executable_path="/usr/bin/chromium",
                              args=["--no-sandbox", "--disable-gpu"])
        page = b.new_page(viewport={"width": w, "height": h}, device_scale_factor=1)
        page.goto(args.url, wait_until="networkidle")
        time.sleep(0.4)

        # Bei aktivem Rezept ggf. zur gewuenschten Karte blaettern (Tap rechts).
        if args.recipe and not args.list and args.card > 0:
            for _ in range(args.card):
                page.click("#zone-right")
                time.sleep(0.15)

        page.screenshot(path=out)
        b.close()

    print(out)


if __name__ == "__main__":
    main()
