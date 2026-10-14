// Browser tests of the update check (docs/update.html) and the two downloads on the home page.
// The app opens update.html#v=<versionName>&c=<versionCode>&e=<edition> (since 0.5.0; older
// apps send no e). The page must offer the download of the same edition, «Weniger Lärm» (the
// edition without internet) when e is missing or unknown, and a link to the other edition.
// Installs before 0.6.0 are «Stadtlärm» (another app id): they must not be told that the update
// installs over them. No request leaves localhost.
"use strict";
const fs = require("fs");
const path = require("path");
const { test, expect } = require("@playwright/test");

const RELEASES = "download/";
const SCREENSHOTS_PRESENT = ["app-messung.png", "app-messung-dark.png"]
  .every((f) => fs.existsSync(path.join(__dirname, "..", "docs", "img", f)));
let foreign;
let consoleErrors;

test.beforeEach(async ({ page }) => {
  foreign = [];
  consoleErrors = [];
  await page.route((url) => !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(url.href), (route) => {
    foreign.push(route.request().url());
    return route.abort();
  });
  page.on("console", (m) => {
    if (m.type() !== "error") return;
    // The home page's screenshot (img/app-messung*.png) comes from a parallel branch; until it is
    // in docs/img/ its 404 is expected (tests/pages.spec.js checks it once it is there).
    if (/\/img\/app-messung(-dark)?\.png$/.test(m.location().url || "") && !SCREENSHOTS_PRESENT) return;
    consoleErrors.push(m.text());
  });
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
});

test.afterEach(async () => {
  expect(foreign, "requests to other hosts").toEqual([]);
  // A CSP violation (stale script hash) would show up here and leave the status unchanged.
  expect(consoleErrors, "console errors").toEqual([]);
});

/** The release tag and the version the page announces, read from its own attributes. */
async function pageData(page) {
  const main = page.locator("#update");
  return {
    version: await main.getAttribute("data-version"),
    code: parseInt(await main.getAttribute("data-code"), 10),
    offline: await main.getAttribute("data-offline"),
    karte: await main.getAttribute("data-karte"),
    firstCode: parseInt(await main.getAttribute("data-first-code"), 10),
  };
}

test("without a hash: «Weniger Lärm», and a link to «Weniger Lärm · Karte»", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  expect(d.offline).toMatch(new RegExp("^" + RELEASES + "lessnoise\\.apk$"));
  expect(d.karte).toBe(d.offline.replace(/lessnoise\.apk$/, "lessnoise-karte.apk"));
  await expect(page.locator("#status")).toHaveText("Aktuelle Version: " + d.version);
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  await expect(page.locator("#download-link")).toHaveText("Weniger Lärm herunterladen");
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
  await expect(page.locator("#other-link")).toHaveText("Weniger Lärm · Karte herunterladen");
  await expect(page.locator("#new-app")).toBeVisible();
  await expect(page.locator("h1, .home")).toHaveText(["Weniger Lärm", "Update prüfen"]);
});

test("the current version is 0.6.1 (versionCode 14); 13 (0.6.0) was the first of the new app id", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  expect(d.version).toBe("0.6.1");
  expect(d.code).toBe(14);
  expect(d.firstCode).toBe(13);
});

test("a Stadtlärm 0.5.1 is told to uninstall first, not that the update installs over it", async ({ page }) => {
  await page.goto("/update.html#v=0.5.1&c=12&e=karte");
  const d = await pageData(page);
  await expect(page.locator("#status")).toHaveText(
    `Update verfügbar: Version ${d.version} (installiert: 0.5.1, Weniger Lärm · Karte)`);
  await expect(page.locator("#hints")).toBeVisible();
  await expect(page.locator("#hint-over")).toBeHidden();
  await expect(page.locator("#new-app")).toBeVisible();
  await expect(page.locator("#new-app")).toContainText("deinstallieren");
  // Only v (no c): the version decides.
  await page.evaluate(() => { location.hash = "v=0.5.0"; });
  await expect(page.locator("#hint-over")).toBeHidden();
});

test("an app of the new id that is older than the published one installs over it", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  test.skip(d.code <= d.firstCode, "no published version after the first of the new app id yet");
  await page.goto(`/update.html#v=0.6.0&c=${d.firstCode}&e=offline`);
  await expect(page.locator("#hints")).toBeVisible();
  await expect(page.locator("#hint-over")).toBeVisible();
});

test("an app up to 0.4.0 (no e) is offered «Weniger Lärm» as its update", async ({ page }) => {
  await page.goto("/update.html#v=0.4.0&c=10");
  const d = await pageData(page);
  await expect(page.locator("#status")).toHaveText(`Update verfügbar: Version ${d.version} (installiert: 0.4.0)`);
  await expect(page.locator("#download")).toBeVisible();
  await expect(page.locator("#hints")).toBeVisible();
  await expect(page.locator("#hint-over")).toBeHidden();
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
});

test("an older Karten-Version is offered «Weniger Lärm · Karte», with a way back", async ({ page }) => {
  await page.goto("/update.html#v=0.4.9&c=10&e=karte");
  const d = await pageData(page);
  await expect(page.locator("#status")).toHaveText(
    `Update verfügbar: Version ${d.version} (installiert: 0.4.9, Weniger Lärm · Karte)`);
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.karte);
  await expect(page.locator("#download-link")).toHaveText("Weniger Lärm · Karte herunterladen");
  await expect(page.locator("#other-text")).toHaveText("Zu «Weniger Lärm» wechseln (ohne Internet- und Standort-Berechtigung):");
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.offline);
});

test("an up-to-date edition: no download button, the switch link stays", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  await page.goto(`/update.html#v=${d.version}&c=${d.code}&e=offline`);
  await expect(page.locator("#status")).toHaveText(`Ihre Version ${d.version} (Weniger Lärm) ist aktuell.`);
  await expect(page.locator("#download")).toBeHidden();
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
  // hashchange re-renders
  await page.evaluate((h) => { location.hash = h; }, `v=${d.version}&c=${d.code}&e=karte`);
  await expect(page.locator("#status")).toHaveText(`Ihre Version ${d.version} (Weniger Lärm · Karte) ist aktuell.`);
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.offline);
});

for (const e of ["labor", "KARTE", "karte2", "<b>x</b>", ""]) {
  test(`an unknown edition (${JSON.stringify(e)}) is treated like none`, async ({ page }) => {
    await page.goto("/update.html#v=0.4.0&c=10&e=" + encodeURIComponent(e));
    const d = await pageData(page);
    await expect(page.locator("#status")).toHaveText(`Update verfügbar: Version ${d.version} (installiert: 0.4.0)`);
    await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  });
}

test("the home page links the same two release files as update.html", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  await page.goto("/");
  const hrefs = await page.locator(`a[href^="${RELEASES}"]`).evaluateAll((as) => as.map((a) => a.getAttribute("href")));
  expect(new Set(hrefs)).toEqual(new Set([d.offline, d.karte]));
  // The primary button is «Weniger Lärm», the secondary «Weniger Lärm · Karte».
  await expect(page.locator("#download a.button").first()).toHaveAttribute("href", d.offline);
  await expect(page.locator("#download a.button").first()).toHaveText("Weniger Lärm herunterladen");
  await expect(page.locator("#download a.button-secondary")).toHaveAttribute("href", d.karte);
  await expect(page.locator("#download a.button-secondary")).toHaveText("Weniger Lärm · Karte herunterladen");
  // Both version strings agree; the home page shows only the version line, the facts are on anleitung.html.
  await expect(page.locator("#download .release")).toHaveText(`Version ${d.version}, Android 8 oder neuer.`);
  await expect(page.locator(".facts")).toHaveCount(0);
  await page.goto("/anleitung.html");
  const hrefs2 = await page.locator(`a[href^="${RELEASES}"]`).evaluateAll((as) => as.map((a) => a.getAttribute("href")));
  expect(new Set(hrefs2)).toEqual(new Set([d.offline, d.karte]));
  await expect(page.locator("#pruefsummen .facts")).toContainText(d.version);
  await expect(page.locator("#pruefsummen .facts")).toContainText("lessnoise.apk");
  await expect(page.locator("#pruefsummen .facts")).toContainText("lessnoise-karte.apk");
});
