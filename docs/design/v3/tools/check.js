const { chromium } = require('playwright'); const path = require('path');
(async () => { const b = await chromium.launch({ executablePath: process.env.CHROME });
for (const w of ['cover', 'inner']) for (const theme of ['light', 'dark']) for (const live of [0, 1]) {
  const p = await b.newPage({ viewport: { width: 1880, height: 1000 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('file://' + path.resolve(__dirname, '../handset.html') + `?w=${w}&theme=${theme}&live=${live}`); await p.waitForTimeout(400);
  const r = await p.evaluate(() => {
    const bad = [];
    const lum = c => { const m = c.match(/[\d.]+/g).map(Number); const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }; return .2126 * f(m[0]) + .7152 * f(m[1]) + .0722 * f(m[2]); };
    const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m); return (x + .05) / (y + .05); };
    document.querySelectorAll('.phone button, .phone a, .phone input').forEach(el => {
      const name = (el.getAttribute('aria-label') || el.innerText || el.placeholder || '').trim(); const lab = el.closest('label');
      if (!name && !(lab && lab.innerText.trim())) bad.push('noname ' + el.outerHTML.slice(0, 80));
      const r = el.getBoundingClientRect(); if (el.tagName === 'BUTTON' && r.width && r.height < 40 && !el.closest('.scrim')) bad.push('small ' + Math.round(r.height) + ' ' + el.className);
    });
    document.querySelectorAll('.phone').forEach(ph => { const pr = ph.getBoundingClientRect(); ph.querySelectorAll('*').forEach(e => { const r = e.getBoundingClientRect(); if (r.width && r.right > pr.right + 1 && !e.closest('.scroll')) bad.push('overflow ' + e.className); }); });
    const bg = getComputedStyle(document.querySelector('.phone')).backgroundColor;
    for (const sel of ['.summary', '.row .p', '.row .time', '.label', '.label .span', '.nav button:not([aria-current])', '.rail button:not([aria-current])', '.field input', '.cal small', '.meta']) {
      const el = document.querySelector('.phone ' + sel); if (!el) continue;
      const c = sel.includes('input') ? getComputedStyle(el, '::placeholder').color : getComputedStyle(el).color;
      const rt = ratio(c, bg); if (rt < 4.5) bad.push(`contrast ${sel} ${rt.toFixed(2)}`);
    }
    return [...new Set(bad)];
  });
  const n = await p.evaluate(() => { let c = 0; const phones = [...document.querySelectorAll('.phone')];
    for (let i = 0; i < 800; i++) { const ph = phones[i % phones.length]; const els = [...ph.querySelectorAll('[data-a]')].filter(e => e.dataset.a !== 'stop'); if (!els.length) continue; els[Math.floor(Math.random() * els.length)].click(); c++; } return c; });
  console.log(w, theme, live, r.slice(0, 20), 'fuzz', n, errs.slice(0, 3));
  await p.close(); }
await b.close(); })();
