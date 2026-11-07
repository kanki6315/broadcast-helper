const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function fixture() {
  const seasons = [2024, 2025, 2026].map((year, i) => {
    const id = i + 1;
    const row = { competitorKey: `source-${id}`, competitorName: 'Test Driver', position: id, totalPoints: 25, cells: {1:[{race:1,name:'Race',carNumber:String(id),start:2,finish:1,status:'Classified',notFinished:false}]}, pointsByRound:{1:25},sessionPoints:{} };
    return {id,year,driverIds:{[row.competitorKey]:7},recap:{championship:{id,seasonId:id,year,seriesName:'FIA F4 Esports Global Championship',kind:'DRIVERS',family:'Main',isCup:false,isOverall:false,className:'F4'},rounds:[{round:1,venue:'FUJI',eventId:id,raceCount:1,sessions:[],races:[{ordinal:1,name:'Race'}]}],rows:[row]},stats:{formats:[],rows:[{driverId:7,driverName:'Test Driver',className:'F4',byFormat:[{formatId:null,starts:1,wins:1,podiums:1,top5s:1,dnfs:0}],quali:{sessions:0,poles:0,top5s:0}}]}};
  });
  return {seriesId:10,seriesName:'FIA F4 Esports Global Championship',seasons};
}
(async () => {
  const data = process.env.F4_HISTORY_FIXTURE ? JSON.parse(fs.readFileSync(process.env.F4_HISTORY_FIXTURE,'utf8')) : fixture();
  const { createServer } = await import('vite');
  const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
  await server.listen();
  let browser;
  try {
    browser = await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH});
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors=[], writes=[];
    let fail = false;
    page.on('pageerror', e => errors.push(String(e)));
    const seasonIndex = data.seasons.slice().reverse().map(s=>({id:s.id,year:s.year,kind:'MAIN',seriesName:data.seriesName,roundCount:s.recap?.rounds.length??0,championshipCount:1}));
    await page.route('**/api/**', route => {
      const req=route.request(), p=new URL(req.url()).pathname;
      if(!['GET','HEAD','OPTIONS'].includes(req.method())) writes.push(`${req.method()} ${p}`);
      if(p===`/api/series/${data.seriesId}/recap`) return route.fulfill({status:fail?500:200,json:fail?{message:'Unavailable'}:data});
      const season = data.seasons.find(s => p===`/api/seasons/${s.id}`);
      const recap = data.seasons.find(s => p===`/api/championships/${s.recap?.championship.id}/recap`)?.recap;
      const body = season ? {id:season.id,year:season.year,kind:'MAIN',seriesId:data.seriesId,seriesName:data.seriesName,entryClasses:[],events:[],championships:season.recap?[{...season.recap.championship,title:'FIA F4',groupTitle:'Main'}]:[]}
        : recap ? recap
        : p==='/api/me' ? {email:null,role:'VIEWER',authEnabled:false}
        : p==='/api/seasons' ? seasonIndex
        : p==='/api/series' ? [{id:data.seriesId,name:data.seriesName}]
        : p.endsWith('/class-styles') ? {styles:[],unconfiguredClasses:[]}
        : [];
      return route.fulfill({json:body});
    });
    const base=`http://127.0.0.1:${server.httpServer.address().port}`;
    await page.goto(`${base}/#/`);
    await page.getByRole('link',{name:'Multi-season recap',exact:true}).click();
    await page.locator('.ms-results tbody tr').first().waitFor();
    assert.equal(await page.getByRole('button',{name:'Summary',exact:true}).count(),0);
    assert.equal(await page.locator('.ms-results .ms-year').count(), data.seasons.length);
    const unique = new Set(data.seasons.flatMap(s=>s.stats.rows.map(r=>r.driverId))).size;
    assert.equal(await page.locator('.ms-results tbody tr').count(),unique);
    const out=process.env.RECAP_SCREENSHOTS;
    if(out){fs.mkdirSync(out,{recursive:true});await page.getByRole('group',{name:'Theme',exact:true}).getByRole('button',{name:'Dark',exact:true}).click();await page.waitForTimeout(150);await page.screenshot({path:path.join(out,'results-dark.png')});}
    const chosen=data.seasons.at(-1).stats.rows[0];
    await page.getByLabel('Find a driver',{exact:true}).fill(chosen.driverName);
    assert.equal(await page.locator('.ms-results tbody tr').count(),1);
    assert.equal(await page.getByLabel('Find a driver',{exact:true}).inputValue(),chosen.driverName);
    const total=data.seasons.flatMap(s=>s.stats.rows).filter(r=>r.driverId===chosen.driverId).flatMap(r=>r.byFormat).reduce((n,f)=>n+f.starts,0);
    assert.equal(await page.locator('[data-stat=starts]').first().textContent(),String(total));
    const last=data.seasons.at(-1);
    for(const s of data.seasons.slice(0,-1)) {
      const button = page.getByRole('group',{name:'Included seasons'}).getByRole('button',{name:String(s.year),exact:true});
      await button.click();
      await button.and(page.locator('[aria-pressed=false]')).waitFor();
    }
    assert.ok(await page.getByRole('group',{name:'Included seasons'}).getByRole('button',{name:String(last.year),exact:true}).isDisabled());
    await page.reload();
    await page.locator('.ms-results').waitFor();
    assert.equal(await page.getByLabel('Find a driver',{exact:true}).inputValue(),chosen.driverName);
    assert.equal(await page.locator('.ms-results .ms-year').count(),1);
    assert.equal(await page.locator('[data-stat=starts]').first().textContent(),String(chosen.byFormat.reduce((n,f)=>n+f.starts,0)));
    assert.equal(await page.locator('[data-stat=wins]').first().textContent(),String(chosen.byFormat.reduce((n,f)=>n+f.wins,0)));
    assert.equal(await page.locator('[data-stat=poles]').first().textContent(),chosen.quali.sessions ? String(chosen.quali.poles) : '—');
    if(out){await page.getByLabel('Find a driver',{exact:true}).fill('');await page.waitForTimeout(150);await page.screenshot({path:path.join(out,'summary-dark.png')});await page.getByRole('group',{name:'Theme',exact:true}).getByRole('button',{name:'Light',exact:true}).click();await page.waitForTimeout(150);await page.screenshot({path:path.join(out,'summary-light.png')});await page.setViewportSize({width:390,height:844});await page.waitForTimeout(150);await page.screenshot({path:path.join(out,'summary-mobile.png')});}
    await page.getByLabel('Find a driver',{exact:true}).fill('No such driver');
    await page.getByText('No drivers match these filters.',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Clear driver filters'}).click();
    await page.locator('.ms-results tbody tr').first().waitFor();
    assert.ok(await page.locator('.ms-results tbody tr').count()>0);
    for (const s of data.seasons.slice(0,-1)) {
      const button = page.getByRole('group',{name:'Included seasons'}).getByRole('button',{name:String(s.year),exact:true});
      await button.click();
      await button.and(page.locator('[aria-pressed=true]')).waitFor();
    }
    await page.getByLabel(`${last.year} drivers only`,{exact:true}).click();
    await page.getByLabel(`${last.year} drivers only`,{exact:true}).and(page.locator(':checked')).waitFor();
    assert.ok(page.url().includes('field=current'));
    assert.equal(await page.locator('.ms-results tbody tr').count(), new Set(last.stats.rows.map(r=>r.driverId)).size);
    await page.getByLabel('Sort drivers',{exact:true}).selectOption('starts');
    await page.waitForFunction(() => location.hash.includes('sort=starts'));
    await page.waitForFunction(() => {
      const counts=[...document.querySelectorAll('.ms-results tbody tr')].map(r=>Number(r.querySelector('[data-stat=starts]').textContent));
      return counts.every((n,i)=>i===0||counts[i-1]>=n);
    });
    fail=true;
    await page.reload();
    await page.getByRole('alert').waitFor();
    fail=false;
    await page.getByRole('button',{name:'Retry',exact:true}).click();
    await page.locator('.ms-results').waitFor();
    await page.goto(`${base}/#/seasons/${last.id}?q=${encodeURIComponent(chosen.driverName)}`);
    await page.getByRole('link',{name:'Across seasons',exact:true}).click();
    await page.locator('.ms-results tbody tr').first().waitFor();
    assert.equal(await page.getByLabel('Find a driver',{exact:true}).inputValue(), chosen.driverName);
    await page.goto(`${base}/#/series/${data.seriesId}/recap?view=summary&q=${encodeURIComponent(chosen.driverName)}`);
    await page.locator('.ms-results tbody tr').first().waitFor();
    assert.equal(await page.locator('[data-stat=starts]').first().textContent(),String(total));
    assert.deepEqual(errors,[]);
    assert.deepEqual(writes,[]);
    console.log('Multi-season recap: navigation, totals, filters, persistence, empty state and retry passed');
  } finally { await browser?.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exitCode=1});
