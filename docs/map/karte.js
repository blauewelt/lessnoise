/* The noise map (docs/karte.html): Leaflet, swisstopo's grey map, one quadrilateral per
 * hectare cell, coloured by the chosen metric over the chosen period (last 7 or 30 days);
 * a card per cell with the 24-hour profile; the manual reports as small lettered markers.
 *
 * Reads only the published files of server/DESIGN.md §6 from the base URL in the map
 * element's `data-api` attribute (falling back to `data-api-fallback` once if the first host
 * cannot be reached at all): cells.json (windows ending today), days/{date}.json (windows
 * ending an earlier day, 404 when the server has none), cells/{cell}.json (one cell's days and
 * its 24-hour profile) and reports.json (the reports of the last 30 days). Depends on
 * window.L (vendored Leaflet 1.9.4), window.Lv95 (lv95.js) and window.KarteModel (model.js).
 * No inline script, no innerHTML with data: the page runs under a CSP without 'unsafe-inline'.
 */
(function () {
  "use strict";

  var M = window.KarteModel;
  var Lv95 = window.Lv95;
  var L = window.L;

  var TILES = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg";
  var BOUNDS = [[45.80, 5.90], [47.85, 10.55]];  // Switzerland, [S, W], [N, E]
  var START = [[47.30, 8.42], [47.45, 8.65]];    // first view without data: Zürich
  var MIN_ZOOM = 7;          // the whole country on a phone
  var MAX_ZOOM = 18;
  var DOT_MAX_ZOOM = 13;     // up to this zoom a hectare is ≤ 8 px: also draw a dot
  var HISTORY_DAYS = 90;     // DESIGN §7: ‹ › through the last 90 days
  var SVGNS = "http://www.w3.org/2000/svg";

  var mapEl = document.getElementById("karte");
  function base(url) { return url ? url.replace(/\/?$/, "/") : null; }
  var api = base(mapEl.getAttribute("data-api") || "https://api.lessnoise.ch/v1/map/");
  var apiFallback = base(mapEl.getAttribute("data-api-fallback"));
  var apiSettled = false;    // true once a host has answered; no switching after that

  var darkMq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  var state = {
    metric: "laeq",
    source: "alle",   // «Quelle»: "alle" or one of the five map categories (KarteModel.SOURCES)
    period: "last_7_days",
    latest: null,       // the day of cells.json (today)
    day: null,          // the last day of the period shown
    file: null,         // the file shown (cells.json or days/{date}.json), or null
    byCell: {},         // cell id -> entry of the shown file
    layers: {},         // cell id -> { poly, dot }
    selected: null,     // cell id of the open card
    history: {},        // cell id -> Promise of cells/{cell}.json
    loadSeq: 0,
    loudDb: 75,         // the server's LOUD_EVENT_DB (cells.json `loud_event_db`)
    reports: null,      // reports.json `reports`, or null while not loaded
    showReports: true,
    periodChosen: false,
    countedSince: null, // cells.json `counted_since` (DESIGN §6), for a day file without it
  };

  /* ---------- small DOM helpers ---------- */

  function el(tag, attrs, children) {
    var n = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (attrs[k] === null || attrs[k] === undefined) return;
      if (k === "text") n.textContent = attrs[k];
      else if (k === "class") n.className = attrs[k];
      else n.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) {
      if (c !== null && c !== undefined) n.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return n;
  }

  function svg(tag, attrs, children) {
    var n = document.createElementNS(SVGNS, tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "text") n.textContent = attrs[k];
      else n.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) n.appendChild(c); });
    return n;
  }

  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }

  function setStatus(text) {
    var s = document.getElementById("status");
    s.textContent = text || "";
    s.hidden = !text;
  }

  function dark() { return !!(darkMq && darkMq.matches); }
  /** What the cells are coloured by: the metric, or one source's events/h (KarteModel.metricFor). */
  function curMetric() { return M.metricFor(state.metric, state.source); }
  function sourceOf() { return M.source(state.source); }
  function sourceColour(s) { return dark() ? s.colourDark : s.colour; }
  function period() { return M.PERIODS[state.period]; }
  function blockOf(entry) { return entry ? entry[state.period] : null; }
  function dbText(v) { return M.fmt1(v).replace(/,0$/, ""); }

  /* ---------- fetching ---------- */

  function fetchFrom(root, path, cache) {
    var opts = { credentials: "omit", referrerPolicy: "no-referrer" };
    if (cache) opts.cache = cache;
    return fetch(root + path, opts).then(function (r) {
      apiSettled = true;
      if (r.status === 404) { var e = new Error("404"); e.notFound = true; throw e; }
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  /** GET a map file. While no host has answered yet, a network failure of the first host
   *  (no DNS record, no certificate) switches to the fallback host for the rest of the visit. */
  function getJson(path, cache) {
    return fetchFrom(api, path, cache).catch(function (e) {
      if (apiSettled || !apiFallback || e.notFound || !(e instanceof TypeError)) throw e;
      api = apiFallback;
      apiFallback = null;
      return fetchFrom(api, path, cache);
    });
  }

  function history(cellId) {
    if (!state.history[cellId]) {
      state.history[cellId] = getJson("cells/" + cellId + ".json").catch(function (e) {
        delete state.history[cellId];
        throw e;
      });
    }
    return state.history[cellId];
  }

  /* ---------- the map ---------- */

  var map = L.map(mapEl, {
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    maxBounds: BOUNDS,
    maxBoundsViscosity: 1.0,
    zoomSnap: 0.5,
    keyboard: true,
    attributionControl: true,
  });
  // A view in the fragment (karte.html#lat=…&lon=…&z=…, server/DESIGN.md §7; the app's tab
  // «Karte» opens the map like this) wins over the default view and over fitting the data; it sets
  // only the view, no marker. Invalid or missing: the default view.
  var hashView = M.parseView(M.hashWithoutSource(location.hash), BOUNDS, MIN_ZOOM, MAX_ZOOM);
  // The chosen source is kept in the fragment too: karte.html#quelle=road_traffic, also after a
  // view (#lat=…&lon=…&z=…&quelle=…). Read here; set by setSource below.
  var hashSource = M.parseSource(location.hash);
  if (hashView) {
    map.setView([hashView.lat, hashView.lon], hashView.z, { animate: false });
    // A link to a place on the map: show the map, not the page head (on a phone the head fills
    // the first screen). The same as the plain anchor karte.html#metric does by itself.
    var first = document.getElementById("metric");
    if (first && first.scrollIntoView) first.scrollIntoView();
  } else {
    map.fitBounds(START);
  }
  function applyView(v) {
    // Leaflet ignores a view change while a zoom animation runs (e.g. the first fit to the data)
    // and would end that animation on its own target: wait for it.
    if (map._animatingZoom) { map.once("zoomend", function () { applyView(v); }); return; }
    map.setView([v.lat, v.lon], v.z, { animate: false });
  }
  window.addEventListener("hashchange", function () {
    var src = M.parseSource(location.hash);
    if (src && src !== state.source) {
      setSource(src, true);
      drawLegend();
      drawCells();
      if (state.selected) renderCard();
    }
    else if (!src && state.source !== M.ALL_SOURCES) writeSourceHash();   // e.g. the app replaced only the view
    var v = M.parseView(M.hashWithoutSource(location.hash), BOUNDS, MIN_ZOOM, MAX_ZOOM);
    if (!v) return;
    hashView = v;
    applyView(v);
  });
  map.createPane("reports").style.zIndex = 450;   // above the cells (400), below tooltips

  L.tileLayer(TILES, {
    attribution: "© swisstopo",
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    maxNativeZoom: 18,
    detectRetina: true,      // sharp tiles and normal-sized labels on phone screens
    bounds: BOUNDS,          // swisstopo answers 400 outside Switzerland; stay inside
    referrerPolicy: "no-referrer",
  }).addTo(map);

  var canHover = window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches;
  var tip = L.tooltip({ className: "cell-tip", direction: "top", offset: [0, -6], opacity: 1 });

  function valueText(m, block) {
    var v = m.get(block);
    if (v === null || v === undefined) return "kein Wert";
    return M.fmt1(v) + " " + m.unit;
  }

  function cellStyle(entry) {
    var fill = M.colourFor(curMetric(), blockOf(entry), dark());
    return { fillColor: fill || "#ffffff", fillOpacity: fill ? 0.78 : 0 };
  }

  function cellClasses(entry) {
    var c = ["cell"];
    if (!entry.calibrated) c.push("uncal");
    if (!M.colourFor(curMetric(), blockOf(entry))) c.push("nodata");
    if (entry.cell === state.selected) c.push("selected");
    return c.join(" ");
  }

  function applyClasses(layer, entry) {
    var p = layer.getElement && layer.getElement();
    if (p) {
      p.setAttribute("class", cellClasses(entry) + " leaflet-interactive");
      p.setAttribute("data-cell", entry.cell);
    }
  }

  function bindCell(layer, entry) {
    layer.on("click", function () { openCard(entry.cell, false); });
    if (canHover) {
      layer.on("mouseover", function () {
        var e = state.byCell[entry.cell];
        if (!e) return;
        tip.setLatLng(Lv95.cellCenter(entry.cell))
          .setContent(el("span", { text: valueText(curMetric(), blockOf(e)) + (e.calibrated ? "" : " · unkalibriert") }));
        map.openTooltip(tip);
      });
      layer.on("mouseout", function () { map.closeTooltip(tip); });
    }
  }

  function clearCells() {
    Object.keys(state.layers).forEach(function (id) {
      map.removeLayer(state.layers[id].poly);
      map.removeLayer(state.layers[id].dot);
    });
    state.layers = {};
  }

  function metricValue(entry) {
    var v = curMetric().get(blockOf(entry));
    return v === null || v === undefined ? null : v;
  }

  function drawCells() {
    clearCells();
    var cells = state.file ? state.file.cells : [];
    // quiet first, loud last, so the loud ones are drawn on top where dots overlap
    var sorted = cells.slice().sort(function (a, b) {
      var va = metricValue(a), vb = metricValue(b);
      return (va === null ? -1 : va) - (vb === null ? -1 : vb);
    });
    sorted.forEach(function (entry) {
      var corners;
      try { corners = Lv95.cellToCorners(entry.cell); } catch (e) { return; }
      var style = cellStyle(entry);
      var poly = L.polygon(corners, {
        color: "#17202e", weight: 1, fillColor: style.fillColor, fillOpacity: style.fillOpacity,
        className: cellClasses(entry), bubblingMouseEvents: false,
      });
      var dot = L.circleMarker(Lv95.cellCenter(entry.cell), {
        radius: 6, color: "#17202e", weight: 1, fillColor: style.fillColor,
        fillOpacity: style.fillOpacity ? 0.9 : 0, className: cellClasses(entry), bubblingMouseEvents: false,
      });
      bindCell(poly, entry);
      bindCell(dot, entry);
      poly.addTo(map);
      applyClasses(poly, entry);
      state.layers[entry.cell] = { poly: poly, dot: dot };
    });
    updateDots();
    fillList();
  }

  function restyleCells() {
    Object.keys(state.layers).forEach(function (id) {
      var entry = state.byCell[id];
      var s = cellStyle(entry);
      var l = state.layers[id];
      l.poly.setStyle({ fillColor: s.fillColor, fillOpacity: s.fillOpacity });
      l.dot.setStyle({ fillColor: s.fillColor, fillOpacity: s.fillOpacity ? 0.9 : 0 });
      applyClasses(l.poly, entry);
      applyClasses(l.dot, entry);
    });
  }

  function updateDots() {
    var show = map.getZoom() <= DOT_MAX_ZOOM;
    Object.keys(state.layers).forEach(function (id) {
      var l = state.layers[id];
      if (show && !map.hasLayer(l.dot)) { l.dot.addTo(map); applyClasses(l.dot, state.byCell[id]); }
      if (!show && map.hasLayer(l.dot)) map.removeLayer(l.dot);
    });
  }
  map.on("zoomend", updateDots);

  function fitToCells() {
    var cells = state.file ? state.file.cells : [];
    if (!cells.length) return;
    var b = L.latLngBounds([]);
    cells.forEach(function (c) {
      try { Lv95.cellToCorners(c.cell).forEach(function (p) { b.extend(p); }); } catch (e) { /* skip */ }
    });
    if (b.isValid()) map.fitBounds(b, { padding: [24, 24], maxZoom: 15 });
  }

  /* ---------- reports ---------- */

  var reportLayer = L.layerGroup().addTo(map);

  function drawReports() {
    reportLayer.clearLayers();
    var shown = state.reports && state.day ? M.reportsInPeriod(state.reports, state.day, period().days) : [];
    shown.forEach(function (r) {
      if (typeof r.lat !== "number" || typeof r.lon !== "number") return;
      var cat = M.reportCategory(r.category);
      var dir = M.reportDirection(r);
      // App 0.8.5: the peak level while the report dialog was open, whole dB, after the direction.
      var text = M.reportText(cat.name, M.hourLabel(r.time), dir, M.reportLevel(r));
      // App 0.6.7: a small arrowhead on the rim points where the vehicle drove. Rotated through
      // CSSOM (allowed by the page's CSP, unlike a style attribute).
      var arrow = null;
      if (dir !== null) {
        arrow = el("i", { class: "report-dir", "aria-hidden": "true", "data-direction": String(dir) });
        arrow.style.transform = "rotate(" + dir + "deg)";
      }
      var icon = L.divIcon({
        className: "report report-" + cat.id,
        html: el("span", { "aria-label": text, "data-category": cat.id, text: cat.letter }, [arrow]),
        iconSize: [18, 18],
        iconAnchor: [9, 9],
      });
      L.marker([r.lat, r.lon], { icon: icon, pane: "reports", keyboard: false, bubblingMouseEvents: false })
        .bindTooltip(el("span", { text: text }), { className: "cell-tip report-tip", direction: "top", offset: [0, -8], opacity: 1 })
        .addTo(reportLayer);
    });
    var count = document.getElementById("reports-count");
    if (count) {
      count.textContent = state.reports === null ? "Meldungen werden geladen …"
        : shown.length + (shown.length === 1 ? " Meldung" : " Meldungen") + " in diesem Zeitraum";
    }
  }

  function drawReportLegend() {
    var ul = document.getElementById("legend-reports");
    clear(ul);
    ul.appendChild(el("li", { class: "reports-head", id: "reports-count" }));
    M.REPORT_CATEGORIES.forEach(function (c) {
      ul.appendChild(el("li", null, [
        el("span", { class: "report report-" + c.id, "aria-hidden": "true" }, [el("span", { text: c.letter })]),
        c.name,
      ]));
    });
    ul.hidden = !state.showReports;
  }

  document.getElementById("reports-toggle").addEventListener("change", function (e) {
    state.showReports = e.target.checked;
    if (state.showReports) reportLayer.addTo(map);
    else map.removeLayer(reportLayer);
    document.getElementById("legend-reports").hidden = !state.showReports;
  });

  /* ---------- legend ---------- */

  function metricTitle(m) {
    return m.title.replace("{db}", dbText(state.loudDb));
  }

  function drawLegend() {
    var m = curMetric();
    document.getElementById("legend-title").textContent = metricTitle(m) + " · " + period().label;
    var bar = document.getElementById("legend-bar");
    clear(bar);
    (dark() && m.coloursDark ? m.coloursDark : m.colours).forEach(function (c) {
      var s = el("span");
      s.style.background = c;
      bar.appendChild(s);
    });
    var ticks = document.getElementById("legend-ticks");
    clear(ticks);
    m.ticks.forEach(function (v) {
      var t = el("span", { "data-value": String(v), text: M.fmt1(v).replace(/,0$/, "") });
      t.style.left = (M.tickPosition(m, v) * 100) + "%";
      ticks.appendChild(t);
    });
    var note = {
      laeq: "Die Farben sind bewusst neutral: Die Karte zeigt Messwerte, keine rechtliche Beurteilung. " +
        "Die Werte der Lärmschutz-Verordnung stehen im Tagesverlauf jedes Felds (antippen).",
      l90: "Der Hintergrund ist der Pegel ohne die einzelnen lauten Momente.",
      dynamics: "Wenige dB: gleichmässiger Verkehr. Viele dB: ruhige Strasse mit einzelnen lauten Fahrzeugen.",
      events: "Nur Maschinen- und Motorenlärm: laute Fahrzeuge, Strassenverkehr, Tram & Bahn, Flugzeug und Baustelle zusammen, " +
        "gezählt pro Stunde gültiger Messzeit; ein Ereignis ist ein kurzer Pegelanstieg deutlich über den Hintergrund.",
      peaks: "Gezählt pro Stunde gültiger Messzeit: Ereignisse mit einem Spitzenpegel ab " + dbText(state.loudDb) +
        " dB(A), nur von lauten Fahrzeugen, Strassenverkehr, Tram & Bahn, Flugzeug und Baustelle.",
    }[state.metric];
    var s = m.source ? sourceOf() : null;
    if (s) {
      note = "Nur «" + s.name + "», gezählt pro Stunde gültiger Messzeit; die Quelle erkennt die App auf dem Telefon, " +
        "ohne Audio zu speichern. «Alle» zeigt die fünf Quellen des Maschinen- und Motorenlärms zusammen.";
    }
    document.getElementById("legend-note").textContent = note;
  }

  /* ---------- list of cells (keyboard and screen readers) ---------- */

  function fillList() {
    var ol = document.getElementById("celllist-items");
    clear(ol);
    var cells = state.file ? state.file.cells.slice() : [];
    cells.sort(function (a, b) {
      var va = metricValue(a), vb = metricValue(b);
      if (va === null) return 1;
      if (vb === null) return -1;
      return vb - va;
    });
    cells.forEach(function (c) {
      var sw = el("span", { class: "swatch", "aria-hidden": "true" });
      sw.style.background = M.colourFor(curMetric(), blockOf(c), dark()) || "transparent";
      var b = el("button", { type: "button", "data-cell": c.cell }, [
        sw,
        el("span", { text: c.cell + (c.calibrated ? "" : " (unkalibriert)") }),
        el("span", { class: "val", text: valueText(curMetric(), blockOf(c)) }),
      ]);
      b.addEventListener("click", function () { openCard(c.cell, true); });
      ol.appendChild(el("li", null, [b]));
    });
    document.getElementById("celllist").hidden = !cells.length;
  }

  /* ---------- the card ---------- */

  function row(label, value, when, src) {
    var dt = el("dt", null, [label]);
    if (src) {
      // the chosen source's row carries its hue as a small mark (text stays in ink)
      var mark = el("i", { class: "src-mark", "aria-hidden": "true" });
      mark.style.background = sourceColour(src);
      dt.insertBefore(mark, dt.firstChild);
    }
    return el("div", { class: src ? "row src-row" : "row", "data-source": src ? src.id : null }, [
      dt,
      el("dd", { text: value }),
      when ? el("span", { class: "when", text: when }) : null,
    ]);
  }

  function measuredText(b, n) {
    return M.fmtHours(b.measured_hours) + (n > 1 ? " an " + b.days_with_data + " von " + n + " Tagen" : "");
  }

  /** The events per hour of each source: all five under «Alle», else only the chosen one. */
  function sourceRows(b, when) {
    var cats = b.events_per_h_by_category || {};
    var chosen = sourceOf();
    return (chosen ? [chosen] : M.SOURCES).map(function (s) {
      return row("davon " + s.name, M.fmt1(cats[s.id]) + " pro h", when, chosen ? s : null);
    });
  }

  /** Every figure of a block, each with the span it was measured in. */
  function fullRows(b, when, n) {
    return el("dl", { class: "rows" }, [
      row("Mittlerer Pegel (LAeq)", M.fmt1(b.laeq_db) + " dB(A)", when),
      row("Hintergrund (L90)", M.fmt1(b.l90_db) + " dB(A)", when),
      row("Laut (L10)", M.fmt1(b.l10_db) + " dB(A)", when),
      row("Dynamik", M.fmt1(b.dynamics_db) + " dB", when),
      row("Ereignisse", M.fmt1(b.events_per_h) + " pro h", when),
    ].concat(sourceRows(b, when), [
      row("Ab " + dbText(state.loudDb) + " dB(A)", M.fmt1(b.loud_events_per_h) + " pro h", when),
      row("Lautestes", M.fmt1(b.loudest_event_db) + " dB(A)", when),
      row("Gemessen", measuredText(b, n), when),
    ]));
  }

  function shortRows(b, when, n) {
    var s = sourceOf();
    var cats = b.events_per_h_by_category || {};
    return el("dl", { class: "rows" }, [
      row("Mittlerer Pegel", M.fmt1(b.laeq_db) + " dB(A)", when),
      row("Dynamik", M.fmt1(b.dynamics_db) + " dB", when),
      s ? row(s.name, M.fmt1(cats[s.id]) + " pro h", when, s) : row("Ereignisse", M.fmt1(b.events_per_h) + " pro h", when),
      row("Gemessen", measuredText(b, n), when),
    ]);
  }

  /** The bars under the chart: the chosen source's events per hour, or all five together. */
  function barLabel() {
    var s = sourceOf();
    return s ? (s.plural || s.name) : "Ereignisse";
  }

  /** The 24-hour profile of cells/{cell}.json: band L90–L10, LAeq line, the LSV reference
   *  values as two clock-time bands, the chosen source's events per hour (or all five) as small
   *  bars under the plot. */
  function profileChart(hours) {
    var src = sourceOf();
    var cm = M.chartModel(hours, src ? src.id : M.ALL_SOURCES);
    if (!cm) return null;
    var W = 320, left = 26, right = 6, top = 6, plotH = 112;
    var stripTop = top + plotH + 4, stripH = 16;
    var axisY = stripTop + stripH + 11;
    var H = axisY + 3;
    var r = cm.range;
    var plotW = W - left - right;
    function X(f) { return left + f * plotW; }
    function Y(db) { return top + (r.hi - Math.max(r.lo, Math.min(r.hi, db))) / (r.hi - r.lo) * plotH; }

    var root = svg("svg", {
      class: "daychart", viewBox: "0 0 " + W + " " + H.toFixed(1), role: "img",
      "aria-label": "Tagesverlauf pro Stunde: mittlerer Pegel zwischen " + M.fmt1(cm.laeqMin) + " und " +
        M.fmt1(cm.laeqMax) + " dB(A)" + (cm.gaps.length ? ", " + cm.gaps.length + " Stunden ohne genug Messzeit" : ""),
    });

    cm.grid.forEach(function (g) {
      root.appendChild(svg("line", { class: "grid", x1: left, x2: W - right, y1: Y(g).toFixed(1), y2: Y(g).toFixed(1) }));
      root.appendChild(svg("text", { class: "axis", x: left - 4, y: (Y(g) + 3.5).toFixed(1), "text-anchor": "end", text: String(g) }));
    });

    // LSV ES II: the band between planning value and impact limit, per clock-time range
    cm.lsv.forEach(function (s) {
      var x0 = X(s.x0), x1 = X(s.x1);
      root.appendChild(svg("rect", {
        class: "lsv-band", "data-pw": s.pw, "data-igw": s.igw,
        x: x0.toFixed(1), y: Y(s.igw).toFixed(1), width: (x1 - x0).toFixed(1), height: (Y(s.pw) - Y(s.igw)).toFixed(1),
      }));
      root.appendChild(svg("line", { class: "lsv-pw", x1: x0.toFixed(1), x2: x1.toFixed(1), y1: Y(s.pw).toFixed(1), y2: Y(s.pw).toFixed(1) }));
      root.appendChild(svg("line", { class: "lsv-igw", x1: x0.toFixed(1), x2: x1.toFixed(1), y1: Y(s.igw).toFixed(1), y2: Y(s.igw).toFixed(1) }));
    });

    cm.gaps.forEach(function (s) {
      var x0 = X(s.x0), x1 = X(s.x1);
      root.appendChild(svg("rect", { class: "gap", "data-hour": s.hour, x: x0.toFixed(1), y: top, width: (x1 - x0).toFixed(1), height: plotH }));
    });

    // band L90–L10 and the LAeq line, one shape per run of consecutive hours with data
    cm.runs.forEach(function (run) {
      var hasBand = run.every(function (s) { return s.l90 !== null && s.l10 !== null; });
      if (hasBand) {
        var up = M.stepPoints(run, "l10"), down = M.stepPoints(run, "l90").reverse();
        var d = up.concat(down).map(function (p, i) {
          return (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1);
        }).join(" ") + " Z";
        root.appendChild(svg("path", { class: "band", d: d }));
      }
      var line = M.stepPoints(run, "laeq").map(function (p, i) {
        return (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1);
      }).join(" ");
      root.appendChild(svg("path", { class: "laeq", d: line }));
    });

    // The chosen source (or all five) per hour of measurement, scaled to the busiest hour; and a hover target per
    // hour with its numbers (the native SVG tooltip)
    var slotW = plotW / 24;
    cm.slots.forEach(function (s) {
      var x0 = X(s.x0);
      if (s.valid && s.highlighted && cm.highlightMax > 0) {
        var h = Math.max(1.5, s.highlighted / cm.highlightMax * stripH);
        var bar = svg("rect", {
          class: "bar hl", "data-hour": s.hour, rx: 1,
          x: (x0 + 1).toFixed(1), y: (stripTop + stripH - h).toFixed(1), width: (slotW - 2).toFixed(1), height: h.toFixed(1),
        });
        if (src) bar.style.fill = sourceColour(src);   // CSSOM, allowed by the CSP
        root.appendChild(bar);
      }
      var label = s.hour + "–" + (s.hour + 1) + " Uhr: " + (s.valid
        ? "LAeq " + M.fmt1(s.laeq) + " dB(A), L90 " + M.fmt1(s.l90) + ", L10 " + M.fmt1(s.l10) +
          ", " + barLabel() + " " + M.fmt1(s.highlighted) + " pro h"
        : "zu wenig Messzeit") + ", gemessen " + M.fmtHours(s.measuredHours);
      root.appendChild(svg("rect", {
        class: "hit", "data-hour": s.hour, x: x0.toFixed(1), y: top, width: slotW.toFixed(1), height: (stripTop + stripH - top).toFixed(1),
      }, [svg("title", { text: label })]));
    });

    cm.labels.forEach(function (lb) {
      root.appendChild(svg("text", { class: "axis", x: X(lb.x).toFixed(1), y: axisY.toFixed(1), "text-anchor": "middle", text: lb.text }));
    });
    return root;
  }

  function chartSection(cellId, sec) {
    history(cellId).then(function (h) {
      if (state.selected !== cellId) return;
      clear(sec);
      var p = h.profile;
      var hours = p && p.hours ? p.hours : [];
      var any = hours.some(function (x) { return x.laeq_db !== null && x.laeq_db !== undefined; });
      if (!any) {
        sec.appendChild(el("h3", { text: "Tagesverlauf" }));
        sec.appendChild(el("p", { class: "card-note", text: "In den letzten 30 Tagen gibt es keine Stunde mit genug Messzeit." }));
        return;
      }
      var n = M.daysBetween(p.from, p.to) + 1;
      sec.appendChild(el("h3", null, ["Tagesverlauf", el("small", { text: "Mittel pro Stunde, " + M.spanLabel(p.to, n) })]));
      sec.appendChild(profileChart(hours));
      var key = el("i", { class: "k-hl" });
      if (sourceOf()) key.style.background = sourceColour(sourceOf());
      sec.appendChild(el("ul", { class: "chart-keys" }, [
        el("li", null, [el("i", { class: "k-band" }), "L90–L10"]),
        el("li", null, [el("i", { class: "k-line" }), "LAeq"]),
        el("li", null, [key, barLabel() + " pro h"]),
        el("li", null, [el("i", { class: "k-lsv" }), "Lärmschutz-Verordnung"]),
      ]));
      var lsv = el("ul", { class: "lsv-list", id: "lsv-list" });
      M.LSV_BANDS.forEach(function (b) { lsv.appendChild(el("li", { "data-from": String(b.from), text: b.text })); });
      sec.appendChild(lsv);
      sec.appendChild(el("p", { class: "card-note lsv-note", text:
        "Die Werte der Lärmschutz-Verordnung für Strassenlärm in Wohnzonen (ES II = Empfindlichkeitsstufe II) " +
        "sind Beurteilungspegel Lr, mit Korrekturen und über das ganze Jahr beurteilt; hier stehen gemessene " +
        "Mittelwerte einzelner Stunden – die Bänder dienen der Orientierung und sind keine rechtliche Beurteilung." }));
      var missing = hours.filter(function (x) { return x.laeq_db === null || x.laeq_db === undefined; }).length;
      if (missing) sec.appendChild(el("p", { class: "card-note", text: missing + (missing === 1 ? " Stunde" : " Stunden") + " mit zu wenig Messzeit (unter 30 Minuten in 30 Tagen): dort keine Linie." }));
      if (state.day !== p.to) sec.appendChild(el("p", { class: "card-note", text: "Der Tagesverlauf gilt immer für die 30 Tage bis zum neuesten Tag." }));
    }).catch(function (e) {
      if (state.selected !== cellId) return;
      clear(sec);
      sec.appendChild(el("h3", { text: "Tagesverlauf" }));
      sec.appendChild(el("p", { class: "card-note", text: e.notFound ? "Für dieses Feld gibt es keinen Verlauf." : "Der Verlauf konnte nicht geladen werden." }));
    });
  }

  /** «16.10.2026» from the shown file's `counted_since` (or the newest file's), or "". */
  function countedSince() {
    var f = state.file && state.file.counted_since ? state.file : null;
    return M.dateNumeric(f ? f.counted_since : state.countedSince);
  }

  function renderCard() {
    var card = document.getElementById("card");
    var body = document.getElementById("card-body");
    var id = state.selected;
    if (!id) { card.hidden = true; document.querySelector(".mapgrid").classList.add("no-card"); return; }
    var entry = state.byCell[id];
    clear(body);
    var title = document.getElementById("card-title");
    clear(title);
    title.appendChild(document.createTextNode("Feld " + id + " "));
    if (entry && !entry.calibrated) title.appendChild(el("span", { class: "badge", id: "badge-uncal", text: "unkalibriert" }));

    var sw = Lv95.parseCell(id);
    body.appendChild(el("p", { class: "card-sub", text: "LV95 E " + sw.e + "–" + (sw.e + 100) + ", N " + sw.n + "–" + (sw.n + 100) +
      (entry ? " · " + entry.devices + (entry.devices === 1 ? " Gerät" : " Geräte") : "") }));

    if (!entry) {
      body.appendChild(el("p", { class: "card-note", text: "In den 30 Tagen bis " + M.dayLabel(state.day) + " ist dieses Feld nicht auf der Karte." }));
    } else {
      var n = period().days;
      var when = M.spanLabel(state.day, n);
      var b = blockOf(entry);
      var sec = el("section", { class: "card-sec", id: "card-period" }, [
        el("h3", null, [period().label, el("small", { text: M.periodLabel(state.day, n) })]),
      ]);
      if (b && b.days_with_data) sec.appendChild(fullRows(b, when, n));
      else sec.appendChild(el("p", { class: "card-note", text: "In diesem Zeitraum keine Messung; unten die Werte der 30 Tage." }));
      var since = countedSince();
      if (since) {
        // server/DESIGN.md §6 `counted_since`: since then only machine and motor noise is stored
        sec.appendChild(el("p", { class: "card-note", id: "counted-since", text:
          "Ereignisse: nur Maschinen- und Motorenlärm, gezählt seit " + since + "." }));
      }
      body.appendChild(sec);

      var chart = el("section", { class: "card-sec chart-sec" }, [el("p", { class: "card-note", text: "Tagesverlauf wird geladen …" })]);
      body.appendChild(chart);
      chartSection(id, chart);

      if (entry.day) {
        body.appendChild(el("section", { class: "card-sec", id: "card-day" }, [
          el("h3", null, ["Am " + M.dayLabel(state.day)]),
          shortRows(entry.day, M.dayShort(state.day), 1),
        ]));
      }
      var other = state.period === "last_7_days" ? M.PERIODS.last_30_days : M.PERIODS.last_7_days;
      var ob = entry[other.key];
      body.appendChild(el("section", { class: "card-sec", id: "card-other" }, [
        el("h3", null, [other.label, el("small", { text: M.periodLabel(state.day, other.days) })]),
        ob && ob.days_with_data ? shortRows(ob, M.spanLabel(state.day, other.days), other.days)
          : el("p", { class: "card-note", text: "Keine Messung in diesen Tagen." }),
      ]));
      if (!entry.calibrated) {
        body.appendChild(el("p", { class: "card-note", text: "Unkalibriert: Mindestens ein Telefon hier wurde nicht mit einem Schallpegelmesser verglichen; die Pegel können um mehrere Dezibel abweichen." }));
      }
    }
    card.hidden = false;
    document.querySelector(".mapgrid").classList.remove("no-card");
  }

  function openCard(cellId, fromKeyboard) {
    state.selected = cellId;
    restyleCells();
    renderCard();
    map.closeTooltip(tip);
    var card = document.getElementById("card");
    if (fromKeyboard) {
      card.focus();
      var l = state.layers[cellId];
      if (l) map.panTo(Lv95.cellCenter(cellId));
    } else if (window.matchMedia && !window.matchMedia("(min-width: 56rem)").matches) {
      card.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
  }

  function closeCard() {
    var was = state.selected;
    state.selected = null;
    restyleCells();
    renderCard();
    if (was) {
      var btn = document.querySelector('#celllist-items button[data-cell="' + was + '"]');
      if (btn && document.getElementById("celllist").open) btn.focus();
      else mapEl.focus();
    }
  }

  document.getElementById("card-close").addEventListener("click", closeCard);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape" && state.selected) closeCard();
  });

  /* ---------- period and days ---------- */

  function setSpanText(text) {
    document.getElementById("span").textContent = text;
  }

  function spanText() {
    return M.periodLabel(state.day, period().days);
  }

  function updateButtons() {
    var prev = document.getElementById("prev"), next = document.getElementById("next");
    if (!state.latest) { prev.disabled = next.disabled = true; return; }
    var back = M.daysBetween(state.day, state.latest);
    prev.disabled = back >= HISTORY_DAYS - 1;
    next.disabled = back <= 0;
  }

  function showFile(file, day) {
    state.file = file;
    state.day = day;
    state.byCell = {};
    (file ? file.cells : []).forEach(function (c) { state.byCell[c.cell] = c; });
    setSpanText(spanText());
    drawCells();
    drawReports();
    if (state.selected) renderCard();
    updateButtons();
  }

  function emptyText() {
    return state.period === "last_7_days" ? "In diesen 7 Tagen keine Messwerte." : "In diesen 30 Tagen keine Messwerte.";
  }

  function loadDay(day) {
    var seq = ++state.loadSeq;
    state.day = day;
    setSpanText(spanText() + " – wird geladen …");
    updateButtons();
    var path = day === state.latest ? "cells.json" : "days/" + day + ".json";
    return getJson(path).then(function (file) {
      if (seq !== state.loadSeq) return;
      showFile(file, day);
      setStatus(file.cells && file.cells.length ? "" : emptyText());
    }).catch(function (e) {
      if (seq !== state.loadSeq) return;
      showFile(null, day);
      if (e.notFound) setSpanText(spanText() + " – keine Daten");
      setStatus(e.notFound ? "Für diesen Tag hat der Server keine Daten." : "Die Messwerte konnten nicht geladen werden (" + e.message + ").");
    });
  }

  document.getElementById("prev").addEventListener("click", function () {
    if (state.day) loadDay(M.addDays(state.day, -1));
  });
  document.getElementById("next").addEventListener("click", function () {
    if (state.day && state.day !== state.latest) loadDay(M.addDays(state.day, 1));
  });

  function onRadio(name, apply) {
    Array.prototype.forEach.call(document.querySelectorAll('input[name="' + name + '"]'), function (input) {
      input.addEventListener("change", function () {
        if (!input.checked) return;
        apply(input.value);
        drawLegend();
        drawCells();
        if (state.selected) renderCard();
      });
    });
  }
  function checkRadio(name, value) {
    var r = document.querySelector('input[name="' + name + '"][value="' + value + '"]');
    if (r) r.checked = true;
  }

  /** Keeps the chosen source in the fragment, after the view if there is one; replaces the
   *  address (no history entry, no hashchange), so the back gesture is not taken by the page. */
  function writeSourceHash() {
    var h = M.hashFor(location.hash, state.source, BOUNDS, MIN_ZOOM, MAX_ZOOM);
    if (h === location.hash || (!h && !location.hash)) return;
    try {
      // window.history: `history` in this file is the cell-file loader
      window.history.replaceState(window.history.state, "", location.pathname + location.search + h);
    } catch (e) { /* a sandboxed view without history: the selection still works */ }
  }

  /** One layer per source: a source other than «Alle» colours the cells by its events per hour
   *  (so the metric becomes «Ereignisse/h»); «Alle» shows all five together. */
  function setSource(id, fromHash) {
    if (id !== M.ALL_SOURCES && !M.source(id)) id = M.ALL_SOURCES;
    state.source = id;
    checkRadio("source", id);
    if (id !== M.ALL_SOURCES && state.metric !== "events") {
      state.metric = "events";
      checkRadio("metric", "events");
    }
    if (!fromHash) writeSourceHash();
    mapEl.setAttribute("data-source", id);
  }

  onRadio("metric", function (v) {
    state.metric = v;
    // the levels and «Sehr laut/h» are not split by source: back to «Alle»
    if (v !== "events" && state.source !== M.ALL_SOURCES) setSource(M.ALL_SOURCES);
  });
  onRadio("source", function (v) { setSource(v); });
  onRadio("period", function (v) {
    state.period = v;
    state.periodChosen = true;
    if (state.day) setSpanText(spanText());
    drawReports();
    if (state.file && !state.file.cells.length) setStatus(emptyText());
  });

  if (darkMq && darkMq.addEventListener) {
    darkMq.addEventListener("change", function () { drawLegend(); restyleCells(); fillList(); if (state.selected) renderCard(); });
  }

  function showNetwork(file) {
    var n = file && file.network;
    var p = document.getElementById("network");
    if (!n) { p.textContent = ""; return; }
    var models = Object.keys(n.device_models || {}).sort(function (a, b) {
      return n.device_models[b] - n.device_models[a] || a.localeCompare(b);
    }).map(function (k) { return k + " " + n.device_models[k]; });
    p.textContent = "Beteiligte Geräte in den letzten 7 Tagen: " + n.devices_active_7d +
      (models.length ? " (" + models.join(", ") + ")" : "") + ". Stand der Daten: " + M.stampLabel(file.generated_at) + ".";
  }

  function setLoudDb(v) {
    if (typeof v !== "number" || !isFinite(v)) return;
    state.loudDb = v;
    Array.prototype.forEach.call(document.querySelectorAll(".loud-db"), function (s) { s.textContent = dbText(v); });
  }

  /* ---------- keeping the page up to date ---------- */

  // The server publishes the map files about every 30 seconds when there is new data (DESIGN §6).
  // While the page is visible it asks for cells.json every 30 seconds, through the same getJson as
  // above (same hosts, no credentials, no referrer) with cache "no-cache": the browser always
  // revalidates, so an unchanged file comes back as a small 304 from a server that answers
  // conditional requests. Only when its generated_at changed are the cells, the reports and an
  // open card redrawn; the view, the metric, the period, the day shown and the open card stay as
  // they are. Hidden page: no checks; visible again: one check at once.
  var POLL_MS = 30 * 1000;
  var shownGenerated = null;   // generated_at of the newest cells.json in use
  var pollTimer = null;
  var polling = false;         // true once the first load is over
  var checking = null;         // the running check, so that checks never overlap
  var checksDone = 0;          // for the tests: data-checks on #karte

  function checkForUpdate() {
    if (!polling) return Promise.resolve(false);   // the first load is still running
    if (checking) return checking;
    checking = getJson("cells.json", "no-cache").then(function (file) {
      if (!file || !M.isDate(file.day) || !Array.isArray(file.cells) || file.generated_at === shownGenerated) return false;
      shownGenerated = file.generated_at;
      var showingNewest = !state.day || state.day === state.latest;
      state.latest = file.day;
      state.history = {};       // a cell's file is rebuilt with the same run
      setLoudDb(file.loud_event_db);
      state.countedSince = file.counted_since || state.countedSince;
      showNetwork(file);
      if (showingNewest) {
        state.loadSeq++;        // a day file still loading must not replace this one
        showFile(file, file.day);   // redraws cells and reports, re-renders an open card
        setStatus(file.cells.length ? "" : "Noch keine Messwerte auf der Karte.");
      } else {
        updateButtons();        // an older day stays shown; › now reaches the new day
        if (state.selected) renderCard();
      }
      mapEl.setAttribute("data-ready", "1");
      return getJson("reports.json", "no-cache").then(function (r) {
        if (Array.isArray(r.reports)) state.reports = r.reports;
      }).catch(function () { /* keep the reports shown */ }).then(function () {
        drawReports();
        if (state.reports) mapEl.setAttribute("data-reports", String(state.reports.length));
        return true;
      });
    }).catch(function () { return false; }).then(function (changed) {
      checking = null;
      checksDone++;
      mapEl.setAttribute("data-checks", String(checksDone));
      mapEl.setAttribute("data-generated", shownGenerated || "");
      return changed;
    });
    return checking;
  }

  function schedule() {
    clearTimeout(pollTimer);
    pollTimer = null;
    if (polling && document.visibilityState === "visible") {
      pollTimer = setTimeout(function () { pollTimer = null; checkForUpdate().then(schedule); }, POLL_MS);
    }
  }

  function startPolling() {
    polling = true;
    mapEl.setAttribute("data-generated", shownGenerated || "");
    schedule();
  }

  document.addEventListener("visibilitychange", function () {
    if (!polling) return;
    if (document.visibilityState === "visible") checkForUpdate().then(schedule);
    else schedule();          // clears the timer
  });

  /* ---------- start ---------- */

  setSource(hashSource || M.ALL_SOURCES, true);
  drawLegend();
  drawReportLegend();
  renderCard();
  getJson("cells.json").then(function (file) {
    if (!M.isDate(file.day)) throw new Error("cells.json ohne Tag");
    state.latest = file.day;
    // the server's default period, unless the visitor already chose one while the file loaded
    if (!state.periodChosen && file.default_period && M.PERIODS[file.default_period]) {
      state.period = file.default_period;
      var radio = document.querySelector('input[name="period"][value="' + state.period + '"]');
      if (radio) radio.checked = true;
    }
    setLoudDb(file.loud_event_db);
    state.countedSince = file.counted_since || null;
    drawLegend();
    shownGenerated = file.generated_at;
    showFile(file, file.day);
    showNetwork(file);
    if (!hashView) fitToCells();
    setStatus(file.cells.length ? "" : "Noch keine Messwerte auf der Karte.");
    mapEl.setAttribute("data-ready", "1");
    return getJson("reports.json").then(function (r) {
      state.reports = Array.isArray(r.reports) ? r.reports : [];
      drawReports();
      mapEl.setAttribute("data-reports", String(state.reports.length));
    }).catch(function () {
      state.reports = [];
      drawReports();
      var c = document.getElementById("reports-count");
      if (c) c.textContent = "Die Meldungen konnten nicht geladen werden.";
      mapEl.setAttribute("data-reports", "error");
    });
  }).catch(function (e) {
    setSpanText("keine Daten");
    setStatus("Die Messwerte konnten nicht geladen werden" + (e.notFound ? "." : " (" + e.message + ")."));
    updateButtons();
    mapEl.setAttribute("data-ready", "error");
  }).then(startPolling);

  // for the Playwright tests: the map and the state
  window.__karte = { map: map, state: state, reportLayer: reportLayer, hashView: function () { return hashView; }, pollMs: POLL_MS };
  // The app's tab «Karte» calls this when it shows the kept page again (server/DESIGN.md §7).
  window.KarteCheck = checkForUpdate;
})();
