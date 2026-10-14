// Browser tests of the text pages (index.html, anleitung.html, datenschutz.html, update.html) for
// app 0.6.0: the name is «Weniger Lärm», nothing speaks of nights any more, «Stadtlärm» appears only
// where a sentence is about the old app, links go to blauewelt/lessnoise, Swiss spelling, the home
// page stays short and links the Anleitung, and every anchor that a page or the app links to exists.
// No request leaves localhost.
"use strict";
const fs = require("fs");
const path = require("path");
const { test, expect } = require("@playwright/test");

const DOCS = path.join(__dirname, "..", "docs");
const PAGES = ["/", "/anleitung.html", "/datenschutz.html", "/update.html"];
// The anchors of anleitung.html that the app (0.6.1) opens; update.html and karte.html link some of them.
const ANLEITUNG_ANCHORS = ["version", "installation", "pruefsummen", "messen", "kalibrieren", "teilen", "daten"];
let foreign;

test.beforeEach(async ({ page }) => {
  foreign = [];
  await page.route((url) => !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(url.href), (route) => {
    foreign.push(route.request().url());
    return route.abort();
  });
});

test.afterEach(async () => {
  expect(foreign, "requests to other hosts").toEqual([]);
});

/** All text of the page, including collapsed <details> and the head (title, description), without scripts. */
async function allText(page) {
  return page.evaluate(() => {
    const body = document.body.cloneNode(true);
    body.querySelectorAll("script").forEach((s) => s.remove());
    return [
      document.title,
      document.querySelector('meta[name="description"]').content,
      body.textContent,
    ].join("\n").replace(/\s+/g, " ");
  });
}

for (const path of PAGES) {
  test(`${path}: no nights, the new name, Swiss spelling`, async ({ page }) => {
    await page.goto(path);
    const text = await allText(page);
    expect(text).not.toMatch(/nacht|nächt|night|22 bis 6/i);
    expect(text).not.toContain("ß");
    expect(await page.title()).toContain("Weniger Lärm");
    // «Stadtlärm» only in paragraphs and list items that say which versions they mean (0.5.x / 0.6.0).
    const blocks = await page.locator("p, li, dt, dd, summary").allTextContents();
    for (const b of blocks.filter((t) => /Stadtlärm/.test(t))) {
      expect(b, "a paragraph naming «Stadtlärm» must name the version").toMatch(/0\.5|0\.6\.0/);
    }
    // The visible chrome never says Stadtlärm.
    const chrome = await page.locator("h1, h2, .home, a.button, footer").allTextContents();
    expect(chrome.join(" ")).not.toMatch(/Stadtlärm/);
  });

  test(`${path}: GitHub links point to blauewelt/lessnoise`, async ({ page }) => {
    await page.goto(path);
    const hrefs = await page.locator('a[href*="github.com"]').evaluateAll((as) => as.map((a) => a.href));
    for (const h of hrefs) {
      if (h.startsWith("https://docs.github.com/")) continue;
      expect(h).toMatch(/^https:\/\/github\.com\/blauewelt\/lessnoise(\/|$)/);
    }
  });
}

test("the home page keeps the anchors other pages link to, and is short", async ({ page }) => {
  await page.goto("/");
  // Every page's footer links ./#verein; the two uses, the download and the sections stay addressable.
  for (const id of ["app", "melden", "download", "version", "karte", "datenschutz", "verein"]) {
    await expect(page.locator("#" + id), "#" + id).toHaveCount(1);
  }
  // Installation, calibration and the checksums live on anleitung.html now.
  await expect(page.locator("details, .facts, .hash, ol.steps")).toHaveCount(0);
  // No screenshot of the old «Nächte» screen.
  await expect(page.locator('img[src*="nacht"], source[srcset*="nacht"]')).toHaveCount(0);
});

test("the home page: two buttons, which to choose, the version line and the link to the Anleitung", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#download a.button")).toHaveCount(2);
  // Online-Version first (the default), the Offline-Version as the more private alternative.
  await expect(page.locator("#version")).toContainText(/^Die Online-Version .* Die Offline-Version ist für alle, die noch mehr Privatsphäre möchten:/);
  await expect(page.locator("#download a.button").first()).toHaveText("Online-Version herunterladen");
  await expect(page.locator("#download .release")).toHaveText("Version 0.6.1, Android 8 oder neuer.");
  await expect(page.locator('#download a[href="anleitung.html"]')).toHaveText("Anleitung");
  await expect(page.locator('footer a[href="anleitung.html"]')).toHaveText("Anleitung");
});

test("the home page shows the app screenshot, light and dark", async ({ page }) => {
  await page.goto("/");
  const fig = page.locator("#download figure.shot");
  await expect(fig.locator('source[media="(prefers-color-scheme: dark)"]')).toHaveAttribute("srcset", "img/app-messung-dark.png");
  await expect(fig.locator("img")).toHaveAttribute("src", "img/app-messung.png");
  expect((await fig.locator("img").getAttribute("alt")).length).toBeGreaterThan(40);
  // The PNGs come from a parallel branch; until they are in docs/img/ only the markup is checked.
  const files = ["app-messung.png", "app-messung-dark.png"];
  const missing = files.filter((f) => !fs.existsSync(path.join(DOCS, "img", f)));
  test.skip(missing.length > 0, `not yet in docs/img/: ${missing.join(", ")}`);
  const ok = await fig.locator("img").evaluate((img) =>
    img.decode().then(() => img.naturalWidth > 0, () => false));
  expect(ok, "img/app-messung.png loads").toBe(true);
});

test("the Anleitung has the anchors the app and the other pages link to", async ({ page }) => {
  await page.goto("/anleitung.html");
  for (const id of ANLEITUNG_ANCHORS) {
    await expect(page.locator("#" + id), "#" + id).toHaveCount(1);
    await expect(page.locator(`nav.toc a[href="#${id}"]`), "toc → #" + id).toHaveCount(1);
  }
  await expect(page.locator("#installation .note")).toContainText("deinstallieren");
  await expect(page.locator("#installation .note")).toContainText("CSV");
  await expect(page.locator("#installation")).toContainText("«Dateien»");
  await expect(page.locator("#pruefsummen .facts")).toContainText("0.6.1");
  await expect(page.locator("#pruefsummen .hash")).toHaveCount(3);   // two files + the signing certificate
  await expect(page.locator("#teilen")).toContainText("Laut! Melden");
  await expect(page.locator("#daten")).toContainText("CSV");
});

test("every link to anleitung.html#… or ./#… on any page lands on an existing element", async ({ page }) => {
  const targets = { "anleitung.html": new Set(), "./": new Set() };
  for (const file of fs.readdirSync(DOCS).filter((f) => f.endsWith(".html"))) {
    const html = fs.readFileSync(path.join(DOCS, file), "utf8");
    for (const m of html.matchAll(/href="(anleitung\.html|\.\/)#([^"]+)"/g)) targets[m[1]].add(m[2]);
  }
  // update.html and karte.html point to the Anleitung for «Welche Version?», the installation and how to measure.
  expect([...targets["anleitung.html"]]).toEqual(expect.arrayContaining(["version", "installation", "messen"]));
  for (const [pagePath, ids] of [["/anleitung.html", targets["anleitung.html"]], ["/", targets["./"]]]) {
    await page.goto(pagePath);
    for (const id of ids) await expect(page.locator("#" + id), `${pagePath}#${id}`).toHaveCount(1);
  }
});

test("the Anleitung has the same Content-Security-Policy as the other static pages and loads nothing else", async ({ page }) => {
  const csp = async (p) => {
    await page.goto(p);
    return page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute("content");
  };
  const home = await csp("/");
  expect(await csp("/datenschutz.html")).toBe(home);
  expect(await csp("/anleitung.html")).toBe(home);
  expect(home).toContain("default-src 'none'");
  expect(home).not.toContain("script-src");
  await expect(page.locator("script")).toHaveCount(0);
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  const hosts = await page.evaluate(() =>
    performance.getEntriesByType("resource").map((r) => new URL(r.name).host));
  for (const h of hosts) expect(h).toMatch(/^(localhost|127\.0\.0\.1)(:\d+)?$/);
});

test("the privacy page names every data flow of 0.6.0", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of [
    "Online-Version",                  // the edition with internet and location
    "Laut! Melden",                    // reports
    "auf die volle Stunde abgerundeten Zeit",
    "zufällig, standardmässig um etwa 50",  // the point at Start is moved (location noise)
    "Standort verschieben",            // the app setting
    "nie im Hintergrund",
    "60 Tage",
    "auch die Meldungen",              // deletion includes reports
    "api.lessnoise.ch",
    "api.stadtlaerm.ch",
    "stats.json",
    "GitHub Pages",
    "swisstopo",
    "Stand: 14. Oktober 2026",
    "Art, Zeit und den ungefähren Ort", // what the phone keeps of a sent report
  ]) {
    expect(text, fact).toContain(fact);
  }
  await expect(page.locator('a[href="https://api.lessnoise.ch/v1/map/stats.json"]')).toHaveCount(1);
});

test("the location of a measurement is described as moved, not as «only a hectare»", async ({ page }) => {
  // server/DESIGN.md §2 rule 2 (location noise): the 100 m fields are a display grid, the protection
  // is the random shift in the app. No page may still promise «only the hectare».
  const forbidden = /genauer wird kein|nur (die|ihre|als) Hektare|behält nur ihre Hektare|pro Hektare|Jedes Feld ist eine Hektare/i;
  const moved = /standardmässig um etwa 50(\s|&nbsp;)m \(in der App (einstellbar|unter)/;
  for (const path of ["/", "/datenschutz.html"]) {
    await page.goto(path);
    const text = await allText(page);
    expect(text, path).not.toMatch(forbidden);
    expect(text, path).toMatch(moved);
  }
  // The guide describes the same setting (anleitung.html#teilen).
  await page.goto("/anleitung.html");
  const guide = await allText(page);
  expect(guide).not.toMatch(forbidden);
  expect(guide).toMatch(/verschiebt ihn sofort zufällig, standardmässig um etwa 50(\s|&nbsp;)m/);
  expect(guide).toContain("Standort verschieben");
  // The map page loads tiles and data (map.spec.js covers it with fixtures); here only its HTML.
  const html = await (await page.request.get("/karte.html")).text();
  expect(html).not.toMatch(forbidden);
  expect(html).toMatch(moved);
});
