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

  function applyState(data) {
    // DESIGN-ENTSCHEIDUNG: Die Karten-Navigation (state.card) ist rein
    // clientseitig - der Server kennt "card" nur als Reset-auf-0 beim
    // Aktivieren eines Rezepts (siehe /api/active). Bei einem eingehenden
    // Broadcast/Poll behalten wir die lokal navigierte Karte bei, ausser
    // der Server hat ein ANDERES Rezept aktiviert (dann ist 0 korrekt).
    var localCard = (state && state.active === data.active) ? state.card : data.card;
    state = data;
    if (localCard !== undefined && localCard !== null) {
      state.card = localCard;
    }
    if (state.active === null || state.active === undefined) {
      renderList();
    } else {
      renderActive();
    }
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

  function renderActive() {
    byId("list-view").style.display = "none";
    byId("active-view").style.display = "block";

    var recipe = state.recipe;
    var content = byId("card-content");
    var indicator = byId("card-indicator");

    if (!recipe || !recipe.cards || recipe.cards.length === 0) {
      content.innerHTML = '<div class="card-heading">Rezept nicht verfügbar</div>';
      indicator.innerHTML = "";
      return;
    }

    var cards = recipe.cards;
    var idx = state.card || 0;
    if (idx < 0) {
      idx = 0;
    }
    if (idx >= cards.length) {
      idx = cards.length - 1;
    }

    var card = cards[idx];
    indicator.innerHTML = (idx + 1) + " / " + cards.length;

    var html = "";

    if (card.type === "ingredients") {
      html += '<div class="card-heading">' + escapeHtml(recipe.title) + " - Zutaten</div>";

      if (recipe.badges && recipe.badges.length > 0) {
        html += '<div class="badges">';
        for (var b = 0; b < recipe.badges.length; b++) {
          html += '<span class="badge">' + escapeHtml(recipe.badges[b].key) + ": "
                + escapeHtml(recipe.badges[b].value) + "</span>";
        }
        html += "</div>";
      }

      for (var g = 0; g < card.groups.length; g++) {
        var group = card.groups[g];
        if (group.heading) {
          html += '<div class="ingredient-group-heading">' + escapeHtml(group.heading) + "</div>";
        }
        html += '<ul class="ingredient-list">';
        for (var it = 0; it < group.items.length; it++) {
          // Items koennen bereits <strong>...</strong> enthalten (vom
          // Server-Parser erzeugt) - bewusst NICHT escapen, nur die
          // gewuenschten Tags sind enthalten.
          html += "<li>" + group.items[it] + "</li>";
        }
        html += "</ul>";
      }
    } else if (card.type === "step") {
      html += '<div class="card-heading">Schritt ' + card.n + " / " + countSteps(cards) + "</div>";
      html += '<div class="card-step-text">' + card.text + "</div>";
    } else {
      html += '<div class="card-heading">' + escapeHtml(recipe.title) + "</div>";
    }

    content.innerHTML = html;
  }

  function countSteps(cards) {
    var n = 0;
    for (var i = 0; i < cards.length; i++) {
      if (cards[i].type === "step") {
        n++;
      }
    }
    return n;
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
    if (!recipe || !recipe.cards) {
      return;
    }
    var idx = (state.card || 0) + delta;
    if (idx < 0) {
      idx = 0;
    }
    if (idx > recipe.cards.length - 1) {
      idx = recipe.cards.length - 1;
    }
    if (idx === state.card) {
      return;
    }
    // Lokal sofort weiterblaettern (fluessiges Gefuehl), Server-State per
    // erneutes /api/active wird NICHT genutzt fuer Karten-Navigation - der
    // Server kennt "card" nur als Teil des State, das die UI lokal mit
    // verwaltet und beim naechsten /api/state/Broadcast wieder abgleicht.
    state.card = idx;
    renderActive();
  }

  // --- Setup ---

  function init() {
    byId("zone-left").onclick = function () { navigate(-1); };
    byId("zone-right").onclick = function () { navigate(1); };
    byId("zone-back").onclick = goBackToList;

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
