// Unit test of compareVersions in docs/update.html (inline script; extracted from the page's source
// so that the test checks exactly what is published). 0.9.4.1: the owner's rule gives a Labor-only fix
// a fourth version component; the page must still compare it correctly.
// Run: node --test tests/unit/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "../../docs/update.html"), "utf8");
const m = html.match(/function compareVersions\(a, b\) \{[\s\S]*?\n  \}\n/);
assert.ok(m, "compareVersions found in docs/update.html");
const compareVersions = new Function(m[0] + "\nreturn compareVersions;")();

test("update.html compareVersions: four-part versions (0.9.4.1)", () => {
  assert.equal(compareVersions("0.9.4.1", "0.9.0"), 1);
  assert.equal(compareVersions("0.9.0", "0.9.4.1"), -1);
  assert.equal(compareVersions("0.9.4.1", "0.9.4"), 1);
  assert.equal(compareVersions("0.9.4", "0.9.4.1"), -1);
  assert.equal(compareVersions("0.9.4.1", "0.9.4.1"), 0);
  assert.equal(compareVersions("0.9.4.1", "0.9.5"), -1);
  assert.equal(compareVersions("0.9.4.1", "0.10.0"), -1);
  assert.equal(compareVersions("0.9.4.0", "0.9.4"), 0);
  // The suffixes of the Labor builds do not count as a number.
  assert.equal(compareVersions("0.9.4.1-labor-karte", "0.9.0"), 1);
  assert.equal(compareVersions("0.9.4.1-labor", "0.9.4.1"), 0);
  // As before: three parts, the «Stadtlärm» boundary.
  assert.equal(compareVersions("0.5.9", "0.6.0"), -1);
  assert.equal(compareVersions("0.9.0", "0.9.0"), 0);
});
