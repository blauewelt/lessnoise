// Browser tests of the text pages (index.html, datenschutz.html, update.html) for app 0.6.0:
// the name is «Weniger Lärm», nothing speaks of nights any more, «Stadtlärm» appears only where
// a sentence is about the old app, links go to blauewelt/lessnoise, Swiss spelling, and the
// anchors that other pages link to still exist. No request leaves localhost.
"use strict";
const { test, expect } = require("@playwright/test");

const PAGES = ["/", "/datenschutz.html", "/update.html"];
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

test("the home page keeps the anchors other pages link to", async ({ page }) => {
  await page.goto("/");
  // karte.html: ./#app, ./#version, ./#verein; update.html: ./#version, ./#download.
  for (const id of ["app", "melden", "download", "version", "installation", "kalibrieren", "karte", "datenschutz", "verein"]) {
    await expect(page.locator("#" + id), "#" + id).toHaveCount(1);
  }
  // No screenshot of the old «Nächte» screen.
  await expect(page.locator('img[src*="nacht"], source[srcset*="nacht"]')).toHaveCount(0);
});

test("the home page: the Stadtlärm 0.5.x note and the two editions", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#download .note")).toContainText("deinstallieren");
  await expect(page.locator("#download .note")).toContainText("CSV");
  await expect(page.locator("#version")).toContainText("«Weniger Lärm»");
  await expect(page.locator("#version")).toContainText("«Weniger Lärm · Karte»");
  await expect(page.locator(".facts")).toContainText("0.6.0");
});

test("the privacy page names every data flow of 0.6.0", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of [
    "Weniger Lärm · Karte",            // the edition with internet and location
    "Laut! Melden",                    // reports
    "auf die volle Stunde abgerundeten Zeit",
    "Hektare",                         // the cell at Start
    "nie im Hintergrund",
    "60 Tage",
    "auch die Meldungen",              // deletion includes reports
    "api.lessnoise.ch",
    "api.stadtlaerm.ch",
    "stats.json",
    "GitHub Pages",
    "swisstopo",
    "Stand: 12. Oktober 2026",
  ]) {
    expect(text, fact).toContain(fact);
  }
  await expect(page.locator('a[href="https://api.lessnoise.ch/v1/map/stats.json"]')).toHaveCount(1);
});
