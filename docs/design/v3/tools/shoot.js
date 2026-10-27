const { chromium } = require('playwright');
const path = require('path');
const out = path.resolve(__dirname, '../shots');
const file = 'file://' + path.resolve(__dirname, '../handset.html');
(async () => {
  const b = await chromium.launch({ executablePath: process.env.CHROME });
  const jobs = [];
  for (const w of ['cover', 'inner']) for (const theme of ['light', 'dark']) jobs.push({ w, theme, live: 0 });
  jobs.push({ w: 'cover', theme: 'light', live: 1, only: 'days', name: 'acting-cover-light' });
  jobs.push({ w: 'inner', theme: 'dark', live: 1, only: 'days', name: 'acting-inner-dark' });
  for (const j of jobs) {
    if (process.argv[2] && !`${j.w}-${j.theme}${j.live ? '-live' : ''}`.includes(process.argv[2])) continue;
    const p = await b.newPage({ viewport: { width: j.w === 'cover' ? 1880 : 1840, height: 1000 }, deviceScaleFactor: 1 });
    const errs = []; p.on('pageerror', e => errs.push(e.message)); p.on('console', m => m.type() === 'error' && errs.push(m.text()));
    await p.goto(file + `?theme=${j.theme}&w=${j.w}&live=${j.live}`);
    await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(500);
    for (const g of ['days', 'chat', 'agents']) {
      if (j.only && j.only !== g) continue;
      await p.locator('#grp-' + g).screenshot({ path: `${out}/${j.name || `${g}-${j.w}-${j.theme}`}.png` });
    }
    if (errs.length) console.log(j, errs);
    await p.close();
  }
  await b.close();
})();
