const { chromium } = require('playwright');
const path = require('path');
const out = path.resolve(__dirname, '../shots');
(async () => {
  const b = await chromium.launch({ executablePath: process.env.CHROME });
  const only = process.argv[2];
  for (const w of ['cover', 'inner']) for (const theme of ['light', 'dark']) {
    if (only && only !== `${w}-${theme}`) continue;
    const p = await b.newPage({ viewport: { width: w === 'cover' ? 1880 : 1840, height: 1000 }, deviceScaleFactor: 1 });
    const errs = []; p.on('pageerror', e => errs.push(e.message)); p.on('console', m => m.type() === 'error' && errs.push(m.text()));
    await p.goto('file://' + path.resolve(__dirname, '../handset.html') + `?theme=${theme}&w=${w}`);
    await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(400);
    for (const v of ['A', 'B', 'C']) {
      await p.locator('#var-' + v).screenshot({ path: `${out}/handset-${v}-${w}-${theme}.png` });
    }
    if (errs.length) console.log(w, theme, errs);
    await p.close();
  }
  await b.close();
})();
