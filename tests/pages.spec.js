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
  await expect(page.locator("#download .release")).toHaveText("Version 0.9.0, Android 8 oder neuer.");
  await expect(page.locator('#download a[href="anleitung.html"]')).toHaveText("Anleitung");
  await expect(page.locator('footer a[href="anleitung.html"]')).toHaveText("Anleitung");
  // «Source Code ist hier» links the repository (owner's text, 2026-10-15).
  await expect(page.locator('#download a[href="https://github.com/blauewelt/lessnoise"]')).toHaveText("hier");
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
  // The «Stadtlärm» 0.5.x note left the Anleitung (owner, 2026-10-15); update.html#new-app still explains it.
  await expect(page.locator("#stadtlaerm, #installation .note")).toHaveCount(0);
  await expect(page.locator("#installation")).toContainText("«Dateien»");
  await expect(page.locator("#pruefsummen .facts")).toContainText("0.9.0");
  await expect(page.locator("#pruefsummen .hash")).toHaveCount(3);   // two files + the signing certificate
  await expect(page.locator("#teilen")).toContainText("Laut! Melden");
  await expect(page.locator("#daten")).toContainText("CSV");
});

test("footnotes: each marker links to its note right below, and the note links back", async ({ page }) => {
  for (const [p, n] of [["/", 1], ["/anleitung.html", 2]]) {
    await page.goto(p);
    const markers = page.locator("sup.fn a");
    await expect(markers, p).toHaveCount(n);
    await expect(page.locator("p.footnote.small"), p).toHaveCount(n);
    for (let i = 1; i <= n; i++) {
      const m = page.locator(`sup.fn a#fnref${i}`);
      await expect(m, `${p} marker ${i}`).toHaveAttribute("href", `#fn${i}`);
      await expect(m).toHaveText(String(i));
      const note = page.locator(`p.footnote#fn${i}`);
      await expect(note, `${p} note ${i}`).toBeVisible();
      await expect(note.locator("a").first()).toHaveAttribute("href", `#fnref${i}`);
      // The note sits in the same section as its marker.
      expect(await note.evaluate((el) => el.closest("section").id)).toBe(await m.evaluate((el) => el.closest("section").id));
    }
  }
  await page.goto("/");
  await expect(page.locator("#fn1")).toContainText("ein altes Telefon");
  await page.goto("/anleitung.html");
  await expect(page.locator("#fn1")).toContainText("nie mit dem genauen Standort");
  await expect(page.locator('#fn2 a[href="https://github.com/blauewelt/lessnoise"]')).toHaveText("Quellcode der App");
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
  // The home page differs since 2026-10-20 (the e-mail form, below); the text pages keep the plain policy.
  const plain = await csp("/datenschutz.html");
  expect(await csp("/anleitung.html")).toBe(plain);
  expect(plain).toBe("default-src 'none'; style-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'");
  await expect(page.locator("script")).toHaveCount(0);
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  const hosts = await page.evaluate(() =>
    performance.getEntriesByType("resource").map((r) => new URL(r.name).host));
  for (const h of hosts) expect(h).toMatch(/^(localhost|127\.0\.0\.1)(:\d+)?$/);
});

// ---- 2026-10-20: the «Vorabversion» box and the e-mail form for update notices ----------------

const ALPHA = "Vorabversion. Die App ist noch in Erprobung: Messwerte können ungenau sein, Funktionen ändern sich, und mit einem Update können Daten verloren gehen. Rückmeldungen sind willkommen.";
const ISSUES = "https://github.com/blauewelt/lessnoise/issues";
const HOME_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src https://api.lessnoise.ch https://api.stadtlaerm.ch; base-uri 'none'; form-action 'none'";
const NEWSLETTER_API = "https://api.lessnoise.ch/v1/newsletter";
const NEWSLETTER_FALLBACK = "https://api.stadtlaerm.ch/v1/newsletter";
const ASK = "Möchten Sie erfahren, wenn es eine neue Version gibt? Hinterlassen Sie Ihre E-Mail-Adresse (freiwillig).";
const THANKS = "Danke, wir melden uns bei neuen Versionen.";

test("the «Vorabversion» box: under the hero on the home page, above «Welche Version?» in the Anleitung", async ({ page }) => {
  await page.goto("/");
  const home = page.locator("header.hero .alpha");
  await expect(home).toBeVisible();
  expect((await home.textContent()).replace(/\s+/g, " ").trim()).toBe(ALPHA);
  await expect(home.locator("strong")).toHaveText("Vorabversion.");
  await expect(home.locator("a")).toHaveText("Rückmeldungen");
  await expect(home.locator("a")).toHaveAttribute("href", ISSUES);
  expect(await page.locator('footer a[href="' + ISSUES + '"]').count()).toBe(1);   // the same link as the footer
  // Directly after the claim, before any section.
  expect(await home.evaluate((el) => el.previousElementSibling.className)).toBe("claim");
  // Amber frame: the warning colours come from style.css, not the plain text colours.
  const look = await home.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { border: cs.borderLeftColor, bg: cs.backgroundColor, width: parseFloat(cs.borderLeftWidth) };
  });
  expect(look.border).toBe("rgb(184, 110, 0)");   // --warn-border (= --amber) in light mode
  expect(look.bg).toBe("rgb(255, 244, 220)");     // --warn-bg
  expect(look.width).toBeGreaterThanOrEqual(4);

  await page.goto("/anleitung.html");
  const guide = page.locator("main > .alpha");
  await expect(guide).toBeVisible();
  expect((await guide.textContent()).replace(/\s+/g, " ").trim()).toBe(ALPHA);
  await expect(guide.locator("a")).toHaveAttribute("href", ISSUES);
  expect(await guide.evaluate((el) => el.nextElementSibling.id)).toBe("version");
  // Smaller than on the home page.
  const size = await guide.evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
  expect(size).toBeLessThan(17);
});

test("the «Vorabversion» box in dark mode uses the dark amber", async ({ browser, baseURL }) => {
  const ctx = await browser.newContext({ colorScheme: "dark", baseURL });
  const page = await ctx.newPage();
  await page.route((url) => !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(url.href), (r) => r.abort());
  await page.goto("/");
  const look = await page.locator(".alpha").evaluate((el) => {
    const cs = getComputedStyle(el);
    return { border: cs.borderLeftColor, bg: cs.backgroundColor, color: cs.color };
  });
  expect(look).toEqual({ border: "rgb(242, 179, 61)", bg: "rgb(44, 34, 16)", color: "rgb(251, 227, 176)" });
  await ctx.close();
});

test("home page CSP: scripts from the site, connections to the two API hosts only, nothing more", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute("content", HOME_CSP);
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  // One script, external and stamped; no inline script, no inline handler.
  const scripts = await page.locator("script").evaluateAll((ss) => ss.map((s) => ({ src: s.getAttribute("src"), text: s.textContent })));
  expect(scripts).toHaveLength(1);
  expect(scripts[0].src).toMatch(/^js\/newsletter\.js\?v=[0-9a-f]{8}$/);
  expect(scripts[0].text).toBe("");
  const html = fs.readFileSync(path.join(DOCS, "index.html"), "utf8");
  expect(html).not.toMatch(/\son[a-z]+=/i);
  // Loading the page sends nothing to the API.
  const hosts = await page.evaluate(() => performance.getEntriesByType("resource").map((r) => new URL(r.name).host));
  for (const h of hosts) expect(h).toMatch(/^(localhost|127\.0\.0\.1)(:\d+)?$/);
});

/** Answers POSTs to the newsletter API; returns the list of {url, body, headers} seen. */
async function newsletterRoute(page, answer) {
  const seen = [];
  await page.route(/^https:\/\/api\.(lessnoise|stadtlaerm)\.ch\/v1\/newsletter$/, async (route) => {
    const req = route.request();
    if (req.method() === "OPTIONS") {
      return route.fulfill({ status: 204, headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, DELETE", "Access-Control-Allow-Headers": "Content-Type" } });
    }
    seen.push({ url: req.url(), method: req.method(), body: req.postData(), headers: await req.allHeaders() });
    return answer(route, req);
  });
  return seen;
}

const cors = { "Access-Control-Allow-Origin": "*" };

test("e-mail form: above the download buttons, sends {email} once, then thanks", async ({ page }) => {
  const seen = await newsletterRoute(page, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", headers: cors, body: '{"ok":true}' }));
  await page.goto("/");
  const form = page.locator("#download form#newsletter");
  await expect(form).toBeVisible();
  await expect(form.locator(".newsletter-text")).toHaveText(ASK);
  await expect(form.locator('input[type="email"]')).toHaveAttribute("autocomplete", "email");
  await expect(form.getByRole("button", { name: "Eintragen" })).toBeVisible();
  // The form comes before the two download buttons.
  const before = await page.evaluate(() => {
    const f = document.getElementById("newsletter");
    const b = document.querySelector("#download .downloads");
    return !!(f.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
  });
  expect(before).toBe(true);

  await form.locator('input[type="email"]').fill("  Anna@Example.ch ");
  await form.getByRole("button", { name: "Eintragen" }).click();
  await expect(form.locator(".newsletter-text")).toHaveText(THANKS);
  await expect(form.locator(".newsletter-row")).toBeHidden();
  expect(seen).toHaveLength(1);
  expect(seen[0].url).toBe(NEWSLETTER_API);
  expect(seen[0].method).toBe("POST");
  expect(JSON.parse(seen[0].body)).toEqual({ email: "Anna@Example.ch" });   // the server lowercases
  expect(seen[0].headers["content-type"]).toBe("application/json");
  expect(seen[0].headers.referer).toBeUndefined();
  expect(seen[0].headers.cookie).toBeUndefined();
  expect(page.url()).toMatch(/\/$/);   // no navigation (preventDefault)
  await expect(page.locator("#download a.button")).toHaveCount(2);
});

test("e-mail form: server unreachable → tries the old host once, then the failure line; downloads stay usable", async ({ page }) => {
  const seen = await newsletterRoute(page, (route) => route.abort("internetdisconnected"));
  await page.goto("/");
  const form = page.locator("#newsletter");
  await form.locator('input[type="email"]').fill("anna@example.ch");
  await form.locator('input[type="email"]').press("Enter");
  const status = form.locator(".newsletter-status");
  await expect(status).toContainText("Eintragen hat nicht geklappt – schreiben Sie uns");
  await expect(status.locator("a")).toHaveAttribute("href", ISSUES);
  expect(seen.map((s) => s.url)).toEqual([NEWSLETTER_API, NEWSLETTER_FALLBACK]);
  await expect(form.locator(".newsletter-text")).toHaveText(ASK);       // not thanked
  await expect(form.locator('input[type="email"]')).toHaveValue("anna@example.ch");  // can try again
  await expect(form.getByRole("button", { name: "Eintragen" })).toBeEnabled();
  // The download buttons do not depend on the form.
  const buttons = page.locator("#download a.button");
  await expect(buttons).toHaveCount(2);
  for (const [i, href] of [[0, "download/lessnoise-karte.apk"], [1, "download/lessnoise.apk"]]) {
    await expect(buttons.nth(i)).toBeVisible();
    await expect(buttons.nth(i)).toHaveAttribute("href", href);
  }
  const res = await page.request.head("/download/lessnoise-karte.apk");
  expect(res.status()).toBe(200);
});

test("e-mail form: the server refuses (422) → failure line, no second host", async ({ page }) => {
  const seen = await newsletterRoute(page, (route) =>
    route.fulfill({ status: 422, contentType: "application/json", headers: cors, body: '{"detail":[{"field":"email","reason":"email is not a valid address"}]}' }));
  await page.goto("/");
  const form = page.locator("#newsletter");
  await form.locator('input[type="email"]').fill("anna@example.c");
  await form.getByRole("button", { name: "Eintragen" }).click();
  await expect(form.locator(".newsletter-status")).toContainText("Eintragen hat nicht geklappt");
  expect(seen).toHaveLength(1);
  await expect(page.locator("#download a.button").first()).toBeVisible();
});

test("e-mail form: an empty or obviously wrong address is not sent", async ({ page }) => {
  const seen = await newsletterRoute(page, (route) => route.fulfill({ status: 200, headers: cors, body: "{}" }));
  await page.goto("/");
  const form = page.locator("#newsletter");
  for (const value of ["", "anna", "anna @example.ch"]) {
    await form.locator('input[type="email"]').fill(value);
    await form.getByRole("button", { name: "Eintragen" }).click();
    await expect(form.locator(".newsletter-status")).toHaveText("Bitte eine gültige E-Mail-Adresse eingeben.");
  }
  expect(seen).toHaveLength(0);
});

test("without its script the form stays hidden; the downloads are there", async ({ browser, baseURL }) => {
  const ctx = await browser.newContext({ javaScriptEnabled: false, baseURL });
  const page = await ctx.newPage();
  await page.route((url) => !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(url.href), (r) => r.abort());
  await page.goto("/");
  await expect(page.locator("#newsletter")).toBeHidden();
  await expect(page.locator("#download a.button")).toHaveCount(2);
  await expect(page.locator("#download a.button").first()).toBeVisible();
  await expect(page.locator(".alpha")).toBeVisible();
  await ctx.close();
});

test("the privacy page explains the e-mail address for updates", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const sec = page.locator("#website");
  await expect(sec.locator("h3#email")).toHaveText("E-Mail-Adresse für Updates");
  const text = (await sec.textContent()).replace(/\s+/g, " ");
  for (const fact of [
    "freiwillig",
    "Gespeichert werden nur die Adresse und der Zeitpunkt",
    "Server des Projekts in der Schweiz",
    "keine IP-Adresse",
    "nur für Hinweise auf neue Versionen der App und auf wichtige Änderungen",
    "nie weitergegeben",
    "Löschen lassen können Sie die Adresse jederzeit",
  ]) expect(text, fact).toContain(fact);
  await expect(sec.locator('a[href="' + ISSUES + '"]')).toHaveCount(1);
  // The home page's form links here.
  await page.goto("/");
  await expect(page.locator('#newsletter a[href="datenschutz.html#email"]')).toHaveCount(1);
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
    "Stand: 25. Oktober 2026",
    "auf etwa eine Minute genau",      // the 30-second map: residual risk (DESIGN.md §2 rule 8)
    "Art, Zeit, den ungefähren Ort",   // what the phone keeps of a sent report
  ]) {
    expect(text, fact).toContain(fact);
  }
  await expect(page.locator('a[href="https://api.lessnoise.ch/v1/map/stats.json"]')).toHaveCount(1);
});

test("0.6.4: only machine and motor noise leaves the phone; «Unterwegs» shared nothing (gone in 0.9.0); «anderes Fahrzeug»", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of [
    "Ab Version 0.6.4 sendet die App nur laute Ereignisse von Maschinen- und Motorenlärm",
    "Stimmen, Musik, Sonstiges und Wind verlassen das Telefon nie",
    "mindestens 0,5", "6,5",
    "gestartete Messung nie geteilt",
  ]) expect(text, fact).toContain(fact);
  await page.goto("/anleitung.html");
  const guide = await allText(page);
  for (const fact of ["anderes Fahrzeug", "keine Messung «Unterwegs»", "Stimmen und Musik nie"]) expect(guide, fact).toContain(fact);
  expect(guide).not.toContain("zwei Knöpfe zum Starten");   // 0.9.0: no «Unterwegs» start any more
});

test("0.6.7: the motion sensor is read on the phone only, and «Unterwegs erkannt» is explained", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of ["den Bewegungssensor, nur auf dem Telefon", "davon verlässt nichts das Telefon"]) expect(text, fact).toContain(fact);
  await page.goto("/anleitung.html");
  const guide = await allText(page);
  for (const fact of ["Unterwegs erkannt", "etwa 3 Minuten ruhig"]) expect(guide, fact).toContain(fact);
  // 0.6.8–0.8.0: the measurement notification opened «Laut! Melden» while «unterwegs»; gone in 0.9.0.
  expect(guide).not.toContain("mit der Zeit des Antippens");
});

test("0.6.3: the one question at «Starten», the cadence and the tab «Karte» are described", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of [
    "Auf die Lärmkarte?", "Ja, auf die Karte", "Nur auf dem Telefon",   // the consent dialog
    "30 Sekunden nach ihrem Ende", "alle 2 Minuten", "mobile Daten",  // cadence, network
    "etwa alle 30 Sekunden neu berechnet", "auf etwa eine Minute genau", // DESIGN §2 rule 8, the live map
    "keine Messung «Unterwegs» und keine Mitteilung «Sehr laut»",   // 0.9.0: no measurement on the road
    "Die Karte in der App", "Tab «Karte»", "nach einem #", "Safe Browsing", // the map tab
  ]) expect(text, fact).toContain(fact);
  expect(text).not.toContain("nach der Erklärung «Auf die Karte senden»");   // the 0.6.2 gate is gone
  await expect(page.locator("#karte-in-der-app")).toHaveCount(1);
  await page.goto("/anleitung.html");
  const guide = await allText(page);
  for (const fact of ["Auf die Lärmkarte?", "Ja, auf die Karte", "30 Sekunden nach ihrem Ende", "alle 2 Minuten", "Karte in der App", "Unterwegs", "«Sehr laut»"]) expect(guide, fact).toContain(fact);
  expect(text).not.toContain("0,1");
  expect(guide).not.toContain("alle 10 Minuten");
  expect(guide).not.toContain("«Weiter»");
});

test("0.9.0: the privacy page describes the one report dialog, the moved place, the level, the photo, shortcut and widget", async ({ page }) => {
  await page.goto("/datenschutz.html");
  const text = await allText(page);
  for (const fact of [
    "sobald sich das Fenster öffnet",              // the one fix when the dialog opens, also if cancelled
    "auch wenn Sie danach «Abbrechen» tippen",
    "Verknüpfung und Widget senden selbst nichts",
    "feste", "15",                                  // the moved place, fixed accuracy
    "höchstens 2", "rund um den Ort, an den Sie sie schieben",
    "Aufgenommen wird nichts",                      // the level meter records nothing
    "die Spitze und der Mittelwert dieser Sekunden",
    "auf ganze dB gerundet",
    "keine Kamera-Berechtigung",                    // the photo
    "nur in ihrem privaten Speicher auf dem Telefon",
    "nie gesendet",
    "Personendaten",
    "In 0.8.0 konnten Sie ein Kontrollschild",      // old typed plates stay on the phone
    "Das ändert nur das Aussehen",                  // dark map tab
  ]) expect(text, fact).toContain(fact);
  await expect(page.locator("#foto")).toHaveCount(1);
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
