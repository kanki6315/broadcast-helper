const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true});
 try {
  const page = await browser.newPage({viewport:{width:1440,height:1000}});
  const champ = {id:1,title:'WeatherTech · GTP Teams',groupTitle:'IMSA WeatherTech',className:'GTP',kind:'TEAMS',isCup:false,year:2026,seasonId:1,seriesName:'IMSA',rowCount:3};
  const championships = ['GTP', 'LMP2', 'GTD PRO', 'GTD'].map((className, i) => ({...champ, id:i+1, className, title:`WeatherTech · ${className} Teams`}));
  const events = [{id:11,name:'Daytona',roundOrdinal:1},{id:22,name:'Sebring',roundOrdinal:2}];
  const recap = {championship:{...champ,family:'IMSA'},rounds:events.map((e,i)=>({round:i+1,eventId:e.id,venue:e.name,sessions:[],races:[],raceCount:1})),rows:[
   {competitorKey:'6',carNumber:'6',teamName:'Porsche Penske Motorsport',position:1,totalPoints:1000,pointsByRound:{1:1000},sessionPoints:{},cells:{}},
   {competitorKey:'7',carNumber:'7',teamName:'Porsche Penske Motorsport',position:2,totalPoints:980,pointsByRound:{1:980},sessionPoints:{},cells:{}},
   {competitorKey:'31',carNumber:'31',teamName:'Cadillac Whelen',position:3,totalPoints:970,pointsByRound:{1:970},sessionPoints:{},cells:{}}
  ]};
  const writes=[]; const errors=[];
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', route => {
   const request = route.request(), path = new URL(request.url()).pathname;
   if(!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(path);
   const body = path === '/api/me' ? {email:null,role:'VIEWER',authEnabled:false}
    : path === '/api/seasons/1' ? {id:1,year:2026,seriesId:1,seriesName:'IMSA',events,championships,entryClasses:championships.map(c=>c.className),kind:'MAIN'}
    : /^\/api\/championships\/[1-4]\/calculator$/.test(path) ? {...recap, championship:championships[Number(path.split('/')[3])-1]}
    : path.endsWith('/class-styles') ? {styles:[],unconfiguredClasses:[]} : [];
   return route.fulfill({json:body});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/seasons/1/calculator?class=GTP`);
  await page.getByLabel('Add team').selectOption('6');
  await page.getByLabel('Add team').selectOption('7');
  await page.getByLabel('Qualifying position for #6').selectOption('1');
  await page.getByLabel('Race position for #6').selectOption('5');
  await page.getByLabel('Qualifying position for #7').selectOption('2');
  await page.getByLabel('Race position for #7').selectOption('1');
  await page.getByLabel('Points adjustment for #6').fill('-10');
  assert.deepEqual(await page.locator('.calculator-total').allTextContents(), ['1362','1285']);
  await page.getByLabel('Race position for #6').selectOption('1');
  assert.equal(await page.getByLabel('Race position for #7').inputValue(), '5');
  await page.getByLabel('Race position for #6').selectOption('5');
  const gtp = page.getByRole('region', {name:'GTP calculator',exact:true});
  assert.equal(await page.locator('.calculator-panel').count(), 1);
  assert.ok(await page.getByRole('button',{name:'Show GTP',exact:true}).isDisabled());
  await page.getByRole('button',{name:'Show LMP2',exact:true}).click();
  assert.equal(await page.locator('.calculator-panel').count(), 2);
  const lmp = page.getByRole('region',{name:'LMP2 calculator',exact:true});
  await lmp.getByLabel('Add team').selectOption('6');
  await lmp.getByLabel('Race position for #6').selectOption('1');
  assert.deepEqual(await lmp.locator('.calculator-total').allTextContents(), ['1350']);
  await gtp.locator('.calculator-total').first().waitFor();
  assert.deepEqual(await gtp.locator('.calculator-total').allTextContents(), ['1362','1285']);
  await page.getByRole('button',{name:'Show GTP',exact:true}).click();
  assert.equal(await page.locator('.calculator-panel').count(), 1);
  await page.getByRole('button',{name:'Show GTP',exact:true}).click();
  await gtp.locator('.calculator-total').first().waitFor();
  assert.deepEqual(await gtp.locator('.calculator-total').allTextContents(), ['1362','1285']);
  await page.getByRole('button',{name:'Show GTD PRO',exact:true}).click();
  assert.equal(await page.locator('.calculator-panel').count(), 3);
  await page.getByRole('button',{name:'Show GTD',exact:true}).click();
  assert.equal(await page.locator('.calculator-panel').count(), 4);
  for (const name of ['GTD PRO','GTD']) {
    const panel = page.getByRole('region',{name:`${name} calculator`,exact:true});
    await panel.getByLabel('Add team').selectOption('31');
    await panel.getByLabel('Race position for #31').selectOption('3');
  }
  fs.mkdirSync('../.impeccable/review',{recursive:true});
  await page.evaluate(()=>document.documentElement.dataset.theme='dark');
  await page.screenshot({path:'../.impeccable/review/desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'../.impeccable/review/mobile.png',fullPage:true});
  assert.ok(await gtp.locator('.calculator-scroll').evaluate(el=>el.scrollWidth > el.clientWidth));
  for (const name of ['LMP2','GTD PRO','GTD']) await page.getByRole('button',{name:`Show ${name}`,exact:true}).click();
  await page.getByLabel('Event', {exact:true}).selectOption('11');
  await page.getByText('The imported standings already include this event', {exact:false}).waitFor();
  assert.equal(await page.locator('.calculator-table').count(), 0);
  await page.getByLabel('Event', {exact:true}).selectOption('22');
  assert.equal(await page.locator('.calculator-table').count(), 0);
  assert.deepEqual(writes,[]); assert.deepEqual(errors,[]);
  console.log('Calculator browser tests passed: 1–4 class panels, independent arithmetic, retained hidden drafts, swaps, shared event reset, baseline guard, viewer access and responsive scroll.');
 } finally { await browser.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
