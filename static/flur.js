/* Flur-Tablet - ES5 fuer die alte WebView (siehe app.js fuer die Regeln:
 * keine arrow functions, kein fetch, kein Framework).
 *
 * HA laeuft im iframe #ha und wird nur einmal geladen. Das Kalender-Overlay
 * #kal liegt darueber; was und wann angezeigt wird, entscheidet der Server
 * (Node-RED -> /api/flur/show). Zeiten kommen vom Server; die Tablet-Uhr wird
 * nur ueber den Versatz zur Serverzeit benutzt (nach leerem Akku oft falsch).
 */

(function () {
  "use strict";

  var data = null;
  var ws = null;
  var pollTimer = null;
  var POLL_INTERVAL = 3000;
  // Auch mit WS regelmaessig neu holen: der Tageswechsel kommt ohne Push.
  var REFRESH_INTERVAL = 300000;
  var HEARTBEAT_INTERVAL = 20000;
  var HEARTBEAT_TIMEOUT = 50000;
  var heartbeatTimer = null;
  var lastWsRx = 0;

  var uhrVersatz = 0;      // Serverzeit - Tabletzeit in ms
  var hideTimer = null;
  var haUrl = null;
  var lokalVersteckt = null;  // gezeigt_um, das per Tap schon weggetippt wurde

  function byId(id) {
    return document.getElementById(id);
  }

  function jetzt() {
    return new Date().getTime() + uhrVersatz;
  }

  function esc(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  // --- Netz ---

  function xhrGetState() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/flur/state", true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState === 4 && xhr.status === 200) {
        try {
          apply(JSON.parse(xhr.responseText));
        } catch (e) {
          // naechster Poll korrigiert
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
  }

  function startPolling() {
    if (!pollTimer) {
      pollTimer = setInterval(xhrGetState, POLL_INTERVAL);
    }
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function connectWs() {
    var url = (location.protocol === "https:" ? "wss:" : "ws:") + "//" + location.host + "/ws/flur";
    var sock;
    try {
      sock = new WebSocket(url);
    } catch (e) {
      startPolling();
      setTimeout(connectWs, 5000);
      return;
    }
    ws = sock;
    sock.onopen = function () {
      stopPolling();
      lastWsRx = new Date().getTime();
      startHeartbeat(sock);
    };
    sock.onmessage = function (evt) {
      lastWsRx = new Date().getTime();
      try {
        var msg = JSON.parse(evt.data);
        if (msg.type === "pong") {
          return;
        }
        if (msg.type === "reload") {
          if (msg.ziel === "ha") {
            ladeHa(true);
          } else {
            location.reload(true);
          }
          return;
        }
        apply(msg);
      } catch (e) {
        // ignorieren
      }
    };
    sock.onclose = function () {
      dropWs(sock);
    };
    sock.onerror = function () {
      startPolling();
    };
  }

  function dropWs(sock) {
    if (ws !== sock) {
      return;
    }
    ws = null;
    stopHeartbeat();
    sock.onopen = sock.onmessage = sock.onclose = sock.onerror = null;
    try {
      sock.close();
    } catch (e) {
      // ignorieren
    }
    startPolling();
    setTimeout(connectWs, 5000);
  }

  function startHeartbeat(sock) {
    stopHeartbeat();
    heartbeatTimer = setInterval(function () {
      if (new Date().getTime() - lastWsRx > HEARTBEAT_TIMEOUT) {
        dropWs(sock);
        return;
      }
      try {
        sock.send("ping");
      } catch (e) {
        dropWs(sock);
      }
    }, HEARTBEAT_INTERVAL);
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  // --- HA-iframe ---

  function ladeHa(neu) {
    var f = byId("ha");
    if (!haUrl) {
      return;
    }
    if (neu || f.getAttribute("data-geladen") !== haUrl) {
      f.setAttribute("data-geladen", haUrl);
      f.src = haUrl;
    }
  }

  // --- Zustand anwenden ---

  function apply(d) {
    if (!d || d.type !== "flur") {
      return;
    }
    data = d;
    uhrVersatz = d.jetzt_ms - new Date().getTime();
    if (d.ha_url && d.ha_url !== haUrl) {
      haUrl = d.ha_url;
      ladeHa(false);
    }
    var ov = d.overlay || {};
    // Ein frisch weggetippter Zustand bleibt weg, auch wenn ein Poll noch den
    // alten Stand bringt; ein NEUES show (anderes gezeigt_um) zeigt wieder.
    var sichtbar = !!ov.view && ov.rest_s !== 0 && lokalVersteckt !== ov.gezeigt_um;
    if (sichtbar) {
      renderKalender(d.kalender);
      zeigen(ov.rest_s);
    } else {
      verbergen();
    }
  }

  function zeigen(restS) {
    byId("kal").style.display = "block";
    byId("kal-knopf").style.display = "none";
    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
    // Eigene Ablaufuhr, falls die Verbindung zum Server gerade weg ist.
    if (restS) {
      hideTimer = setTimeout(verbergen, restS * 1000 + 2000);
    }
    passeFokusAn();
    tick();
  }

  function verbergen() {
    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
    byId("kal").style.display = "none";
    byId("kal-knopf").style.display = "block";
  }

  function onKalTap(e) {
    if (data && data.overlay) {
      lokalVersteckt = data.overlay.gezeigt_um;
    }
    verbergen();
    xhrPost("/api/flur/hide", { grund: "tap" });
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    return false;
  }

  function onKnopfTap(e) {
    lokalVersteckt = null;
    xhrPost("/api/flur/show", { view: "kalender", fokus: "heute", seconds: 300 });
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    return false;
  }

  // --- Rendern ---

  function renderKalender(k) {
    if (!k) {
      return;
    }
    byId("fokus-titel").innerHTML = esc(k.fokus_titel);
    byId("fokus-datum").innerHTML = esc(k.fokus_datum);

    // Fokus-Tag
    var h = "";
    var evs = k.fokus_events || [];
    for (var i = 0; i < evs.length; i++) {
      var e = evs[i];
      h += '<div class="ev' + (e.wichtig ? " wichtig" : "") + '" data-start="' + e.start_ms
         + '" data-ende="' + e.ende_ms + '" data-ganztags="' + (e.ganztags ? 1 : 0) + '">'
         + '<span class="ev-zeit">' + esc(e.zeit) + "</span>"
         + '<span class="ev-text"><span class="ev-titel">' + esc(e.titel) + "</span>";
      if (e.ort) {
        h += '<span class="ev-ort">' + esc(e.ort) + "</span>";
      }
      if (e.hinweis) {
        h += '<span class="ev-hinweis">' + esc(e.hinweis) + "</span>";
      }
      h += "</span></div>";
    }
    if (!evs.length) {
      h = '<div class="fokus-leer">Keine Termine.</div>';
    }
    byId("fokus").innerHTML = h;

    // Wochenleiste
    var w = "";
    var tage = k.woche || [];
    for (var t = 0; t < tage.length; t++) {
      var tag = tage[t];
      w += '<div class="tag' + (tag.wochenende ? " we" : "") + '">'
         + '<div class="tag-inhalt"><div class="tag-kopf">' + esc(tag.wt) + " " + esc(tag.datum) + "</div>";
      var max = tag.events.length > 4 ? 3 : 4;
      for (var j = 0; j < tag.events.length && j < max; j++) {
        var te = tag.events[j];
        w += '<div class="tag-ev' + (te.wichtig ? " wichtig" : "") + '">'
           + (te.zeit ? '<span class="z">' + esc(te.zeit) + "</span>" : "")
           + esc(te.titel) + "</div>";
      }
      if (tag.events.length > max) {
        w += '<div class="tag-mehr">+' + (tag.events.length - max) + " weitere</div>";
      }
      w += "</div></div>";
    }
    byId("woche").innerHTML = w;

    // Demnaechst in zwei Spalten
    var dn = k.demnaechst || [];
    byId("dn-titel").innerHTML = "Demnächst" + (k.demnaechst_mehr ? " (+" + k.demnaechst_mehr + " weitere)" : "");
    var d = "";
    if (!dn.length) {
      d += '<div class="dn">Keine weiteren Termine in den nächsten Wochen.</div>';
    } else {
      var haelfte = Math.ceil(dn.length / 2);
      for (var s = 0; s < 2; s++) {
        d += '<div class="dn-spalte">';
        for (var n = s * haelfte; n < Math.min(dn.length, (s + 1) * haelfte); n++) {
          var x = dn[n];
          d += '<div class="dn' + (x.wichtig ? " wichtig" : "") + '"><span class="d">'
             + esc(x.datum) + "</span>"
             + (x.zeit ? '<span class="z">' + esc(x.zeit) + "</span>" : "")
             + esc(x.titel) + "</div>";
        }
        d += "</div>";
      }
    }
    byId("demnaechst").innerHTML = d;

    // Kalender-Stand: veraltete Daten sichtbar machen statt still falsch zu sein.
    var st = byId("stand");
    var alter = k.stand_alter_s;
    if (alter === null || alter === undefined) {
      st.innerHTML = "Kalender noch nicht geladen";
      st.className = "alt";
    } else if (alter > 3 * 3600) {
      st.innerHTML = "Achtung: Kalender-Stand vor " + Math.round(alter / 3600) + " Std.";
      st.className = "alt";
    } else {
      st.innerHTML = "";
      st.className = "";
    }
  }

  // Schrift im Fokus-Bereich so gross wie moeglich, ohne abzuschneiden.
  function passeFokusAn() {
    var f = byId("fokus");
    var stufen = ["", "gr-m", "gr-s"];
    for (var i = 0; i < stufen.length; i++) {
      f.className = stufen[i];
      if (f.scrollHeight <= f.clientHeight + 2) {
        return;
      }
    }
  }

  function zwei(n) {
    return (n < 10 ? "0" : "") + n;
  }

  // Uhr und laufende/vergangene Termine, jede Sekunde (nur bei sichtbarem Overlay).
  function tick() {
    if (byId("kal").style.display === "none") {
      return;
    }
    var j = jetzt();
    // Die Serverzeit ist UTC-basiert; die Anzeige-Zeitzone kennt das Tablet
    // selbst (Android). Fuer die Uhr reicht das.
    var d = new Date(j);
    byId("uhr").innerHTML = d.getHours() + ":" + zwei(d.getMinutes());
    var evs = byId("fokus").childNodes;
    for (var i = 0; i < evs.length; i++) {
      var el = evs[i];
      if (!el.getAttribute || !el.getAttribute("data-start")) {
        continue;
      }
      var s = +el.getAttribute("data-start");
      var e = +el.getAttribute("data-ende");
      var basis = el.className.replace(/\s*(jetzt|vorbei)/g, "");
      if (e > s && j >= e) {
        el.className = basis + " vorbei";
      } else if (j >= s && (j < e || e === s) && el.getAttribute("data-ganztags") !== "1") {
        el.className = basis + (e > s ? " jetzt" : "");
      } else {
        el.className = basis;
      }
    }
  }

  // --- Setup ---

  function init() {
    var kal = byId("kal");
    kal.onclick = onKalTap;
    byId("kal-knopf").onclick = onKnopfTap;
    // Vorab aus der URL, damit HA schon laedt, bevor der Server antwortet.
    var m = /[?&]ha=([^&]*)/.exec(location.search);
    if (m) {
      haUrl = decodeURIComponent(m[1]);
      ladeHa(false);
    }
    setInterval(tick, 1000);
    setInterval(xhrGetState, REFRESH_INTERVAL);
    xhrGetState();
    connectWs();
  }

  if (document.readyState === "complete" || document.readyState === "interactive") {
    init();
  } else {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
