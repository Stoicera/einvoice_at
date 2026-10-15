#!/usr/bin/env node
// Renders the GitHub social preview (1280x640 PNG) for this repository.
//
//   node scripts/social-preview.mjs [output.png]     (default: docs/assets/social-preview.png)
//
// Needs playwright-core and a Chromium for it. The repository has no Node build, so neither is a
// dependency; the script resolves playwright-core from, in this order: the repository root, the
// directory in PLAYWRIGHT_CORE_PATH (a folder whose node_modules contains playwright-core).
// One-off setup:
//   npm install --no-save playwright-core && npx playwright-core install chromium-headless-shell
// Font: Inter if installed locally, otherwise the system sans-serif. The text below is the whole
// content of the image; it makes no claim beyond what the README states.
//
// GitHub has no API for the social preview: upload the PNG by hand under
// Settings -> General -> Social preview (see docs/PORTFOLIO-READINESS.md).

import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = resolve(repoRoot, process.argv[2] ?? "docs/assets/social-preview.png");

function loadPlaywright() {
  const require = createRequire(import.meta.url);
  const searchPaths = [repoRoot, process.env.PLAYWRIGHT_CORE_PATH].filter(Boolean);
  try {
    return require(require.resolve("playwright-core", { paths: searchPaths }));
  } catch {
    console.error(
      "playwright-core not found. Run `npm install --no-save playwright-core` in the repository " +
        "root, or set PLAYWRIGHT_CORE_PATH to a folder that has it in node_modules.",
    );
    process.exit(1);
  }
}

const NAVY = "#0B1F3A";
const COBALT = "#2558E8";

const html = `<!doctype html>
<html lang="de"><head><meta charset="utf-8"><style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 1280px; height: 640px; }
  body {
    background: ${NAVY};
    color: #F4F6FA;
    font-family: "Inter", system-ui, -apple-system, "Segoe UI", sans-serif;
    display: flex; flex-direction: column; justify-content: center;
    padding: 0 104px;
    position: relative;
  }
  .rule { width: 96px; height: 8px; background: ${COBALT}; border-radius: 4px; margin-bottom: 44px; }
  .mark { font-size: 112px; font-weight: 700; letter-spacing: -3px; line-height: 1; }
  .mark span { color: #8FB0FF; }
  .line { font-size: 38px; font-weight: 500; line-height: 1.32; margin-top: 40px; max-width: 1040px; color: #E3E8F2; }
  .meta { font-size: 28px; font-weight: 400; margin-top: 48px; color: #9FB0CC; letter-spacing: 0.2px; }
  .foot { position: absolute; left: 104px; bottom: 52px; font-size: 22px; color: #6F82A3; }
</style></head><body>
  <div class="rule"></div>
  <div class="mark">einvoice<span>-at</span></div>
  <div class="line">E-Rechnungen für Österreich prüfen und erzeugen — ebInterface&nbsp;6.1, Peppol&nbsp;BIS&nbsp;3.0</div>
  <div class="meta">Apache-2.0 · Java 25 · Spring Boot 4</div>
  <div class="foot">github.com/Stoicera/einvoice_at</div>
</body></html>`;

const { chromium } = loadPlaywright();
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 640 }, deviceScaleFactor: 1 });
  await page.setContent(html, { waitUntil: "load" });
  await page.evaluate(() => document.fonts.ready);
  mkdirSync(dirname(out), { recursive: true });
  await page.screenshot({ path: out, type: "png" });
  console.log(`wrote ${out}`);
} finally {
  await browser.close();
}
