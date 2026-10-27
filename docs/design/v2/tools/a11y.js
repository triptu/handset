const { chromium } = require('playwright'); const path=require('path');
(async()=>{const b=await chromium.launch({executablePath:process.env.CHROME});
for (const w of ['cover','inner']){const p=await b.newPage({viewport:{width:1800,height:1000}});
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.goto('file://'+path.resolve(__dirname,'../handset.html')+'?w='+w);await p.waitForTimeout(500);
const r=await p.evaluate(()=>{const bad=[];document.querySelectorAll('.phone button,.phone a,.phone input').forEach(el=>{
 const name=(el.getAttribute('aria-label')||el.innerText||el.placeholder||'').trim(); const lab=el.closest('label');
 if(!name && !(lab&&lab.innerText.trim())) bad.push(el.outerHTML.slice(0,90));
 const r=el.getBoundingClientRect(); if(el.tagName!=='INPUT'&&el.tagName!=='A'&&r.width&&(r.height<32)) bad.push('small:'+Math.round(r.height)+' '+el.outerHTML.slice(0,70));});
 // overflow check: elements wider than their phone
 document.querySelectorAll('.phone').forEach(ph=>{const pr=ph.getBoundingClientRect();ph.querySelectorAll('*').forEach(e=>{const r=e.getBoundingClientRect();if(r.width&&r.right>pr.right+1&&!e.closest('.scroll'))bad.push('overflow '+e.className)})});
 return [...new Set(bad)].slice(0,30);});
console.log(w,errs,r);
// click-through: tap every data-a in first phone of each variation without errors
await p.close();}
await b.close();})();
