#!/usr/bin/env python3
"""Holt den Google-Familienkalender (geheime iCal-Adresse) und schickt die
Termine der naechsten Wochen an den tabletviewer (POST /api/flur/events).

Das ist die deterministische Basis der Flur-Anzeige: Datum, Uhrzeit und Titel
kommen nur von hier. Serientermine werden aufgeloest (recurring-ical-events).

Konfiguration per Umgebung (systemd: EnvironmentFile, siehe
systemd/flur-kalender.service):
  FLUR_ICAL_URL        geheime iCal-Adresse (Pflicht, NICHT ins Repo)
  TABLETVIEWER_URL     Default http://localhost:8080
  TABLETVIEWER_FLUR_TOKEN  optional, Bearer-Token
  FLUR_TAGE            Horizont in Tagen, Default 45
  TABLETVIEWER_TZ      Default Europe/Berlin

Aufruf: .venv/bin/python tools/flur_kalender.py [--dry-run]
"""

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.request
from zoneinfo import ZoneInfo

import icalendar
import recurring_ical_events

TZ = ZoneInfo(os.environ.get("TABLETVIEWER_TZ", "Europe/Berlin"))


def iso(v):
    if isinstance(v, dt.datetime):
        if v.tzinfo is None:
            v = v.replace(tzinfo=TZ)
        return v.astimezone(TZ).isoformat(timespec="minutes")
    return v.isoformat()


def text(comp, key):
    v = comp.get(key)
    return str(v).strip() if v is not None else ""


# Gaston-Block am Ende der Beschreibung (Format: skills/familienkalender im
# OpenClaw-Workspace). Alles ab der Zeile "— Gaston —" gehoert Gaston:
#   📌 <Hinweis>                    -> Hinweis unter dem Termin
#   📌 ab JJJJ-MM-TT: <Hinweis>     -> ab dem Datum schon vorher unter "Heute"
#   ❗ ...                          -> wichtig
#   🔔 an: jochen, maja [(9:45)]    -> Push aufs Handy (tabletviewer verschickt)
_BLOCK_RE = re.compile(r"(?:^|\n|<br\s*/?>|<p>|<div>)\s*(?:—|--|-)\s*Gaston\s*(?:—|--|-)\s*(?=$|\n|<br|</p>|</div>)",
                       re.I)
_AB_RE = re.compile(r"^ab\s+(\d{4}-\d{2}-\d{2})\s*:\s*(.+)$")
_MELDEN_RE = re.compile(r"^an\s*:\s*([^()]*?)\s*(?:\((\d{1,2}:\d{2})\))?\s*$")


def _klartext(t):
    t = re.sub(r"<br\s*/?>", "\n", t, flags=re.I)
    t = re.sub(r"</(p|div|li)>", "\n", t, flags=re.I)
    t = re.sub(r"<[^>]+>", "", t)
    return t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")


def gaston_block(beschreibung):
    """-> (beschreibung_ohne_block, hinweise[], vorab[], wichtig, melden|None)"""
    if not beschreibung:
        return "", [], [], False, None
    m = _BLOCK_RE.search(beschreibung)
    if not m:
        return _klartext(beschreibung).strip(), [], [], False, None
    hinweise, vorab, wichtig, melden = [], [], False, None
    for z in _klartext(beschreibung[m.start():]).split("\n"):
        z = z.strip()
        if z.startswith("❗"):
            wichtig = True
        elif z.startswith("🔔"):
            mm = _MELDEN_RE.match(z[len("🔔"):].strip())
            an = [n.strip().lower() for n in mm.group(1).split(",") if n.strip()] if mm else []
            if an:
                melden = {"an": an, "um": mm.group(2)}
        elif z.startswith("📌"):
            inhalt = z[len("📌"):].strip()
            mab = _AB_RE.match(inhalt)
            if mab:
                vorab.append({"ab": mab.group(1), "text": mab.group(2).strip()})
            elif inhalt:
                hinweise.append(inhalt)
    return _klartext(beschreibung[:m.start()]).strip(), hinweise, vorab, wichtig, melden


def lade_ics(url):
    req = urllib.request.Request(url, headers={"User-Agent": "tabletviewer-flur/1"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def termine(ics_bytes, tage):
    cal = icalendar.Calendar.from_ical(ics_bytes)
    heute = dt.datetime.now(TZ).date()
    von = heute - dt.timedelta(days=1)          # laufende Mehrtagestermine
    bis = heute + dt.timedelta(days=tage)
    out = []
    for ev in recurring_ical_events.of(cal).between(von, bis):
        if text(ev, "STATUS").upper() == "CANCELLED":
            continue
        start = ev.get("DTSTART").dt
        ende_prop = ev.get("DTEND")
        if ende_prop is not None:
            ende = ende_prop.dt
        elif ev.get("DURATION") is not None:
            ende = start + ev.get("DURATION").dt
        else:
            ende = start
        beschreibung, hinweise, vorab, wichtig, melden = gaston_block(text(ev, "DESCRIPTION"))
        out.append({
            # uid + Beginn: eindeutig auch fuer einzelne Serien-Vorkommen
            "id": "%s@%s" % (text(ev, "UID"), iso(start)),
            "titel": text(ev, "SUMMARY") or "(ohne Titel)",
            "ort": text(ev, "LOCATION") or None,
            "beschreibung": beschreibung[:1000] or None,
            "hinweis": " · ".join(hinweise) or None,
            "vorab": vorab,
            "wichtig": wichtig,
            "melden": melden,
            "start": iso(start),
            "ende": iso(ende),
        })
    out.sort(key=lambda e: e["start"])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="nur ausgeben, nicht senden")
    args = ap.parse_args()

    url = os.environ.get("FLUR_ICAL_URL")
    if not url:
        print("FLUR_ICAL_URL fehlt", file=sys.stderr)
        return 2
    tage = int(os.environ.get("FLUR_TAGE", "45"))
    evs = termine(lade_ics(url), tage)
    body = {
        "events": evs,
        "stand": dt.datetime.now(TZ).isoformat(timespec="seconds"),
        "quelle": "google-ical",
    }
    if args.dry_run:
        print(json.dumps(body, ensure_ascii=False, indent=1))
        return 0

    base = os.environ.get("TABLETVIEWER_URL", "http://localhost:8080").rstrip("/")
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("TABLETVIEWER_FLUR_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + "/api/flur/events",
                                 data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        print(r.read().decode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
