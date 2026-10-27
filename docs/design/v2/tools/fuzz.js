const { chromium } = require('playwright'); const path=require('path');
(async()=>{const b=await chromium.launch({executablePath:process.env.CHROME});
for (const w of ['cover','inner']){const p=await b.newPage({viewport:{width:1800,height:1000}});
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.goto('file://'+path.resolve(__dirname,'../handset.html')+'?w='+w);await p.waitForTimeout(400);
const n=await p.evaluate(()=>{let c=0;const phones=[...document.querySelectorAll('.phone')];
 for(let i=0;i<600;i++){const ph=phones[i%phones.length];const els=[...ph.querySelectorAll('[data-a]')].filter(e=>e.dataset.a!=='stop');if(!els.length)continue;
 els[Math.floor(Math.random()*els.length)].click();c++;}
 return c;});
console.log(w,n,'clicks',errs.slice(0,5));await p.close();}
await b.close();})();
