"""Toleranter Markdown-Parser fuer die Rezepte unter
/home/pi/.openclaw/workspace/rezepte/*.md

Erwartetes Format (siehe README.de.md "Rezept-Format"):

1. `# Titel` (H1, oft mit Emoji am Anfang oder Ende)
2. Metadaten-Block: `**Key:** Value`-Zeilen direkt unter dem Titel,
   teils mit " | " mehrere pro Zeile.
3. Optionaler `---`-Trenner
4. `## Zutaten` (oder Variationen) -> EINE Zutaten-Karte. `### Für X`
   Unter-Ueberschriften werden als Gruppen mit ihren Bullet-Items erhalten.
5. `## Zubereitung` (oder aehnlich) -> nummerierte Liste, jeder Top-Level-
   Schritt wird eine eigene Schritt-Karte (type "step").
6. Alles danach (Servieren, Tipps, Aufbewahrung, Quelle, Nährwerte, ...)
   wird NICHT als eigene Karte ausgegeben, sondern ignoriert.

   DESIGN-ENTSCHEIDUNG: Die README erlaubt es, restliche Abschnitte als
   zusaetzliche Karte(n) auszugeben ODER wegzulassen ("halte es simpel").
   Wir lassen sie weg: der Tab ist fuers Kochen gedacht (Zutaten + Schritte),
   nicht zum Lesen von Tipps/Naehrwerten/Quellen. Das haelt die Karten-Liste
   kurz und die Navigation einfach. Diese Abschnitte bleiben in den .md-
   Dateien vollstaendig erhalten, sie werden nur fuer den Viewer nicht
   gerendert.
"""

import re


# Ueberschriften, die als "Zutaten"-Abschnitt erkannt werden (lowercase,
# ohne Emoji/Sonderzeichen-Praefix).
INGREDIENTS_HEADINGS = ("zutaten", "ingredients")

# Ueberschriften, die als "Zubereitung"-Abschnitt erkannt werden.
PREPARATION_HEADINGS = ("zubereitung", "preparation", "schritt-für-schritt-anleitung",
                        "schritt-fuer-schritt-anleitung")


_BOLD_RE = re.compile(r"\*\*(.+?)\*\*")
_META_RE = re.compile(r"^\*\*(.+?):\*\*\s*(.*)$")
_NUM_ITEM_RE = re.compile(r"^(\d+)\.\s+(.*)$")
_BULLET_RE = re.compile(r"^[-*]\s+(.*)$")


def _strip_md_bold(text):
    """Wandelt **fett** in <strong>fett</strong> um. Reines ES5-HTML als
    Ausgabe, da das Frontend den Text direkt per innerHTML setzen darf
    (keine sonstigen Markdown-Konstrukte werden erwartet)."""
    return _BOLD_RE.sub(r"<strong>\1</strong>", text)


def _extract_title_and_icon(h1_text):
    """Extrahiert ein fuehrendes ODER abschliessendes Emoji aus der
    H1-Zeile als Icon, Rest als Titel."""
    text = h1_text.strip()

    # Emoji am Anfang? (ein oder mehrere Emoji-Zeichen, gefolgt von Leerzeichen)
    m = re.match(r"^([\U0001F000-\U0001FFFF☀-➿←-⇿⬀-⯿️]+)\s*(.*)$", text)
    if m and m.group(2):
        return m.group(2).strip(), m.group(1).strip()

    # Emoji am Ende?
    m = re.match(r"^(.*?)\s*([\U0001F000-\U0001FFFF☀-➿←-⇿⬀-⯿️]+)$", text)
    if m and m.group(1):
        return m.group(1).strip(), m.group(2).strip()

    return text, None


def _heading_level_and_text(line):
    """Gibt (level, text) zurueck, falls line eine Markdown-Ueberschrift ist,
    sonst (0, None)."""
    m = re.match(r"^(#{1,6})\s+(.*)$", line)
    if not m:
        return 0, None
    return len(m.group(1)), m.group(2).strip()


def _normalize_heading(text):
    """Normalisiert eine Ueberschrift fuer den Abschnitts-Vergleich: Emoji
    entfernen, lowercase, trim."""
    cleaned = re.sub(r"[\U0001F000-\U0001FFFF☀-➿←-⇿⬀-⯿️]", "", text)
    cleaned = cleaned.strip().lower()
    # Satzzeichen am Ende entfernen (z.B. "Schritt 1: Spätzle und Speck vorbereiten")
    return cleaned


def _is_ingredients_heading(text):
    norm = _normalize_heading(text)
    # Deckt auch Varianten wie "Zutaten für das Fleisch" oder
    # "Zutaten für die Marinade" ab (mehrere ## Zutaten ...-Abschnitte
    # pro Datei, z.B. butter-chicken.md). Alle landen als zusaetzliche
    # Gruppen in der EINEN Zutaten-Karte (README: "Zutaten = eine Karte").
    return any(norm.startswith(h) for h in INGREDIENTS_HEADINGS)


def _ingredient_group_heading_from_h2(htext, norm):
    """Falls eine ## Zutaten-Ueberschrift einen Zusatz traegt (z.B.
    "Zutaten für die Marinade"), wird dieser Zusatz als Gruppen-Heading
    verwendet. Bei reinem "Zutaten" (ohne Zusatz) -> kein Gruppen-Heading."""
    for h in INGREDIENTS_HEADINGS:
        if norm == h:
            return None
        if norm.startswith(h):
            # Rest nach dem Schluesselwort, z.B. " für das Fleisch" -> "Für das Fleisch"
            rest = htext.strip()
            # Versuche, den passenden Praefix case-insensitiv abzuschneiden
            return rest[len(h):].strip(" :–-").strip().capitalize() or None
    return None


def _is_preparation_heading(text):
    norm = _normalize_heading(text)
    return any(norm.startswith(h) for h in PREPARATION_HEADINGS)


# Zusaetzliche H2-Abschnitte, die inhaltlich zur Zubereitung gehoeren und an
# die Schritt-Karten angehaengt werden (z.B. "## Anrichten" in
# zwiebelrostbraten-gusseisen-edition.md). Bewusst NICHT die allgemeinen
# Restabschnitte (Servieren/Tipps/Aufbewahrung/Quelle) - die werden laut
# Design-Entscheidung oben weggelassen.
PREPARATION_CONTINUATION_HEADINGS = ("anrichten",)


def _is_preparation_continuation_heading(text):
    norm = _normalize_heading(text)
    return any(norm.startswith(h) for h in PREPARATION_CONTINUATION_HEADINGS)


def parse_recipe(name, text):
    """Parst den Inhalt einer Rezept-Markdown-Datei.

    Gibt ein dict zurueck:
    {
        "name": name,
        "title": str,
        "icon": str|null,
        "badges": [{"key": str, "value": str}, ...],
        "cards": [
            {"type": "ingredients", "groups": [{"heading": str|null, "items": [str,...]}]},
            {"type": "step", "n": int, "text": str},
            ...
        ]
    }
    """
    lines = text.splitlines()

    title = name
    icon = None
    badges = []
    cards = []

    i = 0
    n = len(lines)

    # --- 1. H1-Titel finden (erste Zeile, die mit "# " beginnt) ---
    while i < n:
        level, htext = _heading_level_and_text(lines[i])
        if level == 1:
            title, icon = _extract_title_and_icon(htext)
            i += 1
            break
        i += 1

    # --- 2. Metadaten-Block: **Key:** Value Zeilen, optional " | "-getrennt ---
    while i < n:
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if line == "---":
            i += 1
            continue
        # Stop, sobald eine Ueberschrift kommt.
        level, _ = _heading_level_and_text(line)
        if level > 0:
            break

        # Zeile kann mehrere "**Key:** Value"-Paare enthalten, getrennt
        # durch " | " oder Hard-Break (zwei Leerzeichen am Ende werden beim
        # Lesen sowieso entfernt durch strip()).
        parts = [p.strip() for p in line.split("|")]
        matched_any = False
        for part in parts:
            m = _META_RE.match(part)
            if m:
                key = m.group(1).strip()
                value = m.group(2).strip()
                badges.append({"key": key, "value": value})
                matched_any = True

        if not matched_any:
            # Keine Metadaten-Zeile mehr -> Block beendet, ohne i zu erhoehen
            # (Zeile evtl. Teil von etwas anderem, z.B. Beschreibungstext).
            break

        i += 1

    # --- 3. Restliche Abschnitte durchgehen ---
    # "ingredients" und "preparation" sind als Sammeltoepfe gedacht: ALLE
    # passenden H2-Abschnitte einer Datei tragen zur jeweils EINEN Karte
    # bzw. Schrittliste bei (README: "Zutaten = eine Karte"), auch wenn die
    # Datei mehrere "## Zutaten für ..."-Abschnitte hat (butter-chicken.md)
    # oder die Zubereitung in "## Schritt-für-Schritt-Anleitung" +
    # "## Anrichten" aufgeteilt ist (zwiebelrostbraten-gusseisen-edition.md).
    current_section = None  # "ingredients" | "preparation" | None
    ingredient_groups = []
    current_group = None  # {"heading": str|None, "items": [...]}
    step_n = 0
    # Fuer den Bullet-Fallback in der Zubereitung: ### -Unterueberschrift,
    # deren folgende Bullet-Items noch keiner Schritt-Karte zugeordnet wurden.
    pending_h3 = None
    pending_h3_lines = []

    def flush_ingredient_group():
        if current_group is not None and current_group["items"]:
            ingredient_groups.append(current_group)

    def flush_pending_h3():
        nonlocal step_n, pending_h3, pending_h3_lines
        if pending_h3 is not None and pending_h3_lines:
            step_n += 1
            # Falls die ###-Ueberschrift selbst schon mit "N. " durchnummeriert
            # ist (z.B. "### 1. Vorbereitung (...)"), Praefix entfernen, um
            # eine Doppel-Nummerierung im Schritt-Text zu vermeiden.
            heading = re.sub(r"^\d+\.\s*", "", pending_h3)
            text = _strip_md_bold(heading + ": " + " ".join(pending_h3_lines))
            cards.append({"type": "step", "n": step_n, "text": text})
        pending_h3 = None
        pending_h3_lines = []

    while i < n:
        line = lines[i]
        stripped = line.strip()
        level, htext = _heading_level_and_text(stripped)

        if level == 2:
            norm = _normalize_heading(htext)

            if _is_ingredients_heading(htext):
                # Vorherige Gruppe (falls offen) abschliessen, neue Gruppe
                # je nach Zusatz ("Zutaten für die Marinade" -> "Für die Marinade")
                flush_ingredient_group()
                group_heading = _ingredient_group_heading_from_h2(htext, norm)
                current_group = {"heading": group_heading, "items": []}
                current_section = "ingredients"
            elif _is_preparation_heading(htext) or _is_preparation_continuation_heading(htext):
                if current_section == "ingredients":
                    flush_ingredient_group()
                    if ingredient_groups:
                        cards.append({"type": "ingredients", "groups": ingredient_groups})
                    ingredient_groups = []
                    current_group = None
                flush_pending_h3()
                current_section = "preparation"
            else:
                if current_section == "ingredients":
                    flush_ingredient_group()
                    if ingredient_groups:
                        cards.append({"type": "ingredients", "groups": ingredient_groups})
                    ingredient_groups = []
                    current_group = None
                flush_pending_h3()
                current_section = None  # restliche Abschnitte ignorieren

            i += 1
            continue

        if level == 3:
            if current_section == "ingredients":
                # Neue Untergruppe ("### Für X")
                if current_group is not None and current_group["items"]:
                    ingredient_groups.append(current_group)
                current_group = {"heading": htext, "items": []}
            elif current_section == "preparation":
                # Unter-Ueberschriften in "Zubereitung":
                # - falls darunter eine NUMMERIERTE Liste folgt (Normalfall),
                #   tragen sie selbst keine Karte bei.
                # - falls darunter eine BULLET-Liste folgt (z.B.
                #   "### 1. Vorbereitung" in zwiebelrostbraten...), wird die
                #   Ueberschrift + ihre Bullets zu EINER Schritt-Karte
                #   zusammengefasst (Fallback fuer Dateien ohne nummerierte
                #   Zubereitungsliste).
                flush_pending_h3()
                pending_h3 = htext
                pending_h3_lines = []
            i += 1
            continue

        if level >= 4:
            # Tiefere Ueberschriften ignorieren wir.
            i += 1
            continue

        if current_section == "ingredients":
            bm = _BULLET_RE.match(stripped)
            if bm:
                if current_group is None:
                    current_group = {"heading": None, "items": []}
                current_group["items"].append(_strip_md_bold(bm.group(1).strip()))
            # Leere Zeilen / Sonstiges ignorieren.
            i += 1
            continue

        if current_section == "preparation":
            nm = _NUM_ITEM_RE.match(stripped)
            if nm:
                # Eine nummerierte nach einer ###-Ueberschrift ohne eigene
                # Bullets gesammelt zu haben -> Ueberschrift war nur ein
                # Gruppierungs-Label, verwerfen.
                pending_h3 = None
                pending_h3_lines = []

                step_n += 1
                # Mehrzeilige Schritte (Folgezeilen mit Einrueckung, eigene
                # Bullet-Unterpunkte etc.) an den Schritt-Text anhaengen, bis
                # die naechste Top-Level-Nummerierung, Ueberschrift oder ein
                # `---`-Trenner kommt.
                step_lines = [nm.group(2).strip()]
                j = i + 1
                while j < n:
                    nxt = lines[j]
                    nxt_stripped = nxt.strip()
                    if not nxt_stripped:
                        j += 1
                        continue
                    nxt_level, _ = _heading_level_and_text(nxt_stripped)
                    if nxt_level > 0 or nxt_stripped == "---":
                        break
                    if _NUM_ITEM_RE.match(nxt_stripped):
                        break
                    # Eingerueckte Bullet-Unterpunkte oder Fortsetzungstext
                    # gehoeren zum aktuellen Schritt.
                    bm2 = _BULLET_RE.match(nxt_stripped)
                    if bm2:
                        step_lines.append(bm2.group(1).strip())
                    else:
                        step_lines.append(nxt_stripped)
                    j += 1

                step_text = _strip_md_bold(" ".join(step_lines))
                cards.append({"type": "step", "n": step_n, "text": step_text})
                i = j
                continue

            if pending_h3 is not None:
                # Bullet-Fallback: Items unter einer ###-Ueberschrift ohne
                # nummerierte Liste sammeln.
                bm2 = _BULLET_RE.match(stripped)
                if bm2:
                    pending_h3_lines.append(bm2.group(1).strip())
                elif stripped:
                    pending_h3_lines.append(stripped)

            i += 1
            continue

        # Ausserhalb von Zutaten/Zubereitung: ignorieren.
        i += 1

    # Letzte offene Zutaten-Gruppe abschliessen.
    if current_section == "ingredients":
        flush_ingredient_group()
        if ingredient_groups:
            cards.append({"type": "ingredients", "groups": ingredient_groups})

    # Letzte offene ###-Bullet-Gruppe in der Zubereitung abschliessen.
    flush_pending_h3()

    return {
        "name": name,
        "title": title,
        "icon": icon,
        "badges": badges,
        "cards": cards,
    }
