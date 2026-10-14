// Private admitted input only: timed-a/av.json and independent expected-a/av.json.
// Audit browser ownership before and after execution; all owned contexts close in finally.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url);
const [atlas,work,out]=process.argv.slice(2).map(p=>path.resolve(p));
assert.ok(atlas && work && out, 'Usage: node actual_temporal_browser.mjs ATLAS_CHECKOUT PRIVATE_INPUT_DIRECTORY PRIVATE_OUTPUT_DIRECTORY');
const {chromium}=require(require.resolve('playwright',{paths:[path.join(atlas,'e2e/static')]}));
fs.mkdirSync(out,{recursive:true});
const report={conditions:[],errors:[],networkRequests:0,status:'running'};let browser;
try {
 browser=await chromium.launch({headless:true});report.browser=browser.version();
 const context=await browser.newContext({viewport:{width:1440,height:1100},acceptDownloads:true});
 const page=await context.newPage();page.setDefaultTimeout(180000);
 page.on('pageerror',e=>report.errors.push(String(e)));page.on('request',r=>{if(/^https?:/.test(r.url()))report.networkRequests++});
 const settle=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const download=async(name,file)=>{const pending=page.waitForEvent('download');await page.getByRole('button',{name,exact:true}).click();const d=await pending;const target=path.join(out,file);await d.saveAs(target);return fs.readFileSync(target,'utf8')};
 const open=async files=>{await page.locator('#workspace-open').setInputFiles(files);await page.waitForFunction(()=>document.querySelector('[data-open-status]')?.getAttribute('data-open-status')?.startsWith('Opened'),null,{timeout:180000});await settle()};
 for(const condition of ['a','av']){
  await page.goto(pathToFileURL(path.join(atlas,'target/edition/index.html')).href);
  const row={condition};let start=performance.now();await open(path.join(work,`timed-${condition}.json`));row.openMs=performance.now()-start;
  assert.equal(await page.locator('.temporal-grid tbody tr').count(),24);
  await page.getByRole('button',{name:'Reference',exact:true}).click();
  start=performance.now();await page.locator('#temporal-allocation').selectOption('intervals');await settle();row.allocateMs=performance.now()-start;
  const csv=await download('Export table',`${condition}-table.csv`);
  const cells=csv.trimEnd().split('\n').slice(1).map(l=>l.slice(1,-1).split('\",\"').map(v=>v.replaceAll('\"\"','\"')));
  const expected=JSON.parse(fs.readFileSync(path.join(work,`expected-${condition}.json`),'utf8'));
  assert.equal(cells.length,5536);assert.equal(expected.units,173);assert.equal(expected.words,2495);
  const lookup=new Map(expected.cells.map(r=>[r.unit+'|'+r.bin,r]));let numeric=0,absent=0,maxAbs=0;
  for(const [unit,measure,bin,allocated,lower,upper] of cells){
   const e=lookup.get(unit+'|'+bin);assert.ok(e);assert.equal(measure,'NormalizedScoreMass');
   for(const [actual,want] of [[allocated,e.allocated],[lower,e.lower],[upper,e.upper]]){
    if(want===null){assert.equal(actual,'');absent++;}else{assert.notEqual(actual,'');maxAbs=Math.max(maxAbs,Math.abs(Number(actual)-want));assert.equal(Number(actual),want);numeric++;}
   }
  }
  row.cells=cells.length;row.numericValues=numeric;row.unavailableValues=absent;row.maxAbsoluteDifference=maxAbs;
  let pages=1,seen=24;const next=page.getByRole('button',{name:'Next recalls',exact:true});
  while(await next.isEnabled()){await next.click();await settle();seen+=await page.locator('.temporal-grid tbody tr').count();pages++;}
  assert.equal(seen,173);row.pages=pages;
  await page.getByRole('button',{name:'Recall 169',exact:true}).click();await settle();
  const receipt=await download('Export view receipt',`${condition}-receipt.json`);
  const saved=await download('Save view',`${condition}-view.json`);
  await open([path.join(work,`timed-${condition}.json`),path.join(out,`${condition}-view.json`)]);
  assert.equal(await download('Save view',`${condition}-replay.json`),saved);
  assert.equal(await download('Export view receipt',`${condition}-receipt-replay.json`),receipt);
  row.exactReplay=true;report.conditions.push(row);fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n');
 }
 assert.equal(report.errors.length,0);assert.equal(report.networkRequests,0);report.status='passed';
}catch(e){report.status='failed';report.failure=String(e.stack||e);throw e;}
finally{if(browser)await browser.close();fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n')}
