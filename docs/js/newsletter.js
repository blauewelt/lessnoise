// E-mail address for notices of new app versions (index.html#download; server: DESIGN.md §4.8).
// Optional and never a gate: the download buttons work whatever happens here. The form stays
// hidden without this script (the page's CSP has form-action 'none', so it could not be sent).
// One request, only when «Eintragen» is tapped: POST {"email"} to the project's server
// (api.lessnoise.ch; only if that host cannot be reached, api.stadtlaerm.ch, the same server).
// No cookies, no referrer, nothing else sent or kept in the browser.
"use strict";
(function () {
  const TIMEOUT_MS = 15000;
  const TEXT_OK = "Danke, wir melden uns bei neuen Versionen.";
  const TEXT_INVALID = "Bitte eine gültige E-Mail-Adresse eingeben.";

  function init() {
    const form = document.getElementById("newsletter");
    if (!form || !window.fetch) return;
    const input = form.querySelector('input[type="email"]');
    const button = form.querySelector('button[type="submit"]');
    const text = form.querySelector(".newsletter-text");
    const row = form.querySelector(".newsletter-row");
    const status = form.querySelector(".newsletter-status");
    const hosts = [form.dataset.api, form.dataset.apiFallback].filter(Boolean);
    form.hidden = false;

    function say(message, isError) {
      status.textContent = message;
      status.classList.toggle("is-error", !!isError);
    }

    function failed() {
      // «Eintragen hat nicht geklappt – schreiben Sie uns an …»: the Verein's contact, from
      // data-contact (a mailto: address, or the GitHub issues page while there is none).
      const contact = form.dataset.contact || "";
      status.textContent = "";
      status.classList.add("is-error");
      const link = document.createElement("a");
      link.href = contact;
      if (contact.startsWith("mailto:")) {
        link.textContent = contact.slice(7);
        status.append("Eintragen hat nicht geklappt – schreiben Sie uns an ", link, ".");
      } else {
        link.textContent = "GitHub";
        status.append(
          "Eintragen hat nicht geklappt – schreiben Sie uns über ", link,
          " (dort öffentlich sichtbar) oder versuchen Sie es später noch einmal.");
      }
    }

    async function send(url, email) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
      try {
        return await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email }),
          mode: "cors",
          credentials: "omit",
          cache: "no-store",
          referrerPolicy: "no-referrer",
          signal: ctrl.signal,
        });
      } finally {
        clearTimeout(timer);
      }
    }

    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const email = input.value.trim();
      if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        say(TEXT_INVALID, true);
        input.focus();
        return;
      }
      button.disabled = true;
      say("", false);
      let res = null;
      for (const url of hosts) {
        try {
          res = await send(url, email);
          break;              // the server answered (whatever it said): no second host
        } catch (_) {
          res = null;         // unreachable or timed out: try the other host once
        }
      }
      button.disabled = false;
      if (res && res.ok) {
        text.textContent = TEXT_OK;
        row.hidden = true;
        say("", false);
        input.value = "";
      } else {
        failed();
      }
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
