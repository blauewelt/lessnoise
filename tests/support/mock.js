// Network stand-ins for the map page: the API is answered from the server's fixtures
// (server/example_cells.json, example_cell.json, example_reports.json — what the real publisher
// writes), swisstopo tiles with a local grey placeholder. Every other non-local request is
// aborted and recorded, so a test can fail on it. Used by tests/map.spec.js and
// scripts/karte_screenshot.js.
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "..");
const API = "https://api.lessnoise.ch/v1/map/";            // karte.html data-api
const API_FALLBACK = "https://api.stadtlaerm.ch/v1/map/";  // karte.html data-api-fallback
const TILE_HOST = "wmts.geo.admin.ch";

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, "server", name), "utf8"));
}
const cellsJson = fixture("example_cells.json");
const cellJson = fixture("example_cell.json");
const reportsJson = fixture("example_reports.json");
const tile = fs.readFileSync(path.join(ROOT, "tests", "fixtures", "tile-placeholder.jpeg"));

const LATEST = cellsJson.day;                          // "2026-10-08"

/** days/{date}.json of the day before the fixture's day: the fixture with every LAeq 1.5 dB
 *  lower and the first cell left out, so a test can tell the two files apart. Older days
 *  answer 404. */
function previousDay() {
  const d = new Date(LATEST + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - 1);
  const day = d.toISOString().slice(0, 10);
  const file = JSON.parse(JSON.stringify(cellsJson));
  file.day = day;
  file.cells = file.cells.slice(1);
  for (const c of file.cells) {
    for (const k of ["day", "last_7_days", "last_30_days"]) {
      if (c[k] && c[k].laeq_db !== null) c[k].laeq_db = Math.round((c[k].laeq_db - 1.5) * 10) / 10;
    }
  }
  return { day, file };
}
const PREV = previousDay();

function json(route, status, body) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Access-Control-Allow-Origin": "*", "Cache-Control": "max-age=300" },
    body: JSON.stringify(body),
  });
}

/**
 * Installs the routes on a page. Returns { foreign: [urls], api: [paths], hosts: [api hosts], tiles: n }.
 * Options: realTiles (let wmts.geo.admin.ch through — only for a session allowed to fetch);
 * primaryDown (requests to api.lessnoise.ch fail like a host without a DNS record).
 */
async function mockNetwork(page, opts = {}) {
  const seen = { foreign: [], api: [], hosts: [], tiles: 0 };
  const base = opts.baseURL || "http://localhost:8090";
  await page.route("**/*", async (route) => {
    const url = route.request().url();
    if (url.startsWith(base + "/") || url.startsWith("data:")) return route.continue();
    const root = url.startsWith(API) ? API : url.startsWith(API_FALLBACK) ? API_FALLBACK : null;
    if (root) {
      if (root === API && opts.primaryDown) return route.abort("namenotresolved");
      const p = url.slice(root.length).split("?")[0];
      seen.api.push(p);
      seen.hosts.push(new URL(root).host);
      if (p === "cells.json") return json(route, 200, cellsJson);
      if (p === "reports.json") return json(route, 200, reportsJson);
      if (p === `days/${LATEST}.json`) return json(route, 200, cellsJson);
      if (p === `days/${PREV.day}.json`) return json(route, 200, PREV.file);
      if (p === `cells/${cellJson.cell}.json`) return json(route, 200, cellJson);
      return json(route, 404, { detail: "not found" });
    }
    if (new URL(url).hostname === TILE_HOST) {
      seen.tiles++;
      if (opts.realTiles) return route.continue();
      return route.fulfill({ status: 200, contentType: "image/jpeg", body: tile });
    }
    seen.foreign.push(url);
    return route.abort("blockedbyclient");
  });
  return seen;
}

module.exports = { mockNetwork, cellsJson, cellJson, reportsJson, LATEST, PREV, API, API_FALLBACK, TILE_HOST };
