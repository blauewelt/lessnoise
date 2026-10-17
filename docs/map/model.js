/* Pure helpers of the noise map (docs/karte.html): number and date wording, the colour
 * scales of the metrics, the report categories, and the model of the 24-hour chart in the
 * cell card. No DOM, no Leaflet. Loaded as a classic script in the browser (window.KarteModel)
 * and with require() in the node unit tests (tests/unit/model.test.js).
 *
 * Data contract: server/DESIGN.md §6 and server/README.md "Published files"; the fixtures
 * server/example_cells.json, server/example_cell.json and server/example_reports.json are what
 * the server emits.
 */
(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.KarteModel = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /* ---------- numbers (Swiss German: decimal comma, as the site writes «18,4 MB») ------ */

  /** 54.9 -> "54,9"; null/undefined/NaN -> "–". */
  function fmt1(x) {
    if (x === null || x === undefined || typeof x !== "number" || !isFinite(x)) return "–";
    return x.toFixed(1).replace(".", ",").replace("-", "−");
  }

  /** 0.97 -> "97 %" (narrow no-break space, as in Swiss typesetting). */
  function fmtPct(share) {
    if (share === null || share === undefined || !isFinite(share)) return "–";
    return Math.round(share * 100) + " %";
  }

  /** Measured hours: 37.5 -> "37,5 h", 0.4 -> "0,4 h". */
  function fmtHours(h) {
    if (h === null || h === undefined || !isFinite(h)) return "–";
    return fmt1(h) + " h";
  }

  /* ---------- days and dates ------------------------------------------------------------ */

  var MONTHS = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
    "September", "Oktober", "November", "Dezember"];
  var MONTHS_SHORT = ["Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.",
    "Okt.", "Nov.", "Dez."];
  var WEEKDAYS = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];
  var DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

  function isDate(s) { return DATE_RE.test(String(s)); }

  function parts(dateStr) {
    var m = DATE_RE.exec(String(dateStr));
    if (!m) throw new Error("not a date: " + dateStr);
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
    return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate(), wd: d.getUTCDay() };
  }

  /** "2026-10-07" + 1 -> "2026-10-08" (calendar days; no time zone involved). */
  function addDays(dateStr, n) {
    var p = parts(dateStr);
    var d = new Date(Date.UTC(p.y, p.m, p.d + n));
    return d.toISOString().slice(0, 10);
  }

  /** Whole days from a to b (b − a). */
  function daysBetween(a, b) {
    var pa = parts(a), pb = parts(b);
    return Math.round((Date.UTC(pb.y, pb.m, pb.d) - Date.UTC(pa.y, pa.m, pa.d)) / 86400000);
  }

  /** "2026-10-08" -> "Do 8. Oktober 2026". */
  function dayLabel(dateStr) {
    var p = parts(dateStr);
    return WEEKDAYS[p.wd] + " " + p.d + ". " + MONTHS[p.m] + " " + p.y;
  }

  /** "2026-10-08" -> "8. Okt." (the "when" column of the card). */
  function dayShort(dateStr) {
    var p = parts(dateStr);
    return p.d + ". " + MONTHS_SHORT[p.m];
  }

  /** The `n` calendar days ending with `lastDay`, short: (7, 2026-10-08) -> "2.–8. Okt.";
   *  across months "9. Sept.–8. Okt."; one day -> "8. Okt.". */
  function spanLabel(lastDay, n) {
    if (n <= 1) return dayShort(lastDay);
    var a = parts(addDays(lastDay, -(n - 1))), b = parts(lastDay);
    if (a.y === b.y && a.m === b.m) return a.d + ".–" + b.d + ". " + MONTHS_SHORT[b.m];
    return a.d + ". " + MONTHS_SHORT[a.m] + "–" + b.d + ". " + MONTHS_SHORT[b.m];
  }

  /** The same span with full month names and the year, for the date bar:
   *  (7, 2026-10-08) -> "2.–8. Oktober 2026"; "9. September – 8. Oktober 2026";
   *  across years "29. Dezember 2026 – 4. Januar 2027". */
  function periodLabel(lastDay, n) {
    var a = parts(addDays(lastDay, -(n - 1))), b = parts(lastDay);
    var end = b.d + ". " + MONTHS[b.m] + " " + b.y;
    if (a.y !== b.y) return a.d + ". " + MONTHS[a.m] + " " + a.y + " – " + end;
    if (a.m !== b.m) return a.d + ". " + MONTHS[a.m] + " – " + end;
    return a.d + ".–" + end;
  }

  /** "2026-10-16" -> "16.10.2026" (the date `counted_since` of the map files); "" if not a date. */
  function dateNumeric(dateStr) {
    if (!isDate(dateStr)) return "";
    var p = parts(dateStr);
    return (p.d < 10 ? "0" : "") + p.d + "." + (p.m < 9 ? "0" : "") + (p.m + 1) + "." + p.y;
  }

  /** "2026-10-08T06:40:00+02:00" -> "8. Okt. 2026, 06:40" — read from the text, which the
   *  server writes in Zürich local time, so the viewer's own time zone does not matter. */
  function stampLabel(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(iso || ""));
    if (!m) return "";
    return (+m[3]) + ". " + MONTHS_SHORT[+m[2] - 1] + " " + m[1] + ", " + m[4] + ":" + m[5];
  }

  /** A report's hour (local ISO, rounded down by the server) -> "Do 8. Okt., 14–15 Uhr". */
  function hourLabel(iso) {
    var m = /^(\d{4}-\d{2}-\d{2})T(\d{2}):/.exec(String(iso || ""));
    if (!m) return "";
    var p = parts(m[1]), h = +m[2];
    return WEEKDAYS[p.wd] + " " + p.d + ". " + MONTHS_SHORT[p.m] + ", " + h + "–" + ((h + 1) % 24) + " Uhr";
  }

  /** The local calendar date of a report time: "2026-10-08T14:00:00+02:00" -> "2026-10-08". */
  function localDate(iso) {
    return String(iso || "").slice(0, 10);
  }

  /* ---------- metrics and colour scales ------------------------------------------------ */

  // Mean level and background: one neutral hue (blue), light -> dark = quiet -> loud, in 5 dB
  // classes. Deliberately no traffic-light colours: the map shows measurements, not a legal
  // judgement. In dark mode (the basemap is inverted) the ramp runs dark -> light instead, so
  // loud stays the most visible. Both ramps pass the ordinal checks of the dataviz validator
  // (monotone lightness, visible steps, end step ≥ 2:1 against the surface).
  var LEVEL_COLOURS = ["#86b6ef", "#5598e7", "#2a78d6", "#1c5cab", "#104281", "#0a2547"];
  var LEVEL_COLOURS_DARK = ["#184f95", "#2a78d6", "#5598e7", "#86b6ef", "#b7d3f6", "#e3eefc"];
  var RATE_COLOURS = ["#f1e6a6", "#c9dd8a", "#86c27c", "#3fa27f", "#227d82", "#2a5a85", "#33306e"];

  function blockGet(key) { return function (b) { return b ? b[key] : null; }; }

  var METRICS = {
    laeq: {
      id: "laeq", label: "Mittlerer Pegel", unit: "dB(A)",
      title: "Mittlerer Pegel (LAeq) über die gemessene Zeit, dB(A)",
      breaks: [45, 50, 55, 60, 65], colours: LEVEL_COLOURS, coloursDark: LEVEL_COLOURS_DARK,
      ticks: [45, 50, 55, 60, 65],
      get: blockGet("laeq_db"),
    },
    l90: {
      id: "l90", label: "Hintergrund", unit: "dB(A)",
      title: "Hintergrund (L90): der Pegel, der in 90 % der Zeit überschritten wird, dB(A)",
      breaks: [35, 40, 45, 50, 55], colours: LEVEL_COLOURS, coloursDark: LEVEL_COLOURS_DARK,
      ticks: [35, 40, 45, 50, 55],
      get: blockGet("l90_db"),
    },
    dynamics: {
      id: "dynamics", label: "Dynamik", unit: "dB",
      title: "Dynamik: typischer Abstand zwischen lauten und leisen Momenten (L10 − L90), dB",
      breaks: [3, 5, 7, 9, 11, 13], colours: RATE_COLOURS,
      ticks: [3, 5, 7, 9, 11, 13],
      get: blockGet("dynamics_db"),
    },
    events: {
      id: "events", label: "Ereignisse/h", unit: "pro Stunde",
      title: "Laute Einzelereignisse pro Stunde Messzeit, Maschinen- und Motorenlärm (alle fünf Quellen)",
      breaks: [2, 5, 10, 15, 20, 30], colours: RATE_COLOURS,
      ticks: [2, 5, 10, 15, 20, 30],
      get: blockGet("events_per_h"),
    },
    loud: {
      id: "loud", label: "Töff & Poser/h", unit: "pro Stunde",
      title: "Ereignisse «Töff & Poser» (Motorräder, Hochdrehen) pro Stunde Messzeit",
      breaks: [0.5, 1, 2, 3, 5, 8], colours: RATE_COLOURS,
      ticks: [0.5, 1, 2, 3, 5, 8],
      get: function (b) {
        return b && b.events_per_h_by_category ? b.events_per_h_by_category.loud_vehicle : null;
      },
    },
    peaks: {
      id: "peaks", label: "Sehr laut/h", unit: "pro Stunde",
      // the threshold comes from the file (loud_event_db); karte.js fills it in
      title: "Ereignisse ab {db} dB(A) pro Stunde Messzeit, Maschinen- und Motorenlärm",
      breaks: [0.2, 0.5, 1, 2, 3, 5], colours: RATE_COLOURS,
      ticks: [0.2, 0.5, 1, 2, 3, 5],
      get: blockGet("loud_events_per_h"),
    },
  };
  var METRIC_ORDER = ["laeq", "l90", "dynamics", "events", "loud", "peaks"];

  // The two periods of the map (server: `last_7_days`, `last_30_days`).
  var PERIODS = {
    last_7_days: { key: "last_7_days", days: 7, label: "Letzte 7 Tage" },
    last_30_days: { key: "last_30_days", days: 30, label: "Letzte 30 Tage" },
  };

  /** Index of the class a value falls in: value < breaks[0] -> 0, ≥ last break -> n. */
  function classIndex(value, breaks) {
    var i = 0;
    while (i < breaks.length && value >= breaks[i]) i++;
    return i;
  }

  /* ---------- one layer per source (the five map categories, server/DESIGN.md §6) ---------- */

  // The five categories the server counts (config.MAP_CATEGORIES), in the server's order. Each
  // has its own hue (light / dark mode; validated for colour-vision deficiency as a set), used
  // only for its own layer: one hue per view, never several categories coloured at once. Each
  // ramp is that hue in 5 steps of OKLCH lightness (light mode 0.76 → 0.36, light -> dark = few
  // -> many; dark mode 0.52 → 0.91, dark -> light, so many stays the most visible on the dark
  // map, as LEVEL_COLOURS_DARK); every ramp passes the ordinal checks of the dataviz validator
  // (monotone lightness, steps ≥ 0.06 apart, end step ≥ 2:1 against the surface).
  var SOURCES = [
    { id: "loud_vehicle", name: "Töff & Poser", colour: "#EB6834", colourDark: "#D95926",
      ramp: ["#f7936e", "#e46838", "#c24609", "#963300", "#6a2202"],
      rampDark: ["#ac4319", "#d55c2d", "#fc7848", "#fead91", "#fed8ca"] },
    { id: "road_traffic", name: "Strassenverkehr", colour: "#2A78D6", colourDark: "#3987E5",
      ramp: ["#7bb4fd", "#4b93ee", "#2573d1", "#0055ab", "#023b79"],
      rampDark: ["#2969b5", "#3e87e0", "#61a7ff", "#9cc7ff", "#cfe3fe"] },
    { id: "rail_tram", name: "Tram & Bahn", colour: "#1BAF7A", colourDark: "#199E70",
      ramp: ["#6ec69d", "#27ab79", "#078a5e", "#066947", "#014930"],
      rampDark: ["#0c7c57", "#279d71", "#46bd8d", "#71ddae", "#a5f6cf"] },
    { id: "aircraft", name: "Flugzeug", colour: "#4A3AA7", colourDark: "#9085E9",
      ramp: ["#a7a6fe", "#8882ef", "#6b61d1", "#5144ac", "#382a83"],
      rampDark: ["#655bac", "#8177d4", "#9f95fa", "#bfbbfe", "#deddff"] },
    { id: "construction", name: "Baustelle", colour: "#EDA100", colourDark: "#C98500",
      ramp: ["#dea551", "#c28410", "#9b690a", "#774f00", "#543602"],
      rampDark: ["#8f5d00", "#b57802", "#da9525", "#f9b556", "#fedbae"] },
  ];
  // One scale for all five, so that the same step means the same rate whichever source is shown.
  var SOURCE_BREAKS = [0.5, 1, 2, 5];
  var ALL_SOURCES = "alle";

  function source(id) {
    for (var i = 0; i < SOURCES.length; i++) if (SOURCES[i].id === id) return SOURCES[i];
    return null;
  }

  /** The colour scale of one source's events per hour (a metric like those of METRICS). */
  function sourceMetric(id) {
    var s = source(id);
    if (!s) return null;
    return {
      id: "source:" + s.id, source: s.id, label: s.name + "/h", unit: "pro Stunde",
      title: "Ereignisse «" + s.name + "» pro Stunde Messzeit",
      breaks: SOURCE_BREAKS, colours: s.ramp, coloursDark: s.rampDark, ticks: SOURCE_BREAKS,
      get: function (b) {
        var c = b && b.events_per_h_by_category;
        return c && c[s.id] !== undefined ? c[s.id] : null;
      },
    };
  }

  /** The metric the map colours by: one source's events/h when «Ereignisse/h» is chosen with a
   *  source other than «Alle»; otherwise the metric itself. */
  function metricFor(metricId, sourceId) {
    if (metricId === "events" && sourceId && sourceId !== ALL_SOURCES) {
      var sm = sourceMetric(sourceId);
      if (sm) return sm;
    }
    return METRICS[metricId];
  }

  function metricOf(m) { return typeof m === "string" ? METRICS[m] : m; }

  /* ---------- the source in the fragment: karte.html#quelle=<id>, also with a view ---------- */

  function hashParts(hash) {
    var h = typeof hash === "string" ? (hash.charAt(0) === "#" ? hash.slice(1) : hash) : "";
    return h ? h.split("&") : [];
  }

  /** The source in a location hash ("quelle=road_traffic"), or null if absent or unknown. */
  function parseSource(hash) {
    var found = null;
    hashParts(hash).forEach(function (p) {
      var kv = p.split("=");
      if (kv.length === 2 && kv[0] === "quelle" && (kv[1] === ALL_SOURCES || source(kv[1]))) found = kv[1];
    });
    return found;
  }

  /** The hash without its `quelle=` part (what parseView reads). */
  function hashWithoutSource(hash) {
    return hashParts(hash).filter(function (p) { return p.split("=")[0] !== "quelle"; }).join("&");
  }

  /** The hash for a chosen source: the view part of `hash` (if it is a valid view, else nothing)
   *  plus `quelle=<id>`; «Alle» (the default) adds nothing. Returns "" or "#…". */
  function hashFor(hash, sourceId, bounds, minZoom, maxZoom) {
    var rest = hashWithoutSource(hash);
    var parts = rest && parseView(rest, bounds, minZoom, maxZoom) ? [rest] : [];
    if (sourceId && sourceId !== ALL_SOURCES && source(sourceId)) parts.push("quelle=" + sourceId);
    return parts.length ? "#" + parts.join("&") : "";
  }

  /** Fill colour of a cell for a metric, or null when the block has no value. */
  function colourFor(metricId, block, dark) {
    var m = metricOf(metricId);
    var v = m.get(block);
    if (v === null || v === undefined || !isFinite(v)) return null;
    var colours = dark && m.coloursDark ? m.coloursDark : m.colours;
    return colours[classIndex(v, m.breaks)];
  }

  /** Legend-bar position (0..1) of a class edge: classes are drawn with equal width. */
  function tickPosition(metricId, value) {
    var m = metricOf(metricId);
    var i = m.breaks.indexOf(value);
    if (i < 0) throw new Error("not a class edge of " + m.id + ": " + value);
    return (i + 1) / (m.breaks.length + 1);
  }

  /* ---------- sources and reports -------------------------------------------------------- */

  var CATEGORY_NAMES = {
    loud_vehicle: "Töff & Poser", road_traffic: "Strassenverkehr", rail_tram: "Tram & Bahn",
    aircraft: "Flugzeug", construction: "Baustelle", voices: "Stimmen", music: "Musik",
    unclassified: "Sonstiges",
  };
  var HIGHLIGHT = "loud_vehicle";   // the app's default highlight

  // Manual reports (server: reports.json). The letter is the marker's identity; the colour
  // only helps (three hues that pass the all-pairs colour-vision checks, two neutral).
  var REPORT_CATEGORIES = [
    { id: "motorrad", name: "Motorrad", letter: "M" },
    { id: "poser", name: "Poser", letter: "P" },
    { id: "lastwagen", name: "Lastwagen", letter: "L" },
    { id: "auto", name: "Auto", letter: "A" },
    { id: "andere", name: "Andere", letter: "+" },
  ];

  function reportCategory(id) {
    for (var i = 0; i < REPORT_CATEGORIES.length; i++) if (REPORT_CATEGORIES[i].id === id) return REPORT_CATEGORIES[i];
    return REPORT_CATEGORIES[REPORT_CATEGORIES.length - 1];
  }

  /** A report's direction of travel (reports.json `direction`, app 0.6.7): whole degrees 0–359
   *  clockwise from north, or null when none was reported (older reports, older apps). */
  function reportDirection(r) {
    var d = r ? r.direction : null;
    return typeof d === "number" && isFinite(d) && d >= 0 && d < 360 ? Math.round(d) % 360 : null;
  }

  var COMPASS_8 = ["Nord", "Nordost", "Ost", "Südost", "Süd", "Südwest", "West", "Nordwest"];

  /** 87 -> "Richtung Ost"; null -> "". Eight sectors of 45°, centred on the compass points. */
  function directionLabel(deg) {
    if (typeof deg !== "number" || !isFinite(deg)) return "";
    var i = Math.round((((deg % 360) + 360) % 360) / 45) % 8;
    return "Richtung " + COMPASS_8[i];
  }

  /** Reports whose local date lies in the `n` days ending with `lastDay`. */
  function reportsInPeriod(reports, lastDay, n) {
    var first = addDays(lastDay, -(n - 1));
    return (reports || []).filter(function (r) {
      var d = localDate(r.time);
      return isDate(d) && d >= first && d <= lastDay;
    });
  }

  /* ---------- the 24-hour chart in the card ---------------------------------------------- */

  // Reference values of the Swiss Noise Abatement Ordinance (Lärmschutz-Verordnung, LSV,
  // Anhang 3, road traffic), sensitivity level ES II (residential zones), dB(A): planning value
  // and impact limit, for the two periods the ordinance assesses separately.
  var LSV_BANDS = [
    { from: 6, to: 22, pw: 55, igw: 60, text: "6–22 Uhr: Planungswert 55 / Immissionsgrenzwert 60 (ES II)" },
    { from: 22, to: 6, pw: 45, igw: 50, text: "22–6 Uhr: 45 / 50" },
  ];

  /** The y range: lowest L90 − 5 dB to the loudest level + 5 dB, rounded to 5 dB, at least
   *  30 dB, always containing the LSV values (40–65), within 0–120. */
  function yRange(hours) {
    var lows = [40], highs = [65];
    (hours || []).forEach(function (h) {
      if (h.laeq_db !== null && h.laeq_db !== undefined) {
        lows.push(h.l90_db !== null && h.l90_db !== undefined ? h.l90_db : h.laeq_db);
        highs.push(h.laeq_db);
        if (h.l10_db !== null && h.l10_db !== undefined) highs.push(h.l10_db);
      }
    });
    var lo = Math.floor((Math.min.apply(null, lows) - 5) / 5) * 5;
    var hi = Math.ceil((Math.max.apply(null, highs) + 5) / 5) * 5;
    if (hi - lo < 30) hi = lo + 30;
    return { lo: Math.max(0, lo), hi: Math.min(120, hi) };
  }

  /** Gridlines every 10 dB inside the range. */
  function gridlines(r) {
    var out = [];
    for (var v = Math.ceil(r.lo / 10) * 10; v <= r.hi + 1e-9; v += 10) out.push(v);
    return out;
  }

  function hasLevels(h) {
    return h.laeq_db !== null && h.laeq_db !== undefined;
  }

  /** The LSV bands as x ranges (fractions of the day) for the chart: 22–6 Uhr is two pieces. */
  function lsvSegments() {
    var out = [];
    LSV_BANDS.forEach(function (b) {
      var pieces = b.from < b.to ? [[b.from, b.to]] : [[b.from, 24], [0, b.to]];
      pieces.forEach(function (p) { out.push({ x0: p[0] / 24, x1: p[1] / 24, pw: b.pw, igw: b.igw }); });
    });
    return out.sort(function (a, b) { return a.x0 - b.x0; });
  }

  /** Data -> drawing model of the 24-hour chart. `hours` are the 24 rows of
   *  cells/{cell}.json `profile.hours` (hour 0–23). x positions are fractions 0..1 of the day.
   *  `runs` are maximal stretches of consecutive hours with levels: band and line are drawn per
   *  run, so nothing is drawn across an hour without enough data (a `gap`). The bars under the
   *  plot (`highlighted`) are the events per hour of `highlight`: a source id, "alle" for all five
   *  together (`events_per_h`), or by default HIGHLIGHT (Töff & Poser). */
  function chartModel(hours, highlight) {
    var hlKey = highlight === undefined || highlight === null ? HIGHLIGHT : highlight;
    if (!hours || !hours.length) return null;
    var byHour = {};
    hours.forEach(function (h) { byHour[h.hour] = h; });
    var slots = [];
    for (var hr = 0; hr < 24; hr++) {
      var h = byHour[hr] || { hour: hr, laeq_db: null, l90_db: null, l10_db: null, events_per_h_by_category: {}, measured_hours: 0 };
      var cats = h.events_per_h_by_category || {};
      slots.push({
        hour: hr, x0: hr / 24, x1: (hr + 1) / 24, valid: hasLevels(h),
        laeq: h.laeq_db, l90: h.l90_db, l10: h.l10_db,
        highlighted: hlKey === ALL_SOURCES ? (h.events_per_h === undefined ? null : h.events_per_h)
          : cats[hlKey] === undefined ? null : cats[hlKey],
        eventsPerH: h.events_per_h === undefined ? null : h.events_per_h,
        measuredHours: h.measured_hours,
      });
    }
    var runs = [], cur = null;
    slots.forEach(function (s) {
      if (s.valid && cur) cur.push(s);
      else if (s.valid) { cur = [s]; runs.push(cur); }
      else cur = null;
    });
    var gaps = slots.filter(function (s) { return !s.valid; });
    var labels = [0, 3, 6, 9, 12, 15, 18, 21, 24].map(function (v) {
      return { x: v / 24, text: (v < 10 ? "0" : "") + v };
    });
    var r = yRange(hours);
    var valid = slots.filter(function (s) { return s.valid; });
    var hl = slots.map(function (s) { return s.highlighted || 0; });
    return {
      range: r, grid: gridlines(r), slots: slots, runs: runs, gaps: gaps, labels: labels,
      lsv: lsvSegments(),
      highlightMax: Math.max.apply(null, hl),
      laeqMin: valid.length ? Math.min.apply(null, valid.map(function (s) { return s.laeq; })) : null,
      laeqMax: valid.length ? Math.max.apply(null, valid.map(function (s) { return s.laeq; })) : null,
    };
  }

  /** Step path through a run: for each hour a horizontal segment at `key`. */
  function stepPoints(run, key) {
    var pts = [];
    run.forEach(function (s) { pts.push([s.x0, s[key]], [s.x1, s[key]]); });
    return pts;
  }

  /* ---------- the view fragment: karte.html#lat=…&lon=…&z=… (server/DESIGN.md §7) ---------- */

  var VIEW_NUMBER = /^-?\d{1,3}(\.\d{1,8})?$/;

  /** Parses a location hash like "#lat=47.37690&lon=8.54170&z=16" into { lat, lon, z }, or null if
   *  anything is missing or invalid: lat/lon WGS84 decimal degrees inside `bounds` ([[S, W], [N, E]]),
   *  z a number within [minZoom, maxZoom]. Only these three keys, each once; anything else -> null.
   *  The fragment never reaches a server (browsers do not send it); the app's tab «Karte» uses it
   *  to open the map at the shifted point of the phone's newest shared measurement. */
  function parseView(hash, bounds, minZoom, maxZoom) {
    if (typeof hash !== "string") return null;
    var h = hash.charAt(0) === "#" ? hash.slice(1) : hash;
    if (!h || h.length > 100) return null;
    var seen = {};
    var parts = h.split("&");
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i].split("=");
      if (kv.length !== 2 || (kv[0] !== "lat" && kv[0] !== "lon" && kv[0] !== "z")) return null;
      if (Object.prototype.hasOwnProperty.call(seen, kv[0]) || !VIEW_NUMBER.test(kv[1])) return null;
      seen[kv[0]] = Number(kv[1]);
    }
    var lat = seen.lat, lon = seen.lon, z = seen.z;
    if (![lat, lon, z].every(function (x) { return typeof x === "number" && isFinite(x); })) return null;
    if (lat < bounds[0][0] || lat > bounds[1][0] || lon < bounds[0][1] || lon > bounds[1][1]) return null;
    if (z < minZoom || z > maxZoom) return null;
    return { lat: lat, lon: lon, z: z };
  }

  return {
    parseView: parseView,
    fmt1: fmt1, fmtPct: fmtPct, fmtHours: fmtHours,
    isDate: isDate, addDays: addDays, daysBetween: daysBetween,
    dayLabel: dayLabel, dayShort: dayShort, spanLabel: spanLabel, periodLabel: periodLabel,
    stampLabel: stampLabel, hourLabel: hourLabel, localDate: localDate, dateNumeric: dateNumeric,
    METRICS: METRICS, METRIC_ORDER: METRIC_ORDER, PERIODS: PERIODS, LSV_BANDS: LSV_BANDS,
    classIndex: classIndex, colourFor: colourFor, tickPosition: tickPosition,
    CATEGORY_NAMES: CATEGORY_NAMES, HIGHLIGHT: HIGHLIGHT,
    SOURCES: SOURCES, SOURCE_BREAKS: SOURCE_BREAKS, ALL_SOURCES: ALL_SOURCES, source: source,
    sourceMetric: sourceMetric, metricFor: metricFor,
    parseSource: parseSource, hashWithoutSource: hashWithoutSource, hashFor: hashFor,
    REPORT_CATEGORIES: REPORT_CATEGORIES, reportCategory: reportCategory, reportsInPeriod: reportsInPeriod,
    reportDirection: reportDirection, directionLabel: directionLabel,
    yRange: yRange, gridlines: gridlines, lsvSegments: lsvSegments, chartModel: chartModel, stepPoints: stepPoints,
  };
});
