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
        out.append({
            # uid + Beginn: eindeutig auch fuer einzelne Serien-Vorkommen
            "id": "%s@%s" % (text(ev, "UID"), iso(start)),
            "titel": text(ev, "SUMMARY") or "(ohne Titel)",
            "ort": text(ev, "LOCATION") or None,
            "beschreibung": text(ev, "DESCRIPTION")[:1000] or None,
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
