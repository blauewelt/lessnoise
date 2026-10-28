// Unit tests of docs/map/model.js (wording, colour scales, reports, 24-hour chart model).
// Run: node --test tests/unit/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const M = require("../../docs/map/model.js");
const cell = require("../../server/example_cell.json");
const reports = require("../../server/example_reports.json");

test("view fragment: karte.html#lat=…&lon=…&z=… (the app's tab «Karte»)", () => {
  const B = [[45.80, 5.90], [47.85, 10.55]];
  assert.deepEqual(M.parseView("#lat=47.37690&lon=8.54170&z=16", B, 7, 18), { lat: 47.3769, lon: 8.5417, z: 16 });
  assert.deepEqual(M.parseView("lat=46.5&lon=6.6&z=7", B, 7, 18), { lat: 46.5, lon: 6.6, z: 7 });
  assert.deepEqual(M.parseView("#z=18&lon=10.55&lat=45.8", B, 7, 18), { lat: 45.8, lon: 10.55, z: 18 }, "any order, edges included");
  for (const bad of [
    "", "#", "#lat=47.37&lon=8.54", "#lat=47.37&lon=8.54&z=19", "#lat=47.37&lon=8.54&z=6",
    "#lat=48.5&lon=8.54&z=16", "#lat=47.37&lon=11&z=16", "#lat=45.79&lon=8.54&z=16",
    "#lat=47.37&lon=8.54&z=16&x=1", "#lat=47.37&lat=46&lon=8.54&z=16", "#lat=47,37&lon=8.54&z=16",
    "#lat=1e1&lon=8.54&z=16", "#lat=NaN&lon=8.54&z=16", "#lat=Infinity&lon=8.54&z=16", "#lat=&lon=8.54&z=16",
    "#lat=47.37&lon=8.54&z=16=1", "#cell=h26833_12479", "#lat=0x2f&lon=8.54&z=16",
    "#lat=47.37&lon=8.54&z=16" + "&".repeat(90),
  ]) assert.equal(M.parseView(bad, B, 7, 18), null, bad);
  assert.equal(M.parseView(undefined, B, 7, 18), null);
});

test("numbers use the decimal comma", () => {
  assert.equal(M.fmt1(54.9), "54,9");
  assert.equal(M.fmt1(55), "55,0");
  assert.equal(M.fmt1(null), "–");
  assert.equal(M.fmtPct(0.97), "97 %");
  assert.equal(M.fmtHours(37.5), "37,5 h");
  assert.equal(M.fmtHours(null), "–");
});

test("calendar days and labels", () => {
  assert.equal(M.addDays("2026-10-07", 1), "2026-10-08");
  assert.equal(M.addDays("2026-03-01", -1), "2026-02-28");
  assert.equal(M.addDays("2026-10-25", 1), "2026-10-26");   // DST end: calendar days only
  assert.equal(M.daysBetween("2026-07-10", "2026-10-07"), 89);
  assert.equal(M.dayLabel("2026-10-08"), "Do 8. Oktober 2026");
  assert.equal(M.dayShort("2026-10-08"), "8. Okt.");
  assert.equal(M.spanLabel("2026-10-08", 7), "2.–8. Okt.");
  assert.equal(M.spanLabel("2026-10-08", 30), "9. Sept.–8. Okt.");
  assert.equal(M.spanLabel("2026-10-08", 1), "8. Okt.");
  assert.equal(M.periodLabel("2026-10-08", 7), "2.–8. Oktober 2026");
  assert.equal(M.periodLabel("2026-10-08", 30), "9. September – 8. Oktober 2026");
  assert.equal(M.periodLabel("2027-01-04", 7), "29. Dezember 2026 – 4. Januar 2027");
  assert.equal(M.stampLabel("2026-10-08T06:40:00+02:00"), "8. Okt. 2026, 06:40");
  assert.equal(M.hourLabel("2026-10-08T14:00:00+02:00"), "Do 8. Okt., 14–15 Uhr");
  assert.equal(M.hourLabel("2026-10-08T23:00:00+02:00"), "Do 8. Okt., 23–0 Uhr");
});

test("the two periods", () => {
  assert.deepEqual(Object.keys(M.PERIODS), ["last_7_days", "last_30_days"]);
  assert.equal(M.PERIODS.last_7_days.label, "Letzte 7 Tage");
  assert.equal(M.PERIODS.last_30_days.label, "Letzte 30 Tage");
  assert.equal(M.PERIODS.last_30_days.days, 30);
});

test("mean level: neutral 5-dB classes, light to dark, no LSV ticks in the legend", () => {
  const m = M.METRICS.laeq;
  assert.deepEqual(m.breaks, [45, 50, 55, 60, 65]);
  assert.equal(m.colours.length, m.breaks.length + 1);
  assert.equal(m.coloursDark.length, m.breaks.length + 1);
  assert.equal(M.LSV_TICKS, undefined);
  // a value on the edge belongs to the louder class
  assert.notEqual(M.colourFor("laeq", { laeq_db: 49.9 }), M.colourFor("laeq", { laeq_db: 50.0 }));
  assert.equal(M.colourFor("laeq", { laeq_db: 50.0 }), M.colourFor("laeq", { laeq_db: 54.9 }));
  assert.equal(M.colourFor("laeq", { laeq_db: 70 }, true), m.coloursDark[5]);
  assert.equal(M.colourFor("laeq", null), null);
  assert.equal(M.colourFor("loud", { events_per_h_by_category: { loud_vehicle: 1.2 } }), M.METRICS.loud.colours[2]);
  assert.equal(M.colourFor("peaks", { loud_events_per_h: 0.6 }), M.METRICS.peaks.colours[2]);
  assert.ok(Math.abs(M.tickPosition("laeq", 45) - 1 / 6) < 1e-12);
});

test("LSV reference values: 6–22 Uhr and 22–6 Uhr, ES II", () => {
  assert.deepEqual(M.LSV_BANDS.map((b) => [b.from, b.to, b.pw, b.igw]), [[6, 22, 55, 60], [22, 6, 45, 50]]);
  assert.match(M.LSV_BANDS[0].text, /^6–22 Uhr/);
  assert.match(M.LSV_BANDS[1].text, /^22–6 Uhr/);
  // 22–6 Uhr wraps midnight: three x ranges in clock order
  const segs = M.lsvSegments();
  assert.deepEqual(segs.map((s) => [s.x0 * 24, s.x1 * 24, s.pw]), [[0, 6, 45], [6, 22, 55], [22, 24, 45]]);
});

test("0.9.3: the detector class loud_vehicle is «Lautes Fahrzeug» (plural «Laute Fahrzeuge» per hour)", () => {
  assert.equal(M.source("loud_vehicle").name, "Lautes Fahrzeug");
  assert.equal(M.CATEGORY_NAMES.loud_vehicle, "Lautes Fahrzeug");
  assert.equal(M.sourceMetric("loud_vehicle").label, "Laute Fahrzeuge/h");
  assert.equal(M.sourceMetric("loud_vehicle").title, "Ereignisse «Lautes Fahrzeug» pro Stunde Messzeit");
  assert.equal(M.sourceMetric("road_traffic").label, "Strassenverkehr/h");
  assert.equal(M.METRICS.loud.label, "Laute Fahrzeuge/h");
  // the old name is gone from the map's code and page; the report category «Poser» (old reports) stays
  for (const f of ["docs/map/model.js", "docs/map/karte.js", "docs/karte.html"]) {
    const text = fs.readFileSync(path.join(__dirname, "../..", f), "utf8");
    assert.ok(!/Töff (&|&amp;) Poser/.test(text), f);
  }
});

test("reports: categories, letters, filter by period", () => {
  assert.deepEqual(M.REPORT_CATEGORIES.map((c) => c.id).sort(), [...reports.categories].sort());
  const letters = M.REPORT_CATEGORIES.map((c) => c.letter);
  assert.equal(new Set(letters).size, letters.length);
  assert.equal(M.reportCategory("poser").name, "Poser");
  assert.equal(M.reportCategory("neu").id, "andere");
  const all = reports.reports;
  const last7 = M.reportsInPeriod(all, reports.to, 7);
  const last30 = M.reportsInPeriod(all, reports.to, 30);
  assert.equal(last30.length, all.length);
  assert.ok(last7.length > 0 && last7.length < all.length);
  for (const r of last7) assert.ok(r.time.slice(0, 10) >= "2026-10-02");
  assert.deepEqual(M.reportsInPeriod(null, "2026-10-08", 7), []);
});

/** A stand-in for karte.js `el`: plain objects with the same attributes, children and a style. */
function fakeEl(tag, attrs, children) {
  return { tag, attrs: attrs || {}, children: (children || []).filter((c) => c !== null && c !== undefined), style: {} };
}
const classes = (n) => (n.attrs && n.attrs.class) || "";
const find = (n, cls, out = []) => {
  if (n && typeof n === "object") {
    if (classes(n).split(" ").includes(cls)) out.push(n);
    (n.children || []).forEach((c) => find(c, cls, out));
  }
  return out;
};

test("0.9.4.5: four more categories with their own letters; a report without one is a grey dot, not «Andere»", () => {
  assert.deepEqual(M.REPORT_CATEGORIES.map((c) => c.id).slice(-5), ["bahn", "baustelle", "garten", "hupen", "andere"]);
  assert.equal(M.reportCategory("hupen").letter, "H");
  assert.equal(M.reportCategory("baustelle").letter, "B");
  assert.equal(M.reportCategory("garten").name, "Gartengeräte");
  assert.equal(M.reportCategory("bahn").letter, "Z");
  for (const none of [null, undefined]) assert.equal(M.reportCategory(none), M.REPORT_NONE);
  assert.equal(M.REPORT_NONE.letter, "");
  assert.notEqual(M.REPORT_NONE.id, "andere");
  assert.ok(!M.REPORT_CATEGORIES.some((c) => c.id === M.REPORT_NONE.id));
  const none = M.reportMarker({ time: "2026-10-08T14:00:00+02:00", category: null }, fakeEl);
  assert.equal(none.className, "report report-none");
  assert.deepEqual(none.html.children.map(classes), ["report-dot"]);
  assert.equal(none.text, "Ohne Angabe · " + M.hourLabel("2026-10-08T14:00:00+02:00"));
  assert.deepEqual(M.reportMarker({ time: "2026-10-08T14:00:00+02:00", category: "hupen" }, fakeEl).html.children[0].attrs.text, "H");
  // every colour class the page uses exists in karte.css
  const css = fs.readFileSync(path.join(__dirname, "../../docs/map/karte.css"), "utf8");
  for (const c of M.REPORT_CATEGORIES.concat([M.REPORT_NONE])) assert.match(css, new RegExp("\\.report-" + c.id + " \\{ --rc: #[0-9a-f]{6}"), c.id);
  const colours = M.REPORT_CATEGORIES.concat([M.REPORT_NONE]).map((c) => css.match(new RegExp("\\.report-" + c.id + " \\{ --rc: (#[0-9a-f]{6})"))[1]);
  assert.equal(new Set(colours).size, colours.length, "distinct colours");
});

test("0.9.4.5: several vehicles — count 1–9, a convoy behind the front marker, or «×n» without a direction", () => {
  assert.equal(M.reportCount({ count: 3 }), 3);
  for (const one of [{}, { count: null }, { count: 1 }, { count: 0 }, { count: 10 }, { count: 2.5 }, { count: "3" }]) {
    assert.equal(M.reportCount(one), 1, JSON.stringify(one));
  }
  assert.deepEqual(M.convoyOffsets(90, 1), []);
  // driving east: the others to the west (left), on the same line, 13 then 11 px apart
  assert.deepEqual(M.convoyOffsets(90, 3), [{ x: -13, y: 0 }, { x: -24, y: 0 }]);
  // driving north: behind is down the screen
  assert.deepEqual(M.convoyOffsets(0, 2), [{ x: 0, y: 13 }]);
  const [o] = M.convoyOffsets(225, 2);
  assert.ok(Math.abs(o.x - 9.2) < 0.05 && Math.abs(o.y + 9.2) < 0.05, JSON.stringify(o));
  assert.equal(M.reportText("Auto", "14 Uhr", 87, 84, 3), "Auto · 3 Fahrzeuge · 14 Uhr · Richtung Ost · 84 dB");
  assert.equal(M.reportText("Auto", "14 Uhr", null, null, 1), "Auto · 14 Uhr", "1 is never shown");
  const t = "2026-10-08T14:00:00+02:00";
  const convoy = M.reportMarker({ time: t, category: "auto", direction: 90, count: 3 }, fakeEl);
  assert.equal(convoy.className, "report report-auto report-heading report-many");
  assert.deepEqual(convoy.html.children.map(classes), ["report-tail", "report-tail", "report-shape", ""]);
  assert.deepEqual(find(convoy.html, "report-tail").map((n) => n.style.transform), ["translate(-24px, 0px)", "translate(-13px, 0px)"]);
  for (const s of find(convoy.html, "report-tail-shape").concat(find(convoy.html, "report-shape"))) {
    assert.equal(s.style.transform, `rotate(${M.headingShapeRotation(90)}deg)`);
  }
  assert.equal(find(convoy.html, "report-shape").length, 1, "one teardrop with data-direction per report");
  assert.equal(convoy.html.attrs["data-count"], "3");
  assert.match(convoy.text, /^Auto · 3 Fahrzeuge · /);
  const badge = M.reportMarker({ time: t, category: "auto", count: 4 }, fakeEl);
  assert.equal(badge.className, "report report-auto report-many");
  assert.deepEqual(find(badge.html, "report-count").map((n) => n.attrs.text), ["×4"]);
  assert.equal(find(badge.html, "report-tail").length, 0);
  const single = M.reportMarker({ time: t, category: "auto", direction: 90 }, fakeEl);
  assert.equal(find(single.html, "report-tail").length + find(single.html, "report-count").length, 0);
  // the fixture has convoys and badges
  const counts = reports.reports.filter((r) => M.reportCount(r) > 1);
  assert.ok(counts.some((r) => M.reportDirection(r) !== null) && counts.some((r) => M.reportDirection(r) === null));
  assert.ok(reports.reports.some((r) => r.category === null));
  // the legend: every category, «Ohne Angabe», the corner and the convoy
  const legend = M.reportLegendItems(fakeEl).map((li) => li.children[li.children.length - 1]);
  assert.deepEqual(legend, M.REPORT_CATEGORIES.map((c) => c.name).concat(["Ohne Angabe", "Spitze = Fahrtrichtung", "= 3 Fahrzeuge"]));
});

test("reports: direction of travel (app 0.6.7), whole degrees or none", () => {
  assert.equal(M.reportDirection({ direction: 87 }), 87);
  assert.equal(M.reportDirection({ direction: 0 }), 0);
  assert.equal(M.reportDirection({ direction: 359.4 }), 359);
  assert.equal(M.reportDirection({ direction: 359.6 }), 0);
  for (const bad of [null, undefined, -1, 360, "90", NaN]) assert.equal(M.reportDirection({ direction: bad }), null);
  assert.equal(M.reportDirection({}), null, "reports of older apps have none");
  assert.equal(M.directionLabel(0), "Richtung Nord");
  assert.equal(M.directionLabel(22), "Richtung Nord");
  assert.equal(M.directionLabel(23), "Richtung Nordost");
  assert.equal(M.directionLabel(87), "Richtung Ost");
  assert.equal(M.directionLabel(180), "Richtung Süd");
  assert.equal(M.directionLabel(338), "Richtung Nord");
  assert.equal(M.directionLabel(null), "");
  const dirs = reports.reports.map(M.reportDirection);
  assert.ok(dirs.some((d) => d === null) && dirs.some((d) => d !== null), "the fixture has both");
});

test("a report's peak level: whole dB or nothing, shown after the direction (app 0.8.5)", () => {
  assert.equal(M.reportLevel({ lafmax_db: 84 }), 84);
  assert.equal(M.reportLevel({ lafmax_db: 0 }), 0);
  for (const bad of [null, undefined, -1, 141, "84", NaN]) assert.equal(M.reportLevel({ lafmax_db: bad }), null);
  assert.equal(M.reportLevel({}), null, "reports of older apps have none");
  assert.equal(M.reportText("Töff", "14 Uhr", 87, 84), "Töff · 14 Uhr · Richtung Ost · 84 dB");
  assert.equal(M.reportText("Töff", "14 Uhr", null, 84), "Töff · 14 Uhr · 84 dB");
  assert.equal(M.reportText("Töff", "14 Uhr", 87, null), "Töff · 14 Uhr · Richtung Ost");
  assert.equal(M.reportText("Töff", "14 Uhr", null, null), "Töff · 14 Uhr");
  const levels = reports.reports.map(M.reportLevel);
  assert.ok(levels.some((v) => v === null) && levels.some((v) => v !== null), "the fixture has both");
});

test("reports fixture: hours only, no device ids, no accuracy", () => {
  for (const r of reports.reports) {
    // app 0.6.7: plus `direction`, whole degrees or null; never north, compass accuracy or samples
    // app 0.8.5: plus `lafmax_db`, whole dB or null; never the LAeq, the seconds or the calibration flag
    // 0.9.4.5 (schema v9): plus `count`, a whole number 1–9 (always present); `category` may be null
    assert.deepEqual(Object.keys(r).sort(), ["category", "count", "direction", "lafmax_db", "lat", "lon", "time"]);
    assert.ok(Number.isInteger(r.count) && r.count >= 1 && r.count <= 9);
    assert.ok(r.category === null || reports.categories.includes(r.category));
    assert.ok(r.direction === null || (Number.isInteger(r.direction) && r.direction >= 0 && r.direction <= 359));
    assert.ok(r.lafmax_db === null || (Number.isInteger(r.lafmax_db) && r.lafmax_db >= 0 && r.lafmax_db <= 140));
    assert.match(r.time, /^\d{4}-\d{2}-\d{2}T\d{2}:00:00\+0[12]:00$/);
  }
});

test("y range: L90 − 5 to the loudest level + 5, rounded to 5 dB, always contains 40–65", () => {
  assert.deepEqual(M.yRange(cell.profile.hours), { lo: 35, hi: 70 });
  assert.deepEqual(M.yRange([]), { lo: 35, hi: 70 });
  assert.deepEqual(M.yRange([{ laeq_db: 80, l90_db: 70, l10_db: 84 }]), { lo: 35, hi: 90 });
  assert.deepEqual(M.gridlines({ lo: 35, hi: 70 }), [40, 50, 60, 70]);
});

test("chart model of the fixture profile: 24 hours, one run, labels 00–24", () => {
  const cm = M.chartModel(cell.profile.hours);
  assert.equal(cm.slots.length, 24);
  assert.equal(cm.runs.length, 1);
  assert.equal(cm.gaps.length, 0);
  assert.equal(cm.slots[0].x0, 0);
  assert.equal(cm.slots[23].x1, 1);
  assert.deepEqual(cm.labels.map((l) => l.text), ["00", "03", "06", "09", "12", "15", "18", "21", "24"]);
  const max = Math.max(...cell.profile.hours.map((h) => h.events_per_h_by_category.loud_vehicle));
  assert.equal(cm.highlightMax, max);
  assert.equal(cm.lsv.length, 3);
});

test("no line across a missing hour: a null hour splits the runs and becomes a gap", () => {
  const hours = JSON.parse(JSON.stringify(cell.profile.hours));
  Object.assign(hours[3], { laeq_db: null, l90_db: null, l10_db: null });
  const cm = M.chartModel(hours);
  assert.equal(cm.runs.length, 2);
  assert.equal(cm.runs[0].length, 3);
  assert.equal(cm.runs[1].length, 20);
  assert.equal(cm.gaps.length, 1);
  assert.equal(cm.gaps[0].hour, 3);
  const pts = M.stepPoints(cm.runs[0], "laeq");
  assert.equal(pts.length, 6);
  assert.ok(pts[pts.length - 1][0] <= cm.gaps[0].x0 + 1e-12);
});

test("hours missing from the file are gaps; an empty profile has no chart", () => {
  const cm = M.chartModel(cell.profile.hours.filter((h) => h.hour >= 6 && h.hour < 22));
  assert.equal(cm.slots.length, 24);
  assert.equal(cm.gaps.length, 8);
  assert.equal(cm.runs.length, 1);
  assert.equal(M.chartModel([]), null);
});

test("no word «Nacht» / «night» in the map's scripts and page", () => {
  for (const f of ["docs/karte.html", "docs/map/karte.js", "docs/map/model.js", "docs/map/karte.css"]) {
    const text = fs.readFileSync(path.join(__dirname, "..", "..", f), "utf8");
    assert.doesNotMatch(text, /nacht|night|nächt/i, f);
  }
});

test("headingShapeRotation turns the teardrop's south-west corner to the bearing", () => {
  assert.equal(M.headingShapeRotation(225), 0);
  assert.equal(M.headingShapeRotation(0), 135);
  assert.equal(M.headingShapeRotation(90), 225);
  assert.equal(M.headingShapeRotation(180), 315);
  assert.equal(M.headingShapeRotation(270), 45);
  for (let d = 0; d < 360; d += 7) {
    const r = M.headingShapeRotation(d);
    assert.ok(r >= 0 && r < 360);
    assert.equal((225 + r) % 360, d);
  }
});
