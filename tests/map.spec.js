// Browser tests of the noise map page (docs/karte.html), at 360×740 and 1280×800
// (projects in playwright.config.js). The API is answered from the server fixtures and the
// swisstopo tiles from a placeholder (tests/support/mock.js); any other host fails the test.
"use strict";
const { test, expect } = require("@playwright/test");
const M = require("../docs/map/model.js");
const { mockNetwork, cellsJson, cellJson, reportsJson, LATEST, PREV, API, TILE_HOST } = require("./support/mock");

const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: https://wmts.geo.admin.ch; connect-src https://api.lessnoise.ch https://api.stadtlaerm.ch; base-uri 'none'; form-action 'none'";
const UNCAL = cellsJson.cells.find((c) => !c.calibrated && c.day).cell;   // h26818_12478
const CAL = cellJson.cell;                                              // h26828_12455, calibrated
const FX = cellsJson.cells.find((c) => c.cell === CAL);
const dec = (x) => x.toFixed(1).replace(".", ",");

let seen;
let hosts;
let consoleErrors;

test.beforeEach(async ({ page, baseURL }) => {
  seen = await mockNetwork(page, { baseURL });
  hosts = new Set();
  consoleErrors = [];
  page.on("request", (r) => {
    const u = r.url();
    if (!u.startsWith("data:")) hosts.add(new URL(u).host);
  });
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
  await page.goto("/karte.html");
  await expect(page.locator("#karte")).toHaveAttribute("data-ready", "1");
  await expect(page.locator("#karte")).toHaveAttribute("data-reports", String(reportsJson.reports.length));
});

test.afterEach(async () => {
  // No request to any host other than the local server, swisstopo and the (mocked) new API host.
  const allowed = new Set(["localhost:8090", TILE_HOST, "api.lessnoise.ch"]);
  const bad = [...hosts].filter((h) => !allowed.has(h));
  expect(bad, "requests to unexpected hosts").toEqual([]);
  expect(seen.foreign, "aborted foreign requests").toEqual([]);
  // a 404 from the (mocked) API is a tested answer, which Chromium also logs as a failed resource
  expect(consoleErrors.filter((e) => !/status of 404/.test(e)), "console errors").toEqual([]);
});

/** Taps (phone) or clicks (desktop) the centre of a cell on the map, zoomed in to `zoom`.
 *  The reports layer is switched off first, so no marker covers the cell. */
async function hitCell(page, cellId, isMobile, zoom = 16) {
  await page.locator("#reports-toggle").uncheck();
  await page.locator("#karte").scrollIntoViewIfNeeded();
  await page.evaluate(([id, z]) => new Promise((resolve) => {
    const k = window.__karte;
    k.map.once("moveend", () => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    k.map.setView(window.Lv95.cellCenter(id), z, { animate: false });
  }), [cellId, zoom]);
  const pt = await page.evaluate((id) => {
    const p = window.__karte.map.latLngToContainerPoint(window.Lv95.cellCenter(id));
    const r = document.getElementById("karte").getBoundingClientRect();
    return { x: r.left + p.x, y: r.top + p.y };
  }, cellId);
  const hit = await page.evaluate(({ x, y }) => {
    const e = document.elementFromPoint(x, y);
    return e && e.getAttribute("data-cell");
  }, pt);
  expect(hit, "the cell is what is under the pointer").toBe(cellId);
  if (isMobile) await page.touchscreen.tap(pt.x, pt.y);
  else await page.mouse.click(pt.x, pt.y);
}

test("renders: map, tiles from swisstopo, one shape per fixture cell, sentence about sources", async ({ page }) => {
  await expect(page.locator("#karte .leaflet-tile-pane img").first()).toBeVisible();
  const tileSrc = await page.locator("#karte .leaflet-tile-pane img").first().getAttribute("src");
  expect(tileSrc).toMatch(/^https:\/\/wmts\.geo\.admin\.ch\/1\.0\.0\/ch\.swisstopo\.pixelkarte-grau\/default\/current\/3857\/\d+\/\d+\/\d+\.jpeg$/);
  expect(seen.tiles).toBeGreaterThan(0);
  const ids = await page.locator("#karte path.cell").evaluateAll((ps) => [...new Set(ps.map((p) => p.getAttribute("data-cell")))]);
  expect(ids.sort()).toEqual(cellsJson.cells.map((c) => c.cell).sort());
  await expect(page.locator(".leaflet-control-attribution")).toContainText("© swisstopo");
  await expect(page.locator("#quellen")).toHaveText(
    "Diese Seite lädt die Kartenbilder von swisstopo und die Messwerte und Meldungen vom Server des Projekts (api.lessnoise.ch, übergangsweise api.stadtlaerm.ch), sonst nichts von anderen Servern.");
  await expect(page).toHaveTitle("Lärmkarte – Weniger Lärm");
  await expect(page.locator("#span")).toHaveText("2.–8. Oktober 2026");
  await expect(page.locator('#period input[value="last_7_days"]')).toBeChecked();
  expect(seen.api).toContain("cells.json");
  expect(seen.api).toContain("reports.json");
  expect(new Set(seen.hosts)).toEqual(new Set(["api.lessnoise.ch"]));
  const z = await page.evaluate(() => window.__karte.map.getMinZoom());
  expect(z).toBe(7);  // all of Switzerland
});

test("no horizontal scrolling at this width", async ({ page }) => {
  const w = await page.evaluate(() => ({ doc: document.documentElement.scrollWidth, vw: window.innerWidth }));
  expect(w.doc).toBeLessThanOrEqual(w.vw);
});

test("no word «Nacht» anywhere on the page, also with the card open and after switching", async ({ page, isMobile }) => {
  const text = () => page.evaluate(() => document.body.innerText + " " + document.title +
    [...document.querySelectorAll("[aria-label],[title]")].map((e) => e.getAttribute("aria-label") || e.getAttribute("title")).join(" "));
  expect(await text()).not.toMatch(/nacht|nächt|night/i);
  await page.locator('#period input[value="last_30_days"]').check({ force: true });
  await hitCell(page, CAL, isMobile);
  await expect(page.locator("#card svg.daychart")).toBeVisible();
  expect(await text()).not.toMatch(/nacht|nächt|night/i);
  const titles = await page.locator("#card svg title").allTextContents();
  expect(titles.join(" ")).not.toMatch(/nacht|night/i);
});

test("legend: neutral scale for the mean level, no LSV values in the legend", async ({ page }) => {
  await expect(page.locator("#legend-title")).toHaveText("Mittlerer Pegel (LAeq) über die gemessene Zeit, dB(A) · Letzte 7 Tage");
  const ticks = await page.locator("#legend-ticks span").allTextContents();
  expect(ticks).toEqual(["45", "50", "55", "60", "65"]);
  const bar = await page.locator("#legend-bar span").evaluateAll((s) => s.map((x) => x.style.background));
  expect(bar.length).toBe(M.METRICS.laeq.colours.length);
  await expect(page.locator("#legend-lsv")).toHaveCount(0);
  await expect(page.locator("#legend")).not.toContainText("Planungswert");
  await expect(page.locator("#legend-note")).toContainText("keine rechtliche Beurteilung");
  await expect(page.locator("#legend-note")).toContainText("Tagesverlauf");
});

test("metric selector recolours the cells and changes the legend", async ({ page }) => {
  const fill = () => page.locator(`#karte path.cell[data-cell="${CAL}"]`).first().getAttribute("fill");
  const before = await fill();
  await page.locator('#metric input[value="loud"]').check({ force: true });
  await expect(page.locator("#legend-title")).toContainText("Töff & Poser");
  expect(await fill()).not.toBe(before);
  await page.locator('#metric input[value="peaks"]').check({ force: true });
  await expect(page.locator("#legend-title")).toContainText("Ereignisse ab 75 dB(A) pro Stunde Messzeit");
  // keyboard: arrow keys move between the radio buttons
  await page.locator('#metric input[value="loud"]').focus();
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator('#metric input[value="events"]')).toBeChecked();
});

test("period switch: «Letzte 30 Tage» changes the span, the legend, the list and the card", async ({ page, isMobile }) => {
  const listVal = page.locator(`#celllist-items button[data-cell="${CAL}"] .val`);
  await expect(listVal).toHaveText(dec(FX.last_7_days.laeq_db) + " dB(A)");
  await page.locator('#period input[value="last_30_days"]').check({ force: true });
  await expect(page.locator("#span")).toHaveText("9. September – 8. Oktober 2026");
  await expect(page.locator("#legend-title")).toContainText("Letzte 30 Tage");
  await expect(listVal).toHaveText(dec(FX.last_30_days.laeq_db) + " dB(A)");
  await hitCell(page, CAL, isMobile);
  const sec = page.locator("#card-period");
  await expect(sec.locator("h3")).toContainText("Letzte 30 Tage");
  await expect(sec.locator(".row", { hasText: "Mittlerer Pegel (LAeq)" })).toContainText(dec(FX.last_30_days.laeq_db) + " dB(A)");
  await expect(sec.locator(".row", { hasText: "Gemessen" })).toContainText(`an ${FX.last_30_days.days_with_data} von 30 Tagen`);
  await expect(page.locator("#card-other h3")).toContainText("Letzte 7 Tage");
  // back to 7 days
  await page.locator('#period input[value="last_7_days"]').check({ force: true });
  await expect(page.locator("#card-period h3")).toContainText("Letzte 7 Tage");
});

test("tapping a cell opens the card: period figures with their span, the day, the 24-hour profile", async ({ page, isMobile }) => {
  await hitCell(page, CAL, isMobile);
  const card = page.locator("#card");
  await expect(card).toBeVisible();
  await expect(page.locator("#card-title")).toContainText(CAL);
  await expect(page.locator("#badge-uncal")).toHaveCount(0);
  await expect(card.locator(".card-sub")).toContainText("2 Geräte");
  const laeq = card.locator("#card-period .row", { hasText: "Mittlerer Pegel (LAeq)" });
  await expect(laeq).toContainText(dec(FX.last_7_days.laeq_db) + " dB(A)");
  await expect(laeq.locator(".when")).toHaveText("2.–8. Okt.");
  await expect(card.locator("#card-period .row", { hasText: "Gemessen" }))
    .toContainText(`${dec(FX.last_7_days.measured_hours)} h an ${FX.last_7_days.days_with_data} von 7 Tagen`);
  await expect(card.locator("#card-period .row", { hasText: "Ab 75 dB(A)" })).toContainText(dec(FX.last_7_days.loud_events_per_h) + " pro h");
  await expect(card.locator("#card-day h3")).toHaveText("Am Do 8. Oktober 2026");
  await expect(card.locator("#card-day .row").first()).toContainText(dec(FX.day.laeq_db) + " dB(A)");
  // the chart: 24 hours, one run, band, Töff & Poser bars, the LSV bands by clock time
  const chart = card.locator("svg.daychart");
  await expect(chart).toBeVisible();
  await expect(chart.locator("path.laeq")).toHaveCount(1);
  await expect(chart.locator("path.band")).toHaveCount(1);
  await expect(chart.locator("rect.hit")).toHaveCount(24);
  const bars = cellJson.profile.hours.filter((h) => h.events_per_h_by_category.loud_vehicle > 0).length;
  await expect(chart.locator("rect.bar.hl")).toHaveCount(bars);
  const bands = await chart.locator("rect.lsv-band").evaluateAll((r) => r.map((x) => [x.getAttribute("data-pw"), x.getAttribute("data-igw")]));
  expect(bands).toEqual([["45", "50"], ["55", "60"], ["45", "50"]]);
  const lsv = card.locator("#lsv-list li");
  await expect(lsv).toHaveCount(2);
  await expect(lsv.nth(0)).toHaveText("6–22 Uhr: Planungswert 55 / Immissionsgrenzwert 60 (ES II)");
  await expect(lsv.nth(1)).toHaveText("22–6 Uhr: 45 / 50");
  await expect(card.locator(".lsv-note")).toContainText("Beurteilungspegel Lr");
  await expect(card.locator(".lsv-note")).toContainText("keine rechtliche Beurteilung");
  await expect(card.locator(".chart-sec h3")).toContainText("Tagesverlauf");
  await expect(card.locator(".chart-sec h3 small")).toHaveText("Mittel pro Stunde, 9. Sept.–8. Okt.");
  // close
  await page.locator("#card-close").click();
  await expect(card).toBeHidden();
});

test("the uncalibrated fixture cell shows the «unkalibriert» badge", async ({ page, isMobile }) => {
  await hitCell(page, UNCAL, isMobile);
  await expect(page.locator("#card-title")).toContainText(UNCAL);
  await expect(page.locator("#badge-uncal")).toHaveText("unkalibriert");
  await expect(page.locator(`#karte path.cell.uncal[data-cell="${UNCAL}"]`).first()).toBeAttached();
  // its history file is not in the fixtures: the card says so instead of breaking
  await expect(page.locator("#card")).toContainText("keinen Verlauf");
});

test("a missing hour is not bridged by the LAeq line", async ({ page, isMobile }) => {
  const file = JSON.parse(JSON.stringify(cellJson));
  Object.assign(file.profile.hours[3], { laeq_db: null, l90_db: null, l10_db: null });
  await page.route(`${API}cells/${CAL}.json`, (r) =>
    r.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(file) }));
  await hitCell(page, CAL, isMobile);
  const chart = page.locator("#card svg.daychart");
  await expect(chart.locator("path.laeq")).toHaveCount(2);
  await expect(chart.locator("rect.gap")).toHaveCount(1);
  await expect(page.locator("#card")).toContainText("1 Stunde mit zu wenig Messzeit");
});

test("day steps: ‹ moves the period back a day, › returns, a 404 day shows «keine Daten»", async ({ page }) => {
  const prev = page.locator("#prev"), next = page.locator("#next");
  await expect(next).toBeDisabled();
  await expect(prev).toBeEnabled();
  await prev.click();
  await expect(page.locator("#span")).toHaveText("1.–7. Oktober 2026");
  const ids = await page.locator("#karte path.cell").evaluateAll((ps) => [...new Set(ps.map((p) => p.getAttribute("data-cell")))]);
  expect(ids.length).toBe(PREV.file.cells.length);
  expect(seen.api).toContain(`days/${PREV.day}.json`);
  // the reports follow the period
  const shown = M.reportsInPeriod(reportsJson.reports, PREV.day, 7).length;
  await expect(page.locator("#karte .leaflet-marker-icon.report")).toHaveCount(shown);
  await prev.click();                        // the fixture server has no file for 6 October
  await expect(page.locator("#span")).toContainText("keine Daten");
  await expect(page.locator("#status")).toContainText("keine Daten");
  await expect(page.locator("#karte path.cell")).toHaveCount(0);
  await next.click();
  await next.click();
  await expect(page.locator("#span")).toHaveText("2.–8. Oktober 2026");
  await expect(page.locator("#status")).toBeHidden();
  await expect(next).toBeDisabled();
  expect(LATEST).toBe("2026-10-08");
});

test("reports layer: on by default, one lettered marker per report of the period, toggle hides them", async ({ page, isMobile }) => {
  const markers = page.locator("#karte .leaflet-marker-icon.report");
  const in7 = M.reportsInPeriod(reportsJson.reports, LATEST, 7);
  await expect(page.locator("#reports-toggle")).toBeChecked();
  await expect(markers).toHaveCount(in7.length);
  await expect(page.locator("#reports-count")).toHaveText(`${in7.length} Meldungen in diesem Zeitraum`);
  const legend = await page.locator("#legend-reports li:not(.reports-head)").allTextContents();
  expect(legend.map((t) => t.slice(1))).toEqual(["Motorrad", "Poser", "Lastwagen", "Auto", "Andere"]);
  // the marker carries category and hour, nothing else
  const labels = await markers.locator("span").evaluateAll((s) => s.map((x) => x.getAttribute("aria-label")));
  for (const l of labels) expect(l).toMatch(/^(Motorrad|Poser|Lastwagen|Auto|Andere) · (Mo|Di|Mi|Do|Fr|Sa|So) \d+\. \S+, \d+–\d+ Uhr$/);
  const first = in7[0];
  expect(labels).toContain(`${M.reportCategory(first.category).name} · ${M.hourLabel(first.time)}`);
  if (!isMobile) {
    await page.locator("#karte").scrollIntoViewIfNeeded();
    const m = markers.first();
    await m.hover({ force: true });
    await expect(page.locator(".leaflet-tooltip.report-tip")).toContainText(" Uhr");
  }
  // 30 days: every report of the file
  await page.locator('#period input[value="last_30_days"]').check({ force: true });
  await expect(markers).toHaveCount(reportsJson.reports.length);
  // off and on again
  await page.locator("#reports-toggle").uncheck();
  await expect(markers).toHaveCount(0);
  await expect(page.locator("#legend-reports")).toBeHidden();
  await page.locator("#reports-toggle").check();
  await expect(markers).toHaveCount(reportsJson.reports.length);
});

test("keyboard: the list of cells opens the card, Escape closes it", async ({ page }) => {
  await page.locator("#celllist summary").focus();
  await page.keyboard.press("Enter");
  const btn = page.locator(`#celllist-items button[data-cell="${CAL}"]`);
  await btn.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#card")).toBeVisible();
  await expect(page.locator("#card")).toBeFocused();
  await expect(page.locator("#card-period .row", { hasText: "Mittlerer Pegel" }).first()).toContainText(dec(FX.last_7_days.laeq_db) + " dB(A)");
  await page.keyboard.press("Escape");
  await expect(page.locator("#card")).toBeHidden();
  await expect(btn).toBeFocused();
});

test("CSP meta is exactly the specified policy (new and old API host), no inline scripts, no-referrer", async ({ page }) => {
  const csp = await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute("content");
  expect(csp).toBe(CSP);
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  const inline = await page.locator("script:not([src])").count();
  expect(inline).toBe(0);
  const srcs = await page.locator("script[src]").evaluateAll((s) => s.map((x) => x.getAttribute("src")));
  for (const s of srcs) expect(s).toMatch(/^(vendor|map)\/[\w/.-]+\.js\?v=[0-9a-f]{8}$/);
  await expect(page.locator("#karte")).toHaveAttribute("data-api", "https://api.lessnoise.ch/v1/map/");
  await expect(page.locator("#karte")).toHaveAttribute("data-api-fallback", "https://api.stadtlaerm.ch/v1/map/");
});

test("api.lessnoise.ch unreachable: the page falls back to api.stadtlaerm.ch once", async ({ browser, baseURL }) => {
  const ctx = await browser.newContext({ viewport: { width: 360, height: 740 } });
  const p = await ctx.newPage();
  const s = await mockNetwork(p, { baseURL, primaryDown: true });
  await p.goto("/karte.html");
  await expect(p.locator("#karte")).toHaveAttribute("data-ready", "1");
  await expect(p.locator("#karte")).toHaveAttribute("data-reports", String(reportsJson.reports.length));
  expect(new Set(s.hosts)).toEqual(new Set(["api.stadtlaerm.ch"]));
  expect(s.foreign).toEqual([]);
  await ctx.close();
});

test("dark mode uses the site's colours", async ({ browser, baseURL }) => {
  const ctx = await browser.newContext({ colorScheme: "dark", viewport: { width: 360, height: 740 } });
  const p = await ctx.newPage();
  const s = await mockNetwork(p, { baseURL });
  await p.goto("/karte.html");
  await expect(p.locator("#karte")).toHaveAttribute("data-ready", "1");
  const bg = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(bg).toBe("rgb(13, 22, 38)");        // --paper in dark mode (style.css)
  const fill = await p.locator("#karte path.cell").first().getAttribute("fill");
  expect(M.METRICS.laeq.coloursDark).toContain(fill);
  expect(s.foreign).toEqual([]);
  await ctx.close();
});
