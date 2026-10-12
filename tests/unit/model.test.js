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

test("reports fixture: hours only, no device ids, no accuracy", () => {
  for (const r of reports.reports) {
    assert.deepEqual(Object.keys(r).sort(), ["category", "lat", "lon", "time"]);
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
