/* tabletviewer - ES5-Frontend fuer alte Android-5.1-WebView.
 * KEINE arrow functions, KEIN fetch, KEIN Framework.
 * State kommt per WebSocket (/ws); falls das nicht funktioniert, wird per
 * XHR alle ~2s GET /api/state gepollt. Schreibende Aktionen gehen immer
 * per XHR POST; die UI reagiert auf den zurueckkommenden Broadcast/State.
 */

(function () {
  "use strict";

  var state = null;
  var ws = null;
  var pollTimer = null;
  var POLL_INTERVAL = 2000;

  // Bildschirm-"Schlaf" rein in der Web-App: nach dieser Inaktivitaet legt
  // sich ein schwarzes Overlay drueber. Android schaltet das Display NICHT
  // ab (WallPanel "Prevent Sleep" bleibt an), darum kommt kein Fire-Keyguard
  // mit Wischsperre. Der erste Tap weckt nur (siehe wakeScreen). Zum Anpassen
  // hier den Wert aendern.
  var DIM_AFTER_MS = 1800000; // 30 Minuten (lang genug, damit es beim Kochen nicht ausgeht)
  var dimTimer = null;
  var dimmed = false;
  // Signatur des zuletzt angewendeten States, um echte (Gaston-)Aenderungen
  // vom 2-Sekunden-Polling zu unterscheiden - nur echte Aenderungen wecken.
  var lastSig = null;

  // --- Hilfsfunktionen ---

  function byId(id) {
    return document.getElementById(id);
  }

  function xhrGetState() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/state", true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState === 4 && xhr.status === 200) {
        try {
          var data = JSON.parse(xhr.responseText);
          applyState(data);
        } catch (e) {
          // ignorieren, naechster Poll/Push korrigiert
        }
      }
    };
    xhr.send(null);
  }

  function xhrPost(url, body) {
    var xhr = new XMLHttpRequest();
    xhr.open("POST", url, true);
    xhr.setRequestHeader("Content-Type", "application/json");
    xhr.send(JSON.stringify(body));
    // Keine eigene UI-Aktualisierung hier - die UI reagiert auf den
    // WS-Broadcast bzw. den naechsten Poll.
  }

  function startPolling() {
    if (pollTimer) {
      return;
    }
    pollTimer = setInterval(xhrGetState, POLL_INTERVAL);
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  // --- Kuechentimer ---
  // Der Sprachassistent haelt die Uhr und schickt Restzeiten (Vertrag:
  // TIMER_INTERFACE.md im openclaw_voice_assist). Hier wird ab Empfang mit
  // der eigenen monotonen Uhr weitergezaehlt - die Wanduhr des Tablets ist
  // nicht verlaesslich. Klingeln geht NUR ueber WebAudio: <audio>.play() ist
  // ohne Geste immer blockiert, WebAudio laeuft auch nach 36 min gedimmt
  // (gemessen 2026-10-10, Branch test/tontest).

  var timerStand = null;      // {seq, klingel_abstand_s, nachlauf_max_s, timer:[...]}
  var timerEmpfangen = 0;     // jetztMs() beim Empfang
  var timerRing = {};         // id -> {n: gespielte Folgen, handle: Intervall}
  var audioCtx = null;

  function jetztMs() {
    if (window.performance && performance.now) {
      return performance.now();
    }
    return new Date().getTime();
  }

  function getAudioCtx() {
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!audioCtx && Ctx) {
      audioCtx = new Ctx();
    }
    return audioCtx;
  }

  // Eine Klingel-Folge: vier Rechteck-Pieper 2,5 kHz, volle Lautstaerke -
  // im Tontest "deutlich lauter und fuer einen Wecker/Timer wirklich gut".
  function klingelFolge() {
    var ctx = getAudioCtx();
    if (!ctx) {
      return false;
    }
    if (ctx.state === "suspended" && ctx.resume) {
      ctx.resume();
    }
    var t = ctx.currentTime + 0.05;
    for (var i = 0; i < 4; i++) {
      var osc = ctx.createOscillator();
      var gain = ctx.createGain();
      osc.type = "square";
      osc.frequency.value = 2500;
      gain.gain.value = 1.0;
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t + i * 0.15);
      osc.stop(t + i * 0.15 + 0.08);
    }
    return ctx.state === "running";
  }

  function timerRest(t) {
    return t.rest_s - (jetztMs() - timerEmpfangen) / 1000;
  }

  function findeTimer(id) {
    var liste = (timerStand && timerStand.timer) || [];
    for (var i = 0; i < liste.length; i++) {
      if (liste[i].id === id) {
        return liste[i];
      }
    }
    return null;
  }

  function stopRing(id) {
    if (timerRing[id] && timerRing[id].handle) {
      clearInterval(timerRing[id].handle);
      timerRing[id].handle = null;
    }
  }

  // Klingelt hoechstens t.klingeln Folgen im Abstand klingel_abstand_s und
  // hoert auf, sobald der Timer still, verlaengert oder geloescht ist.
  function startRing(t) {
    var id = t.id;
    timerRing[id] = { n: 0, handle: null };
    var folge = function () {
      var akt = findeTimer(id);
      var r = timerRing[id];
      if (!r || !akt || akt.still || timerRest(akt) > 0 || r.n >= akt.klingeln) {
        stopRing(id);
        return;
      }
      var laeuft = klingelFolge();
      r.n += 1;
      if (r.n === 1) {
        // Nur bestaetigen, wenn WebAudio wirklich spielt - sonst klingelt
        // der Assistent am eigenen Lautsprecher.
        if (laeuft) {
          xhrPost("/api/timer/klingelt", { id: id, audio: audioCtx ? audioCtx.state : null });
        }
        wakeScreen();
      }
    };
    folge();
    timerRing[id].handle = setInterval(folge, ((timerStand && timerStand.klingel_abstand_s) || 5) * 1000);
  }

  function applyTimer(stand) {
    if (!stand) {
      return;
    }
    var neu = !timerStand || timerStand.seq !== stand.seq;
    timerStand = stand;
    timerEmpfangen = jetztMs();
    // Eine echte Aenderung (gestellt, verlaengert, geloescht) weckt: das
    // Tablet zeigt so, ob Gaston richtig verstanden hat.
    if (neu && stand.seq !== undefined) {
      wakeScreen();
    }
    for (var id in timerRing) {
      if (timerRing.hasOwnProperty(id)) {
        var t = findeTimer(id);
        if (!t || timerRest(t) > 0) {
          stopRing(id);
          delete timerRing[id];     // verlaengert: neuer Ablauf darf wieder klingeln
        } else if (t.still) {
          stopRing(id);
        }
      }
    }
    renderTimers();
  }

  function zeitText(sek) {
    var s = Math.floor(Math.abs(sek));
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var r = s % 60;
    var mm = (h > 0 && m < 10 ? "0" : "") + m;
    return (h > 0 ? h + ":" : "") + mm + ":" + (r < 10 ? "0" : "") + r;
  }

  function renderTimers() {
    var box = byId("timers");
    var liste = (timerStand && timerStand.timer) || [];
    var html = "";
    var nachlauf = (timerStand && timerStand.nachlauf_max_s) || 1800;
    for (var i = 0; i < liste.length; i++) {
      var t = liste[i];
      var rest = timerRest(t);
      if (rest < -nachlauf) {
        continue;
      }
      var name = t.name ? escapeHtml(t.name) : "Timer";
      var cls = "timer";
      var zeit;
      if (rest > 0) {
        // Aufrunden: "0:01" bis zum Schluss, nie "0:00" vor dem Ablauf.
        zeit = zeitText(Math.ceil(rest));
      } else {
        cls += " timer-ab";
        if (timerRing[t.id] && timerRing[t.id].handle) {
          cls += " timer-klingelt";
        }
        zeit = "&minus;" + zeitText(rest);
      }
      html += '<div class="' + cls + '"><span class="timer-name">' + name
            + '</span><span class="timer-zeit">' + zeit + "</span></div>";
      if (rest <= 0 && !t.still && !timerRing[t.id]) {
        startRing(t);
      }
    }
    box.innerHTML = html;
    box.style.display = html ? "block" : "none";
  }

  // --- Bildschirm-Dimmen (siehe DIM_AFTER_MS oben) ---

  function dimScreen() {
    dimmed = true;
    byId("dim-overlay").className = "dim-on";
  }

  function resetDimTimer() {
    if (dimTimer) {
      clearTimeout(dimTimer);
    }
    dimTimer = setTimeout(dimScreen, DIM_AFTER_MS);
  }

  // Weckt den Bildschirm und startet die Inaktivitaets-Uhr neu. Gibt true
  // zurueck, wenn dabei ein gedimmter Zustand aufgehoben wurde - dann soll der
  // ausloesende Tap NUR wecken und nicht zusaetzlich navigieren/oeffnen.
  function wakeScreen() {
    var wasDimmed = dimmed;
    if (dimmed) {
      dimmed = false;
      byId("dim-overlay").className = "";
    }
    resetDimTimer();
    return wasDimmed;
  }

  // Erster Tap auf das gedimmte Overlay: nur wecken, Ereignis schlucken.
  function onOverlayTap(e) {
    wakeScreen();
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    if (e && e.stopPropagation) {
      e.stopPropagation();
    }
    return false;
  }

  // Jede Interaktion mit der App (Taps auf Zonen/Kacheln) haelt den Screen wach.
  function onActivity() {
    wakeScreen();
  }

  function stateSignature(data) {
    var list = (data && data.list) || [];
    return (data && data.active ? data.active : "") + "::" + list.join(",");
  }

  function connectWs() {
    var proto = (window.location.protocol === "https:") ? "wss:" : "ws:";
    var url = proto + "//" + window.location.host + "/ws";

    try {
      ws = new WebSocket(url);
    } catch (e) {
      startPolling();
      return;
    }

    ws.onopen = function () {
      stopPolling();
    };

    ws.onmessage = function (evt) {
      try {
        var data = JSON.parse(evt.data);
        if (data && data.type === "timer") {
          applyTimer(data.timer);
          return;
        }
        if (data && data.type === "reload") {
          window.location.reload(true);
          return;
        }
        applyState(data);
      } catch (e) {
        // ignorieren
      }
    };

    ws.onclose = function () {
      ws = null;
      startPolling();
      // Reconnect-Versuch nach kurzer Pause.
      setTimeout(connectWs, 5000);
    };

    ws.onerror = function () {
      // onclose wird i.d.R. ebenfalls ausgeloest; Polling als Fallback
      // sicherheitshalber direkt starten.
      startPolling();
    };
  }

  // --- Rendering ---

  // Cache der berechneten Seiten: { key: "<name>|<breite>x<hoehe>", pages: [html,...] }.
  // Die Seiten haengen vom Inhalt UND von der Fenstergroesse ab (Hochformat
  // packt anders als Querformat), daher die Groesse im Key.
  var pagesCache = null;
  var resizeTimer = null;

  function applyState(data) {
    // DESIGN-ENTSCHEIDUNG: Die Seiten-Navigation (state.card) ist rein
    // clientseitig - der Server kennt "card" nur als Reset-auf-0 beim
    // Aktivieren eines Rezepts (siehe /api/active). Bei einem eingehenden
    // Broadcast/Poll behalten wir die lokal navigierte Seite bei, ausser
    // der Server hat ein ANDERES Rezept aktiviert (dann ist 0 korrekt).
    var localCard = (state && state.active === data.active) ? state.card : data.card;
    if (data.timer !== undefined) {
      applyTimer(data.timer);
    }
    state = data;
    if (localCard !== undefined && localCard !== null) {
      state.card = localCard;
    }
    if (state.active === null || state.active === undefined) {
      renderList();
    } else {
      renderActive();
    }

    // Echte Aenderung (z.B. Gaston springt zu Rezept X oder setzt die Auswahl)
    // weckt den Bildschirm; identische Poll-Antworten tun das bewusst nicht.
    var sig = stateSignature(data);
    if (lastSig !== null && sig !== lastSig) {
      wakeScreen();
    }
    lastSig = sig;
  }

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function renderList() {
    byId("list-view").style.display = "block";
    byId("active-view").style.display = "none";

    var tiles = byId("tiles");
    tiles.innerHTML = "";

    var list = (state && state.list) || [];

    if (list.length === 0) {
      var empty = document.createElement("div");
      empty.className = "list-empty";
      empty.innerHTML = "Keine Rezepte ausgewählt.";
      tiles.appendChild(empty);
      return;
    }

    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      var tile = document.createElement("div");
      tile.className = "tile";
      tile.setAttribute("data-name", entry.name);

      var emoji = document.createElement("div");
      emoji.className = "tile-emoji";
      emoji.innerHTML = entry.emoji ? escapeHtml(entry.emoji) : "🍽";
      tile.appendChild(emoji);

      var title = document.createElement("div");
      title.className = "tile-title";
      title.innerHTML = escapeHtml(entry.title);
      tile.appendChild(title);

      tile.onclick = makeTileHandler(entry.name);
      tiles.appendChild(tile);
    }
  }

  function makeTileHandler(name) {
    return function () {
      xhrPost("/api/active", { recipe: name });
    };
  }

  // --- Seitenaufbau & Hoehen-Pagination ---

  function findIngredientsCard(recipe) {
    for (var i = 0; i < recipe.cards.length; i++) {
      if (recipe.cards[i].type === "ingredients") {
        return recipe.cards[i];
      }
    }
    return null;
  }

  function buildTitlePage(recipe) {
    var html = '<div class="title-page">';
    if (recipe.image) {
      html += '<img class="title-image" src="' + recipe.image + '" alt="">';
    } else {
      html += '<div class="title-emoji">' + (recipe.icon ? escapeHtml(recipe.icon) : "🍽") + "</div>";
    }
    html += '<div class="title-name">' + escapeHtml(recipe.title) + "</div>";
    if (recipe.badges && recipe.badges.length > 0) {
      html += '<div class="title-badges">';
      for (var b = 0; b < recipe.badges.length; b++) {
        html += '<div class="title-badge">'
              + escapeHtml(recipe.badges[b].key) + ": "
              + escapeHtml(recipe.badges[b].value) + "</div>";
      }
      html += "</div>";
    }
    html += "</div>";
    return html;
  }

  function groupHeadingHtml(text) {
    return '<div class="ingredient-group-heading">' + escapeHtml(text) + "</div>";
  }

  // Zutaten als getaggte "Zeilen": kind 'sec' (Zutaten-Ueberschrift),
  // 'gh' (Gruppen-Ueberschrift) oder 'item'. gh traegt den Gruppen-Titel
  // mit, damit er bei einem Seitenumbruch wiederholt werden kann.
  function buildIngredientRows(card) {
    var rows = [{ kind: "sec", gh: null, html: '<div class="section-heading">Zutaten</div>' }];
    for (var g = 0; g < card.groups.length; g++) {
      var group = card.groups[g];
      if (group.heading) {
        rows.push({ kind: "gh", gh: group.heading, html: groupHeadingHtml(group.heading) });
      }
      for (var it = 0; it < group.items.length; it++) {
        // Items koennen <strong>...</strong> vom Server enthalten -> nicht escapen.
        rows.push({
          kind: "item",
          gh: group.heading || null,
          html: '<div class="ingredient-item">' + group.items[it] + "</div>"
        });
      }
    }
    return rows;
  }

  function rowsHtml(rows) {
    var s = "";
    for (var i = 0; i < rows.length; i++) {
      s += rows[i].html;
    }
    return s;
  }

  function isHeadingRow(row) {
    return row.kind === "gh" || row.kind === "sec";
  }

  // Gruppenbewusste Hoehen-Pagination der Zutaten: Ueberschriften stehen nie
  // allein am Seitenende (sie wandern auf die naechste Seite) und werden bei
  // einer ueber den Umbruch laufenden Gruppe oben wiederholt.
  function paginateIngredientRows(rows) {
    var cc = byId("card-content");
    if (!cc || cc.clientHeight < 50) {
      return rows.length ? [rowsHtml(rows)] : [];
    }
    var pages = [];
    var current = [];
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      cc.innerHTML = rowsHtml(current) + row.html;
      if (cc.scrollHeight > cc.clientHeight && current.length > 0) {
        // Abschliessende Ueberschriften nicht am Seitenende lassen -> mitnehmen.
        var carry = [];
        while (current.length > 0 && isHeadingRow(current[current.length - 1])) {
          carry.unshift(current.pop());
        }
        if (current.length > 0) {
          pages.push(rowsHtml(current));
        }
        current = carry;
        // Laeuft eine Gruppe ueber den Umbruch, ihre Ueberschrift oben wiederholen.
        if (row.kind === "item" && row.gh) {
          var hasGh = false;
          for (var c = 0; c < current.length; c++) {
            if (current[c].kind === "gh" && current[c].gh === row.gh) {
              hasGh = true;
              break;
            }
          }
          if (!hasGh) {
            current.push({ kind: "gh", gh: row.gh, html: groupHeadingHtml(row.gh) });
          }
        }
        current.push(row);
      } else {
        current.push(row);
      }
    }
    if (current.length > 0) {
      pages.push(rowsHtml(current));
    }
    cc.innerHTML = "";
    return pages;
  }

  function buildStepBlocks(recipe) {
    var blocks = [];
    for (var i = 0; i < recipe.cards.length; i++) {
      var card = recipe.cards[i];
      if (card.type !== "step") {
        continue;
      }
      // card.text kann <strong>...</strong> vom Server enthalten -> nicht escapen.
      blocks.push('<div class="step"><span class="step-num">' + card.n + ".</span> "
                + '<span class="step-text">' + card.text + "</span></div>");
    }
    return blocks;
  }

  // Verteilt eine Liste von Block-HTML-Strings auf so wenige Seiten wie
  // moeglich, indem real im #card-content gemessen wird: Bloecke werden
  // angehaengt, bis es ueberlaeuft, dann beginnt eine neue Seite.
  function paginateBlocks(blocks) {
    var cc = byId("card-content");
    // Falls das Layout noch nicht steht (Hoehe unbekannt), nicht aufteilen.
    if (!cc || cc.clientHeight < 50) {
      return blocks.length ? [blocks.join("")] : [];
    }
    var pages = [];
    var current = "";
    for (var i = 0; i < blocks.length; i++) {
      var candidate = current + blocks[i];
      cc.innerHTML = candidate;
      if (cc.scrollHeight > cc.clientHeight && current !== "") {
        // Passt nicht mehr: aktuelle Seite abschliessen, neue mit diesem Block.
        pages.push(current);
        current = blocks[i];
      } else {
        current = candidate;
      }
    }
    if (current !== "") {
      pages.push(current);
    }
    cc.innerHTML = "";
    return pages;
  }

  function pageKey(recipe) {
    return recipe.name + "|" + window.innerWidth + "x" + window.innerHeight;
  }

  function computePages(recipe) {
    var pages = [];
    pages.push(buildTitlePage(recipe));

    var ing = findIngredientsCard(recipe);
    if (ing) {
      var ingPages = paginateIngredientRows(buildIngredientRows(ing));
      for (var a = 0; a < ingPages.length; a++) {
        pages.push('<div class="content-page">' + ingPages[a] + "</div>");
      }
    }

    var stepPages = paginateBlocks(buildStepBlocks(recipe));
    for (var b = 0; b < stepPages.length; b++) {
      pages.push('<div class="content-page">' + stepPages[b] + "</div>");
    }

    return pages;
  }

  function getPages(recipe) {
    var key = pageKey(recipe);
    if (pagesCache && pagesCache.key === key) {
      return pagesCache.pages;
    }
    var pages = computePages(recipe);
    pagesCache = { key: key, pages: pages };
    return pages;
  }

  function renderActive() {
    byId("list-view").style.display = "none";
    byId("active-view").style.display = "block";

    var recipe = state.recipe;
    var content = byId("card-content");
    var indicator = byId("card-indicator");
    var headerTitle = byId("header-title");

    if (!recipe || !recipe.cards || recipe.cards.length === 0) {
      content.innerHTML = '<div class="section-heading">Rezept nicht verfügbar</div>';
      indicator.innerHTML = "";
      headerTitle.innerHTML = "";
      return;
    }

    var pages = getPages(recipe);
    var idx = state.card || 0;
    if (idx < 0) {
      idx = 0;
    }
    if (idx > pages.length - 1) {
      idx = pages.length - 1;
    }
    state.card = idx;

    content.innerHTML = pages[idx];
    indicator.innerHTML = (idx + 1) + " / " + pages.length;
    // Auf der Titelseite (idx 0) steht der Titel gross im Inhalt -> Kopf frei.
    headerTitle.innerHTML = (idx === 0) ? "" : escapeHtml(recipe.title);
  }

  // --- Navigation ---

  function goBackToList() {
    xhrPost("/api/active", { recipe: null });
  }

  function navigate(delta) {
    if (!state || state.active === null || state.active === undefined) {
      return;
    }
    var recipe = state.recipe;
    if (!recipe) {
      return;
    }
    var pages = getPages(recipe);
    var idx = (state.card || 0) + delta;
    if (idx < 0) {
      idx = 0;
    }
    if (idx > pages.length - 1) {
      idx = pages.length - 1;
    }
    if (idx === state.card) {
      return;
    }
    state.card = idx;
    renderActive();
  }

  function onResize() {
    // Beim Drehen aendert sich die verfuegbare Hoehe -> Seiten neu berechnen.
    if (resizeTimer) {
      clearTimeout(resizeTimer);
    }
    resizeTimer = setTimeout(function () {
      pagesCache = null;
      if (state && state.active !== null && state.active !== undefined) {
        renderActive();
      }
    }, 200);
  }

  // --- Setup ---

  function init() {
    byId("zone-left").onclick = function () { navigate(-1); };
    byId("zone-right").onclick = function () { navigate(1); };
    byId("zone-back").onclick = goBackToList;

    window.onresize = onResize;
    if (window.addEventListener) {
      window.addEventListener("orientationchange", onResize, false);
    }

    // Dim-Overlay: erster Tap weckt nur (schluckt das Ereignis).
    var overlay = byId("dim-overlay");
    overlay.onclick = onOverlayTap;
    if (overlay.addEventListener) {
      overlay.addEventListener("touchstart", onOverlayTap, false);
    }

    // Jede Interaktion mit der App haelt den Bildschirm wach. Im gedimmten
    // Zustand faengt das Overlay den Tap ab (stopPropagation), darum erreichen
    // diese Listener nur Taps bei wachem Schirm -> sie verschieben das Dimmen.
    if (document.addEventListener) {
      document.addEventListener("touchstart", onActivity, false);
      document.addEventListener("mousedown", onActivity, false);
    } else {
      document.onmousedown = onActivity;
    }

    // Inaktivitaets-Uhr starten.
    resetDimTimer();

    // Timer-Anzeige jede Viertelsekunde neu (nur Text, billig).
    setInterval(renderTimers, 250);

    // Initialen State laden, dann WS verbinden (mit Polling-Fallback).
    xhrGetState();
    connectWs();
  }

  if (document.readyState === "complete" || document.readyState === "interactive") {
    init();
  } else {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
