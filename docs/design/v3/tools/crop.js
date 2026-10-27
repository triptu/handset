const { chromium } = require('playwright'); const path = require('path');
(async () => { const b = await chromium.launch({ executablePath: process.env.CHROME });
  const [w, theme, grp, idx, out, live] = process.argv.slice(2);
  const p = await b.newPage({ viewport: { width: 1880, height: 1000 }, deviceScaleFactor: 2 });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('file://' + path.resolve(__dirname, '../handset.html') + `?theme=${theme}&w=${w}&live=${live||0}`);
  await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(400);
  await p.locator(`#grp-${grp} .device`).nth(+idx).screenshot({ path: out });
  if (errs.length) console.log(errs); await b.close(); })();
