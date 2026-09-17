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
    : path === '/api/seasons/1' ? {id:1,year:2026,seriesId:1,seriesName:'IMSA',events,championships:[champ],entryClasses:['GTP'],kind:'MAIN'}
    : path === '/api/championships/1/calculator' ? recap
    : path.endsWith('/class-styles') ? {styles:[],unconfiguredClasses:[]} : [];
   return route.fulfill({json:body});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/seasons/1/calculator`);
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
  fs.mkdirSync('../.impeccable/review',{recursive:true});
  await page.evaluate(()=>document.documentElement.dataset.theme='dark');
  await page.screenshot({path:'../.impeccable/review/desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'../.impeccable/review/mobile.png',fullPage:true});
  assert.ok(await page.locator('.calculator-scroll').evaluate(el=>el.scrollWidth > el.clientWidth));
  await page.getByLabel('Event', {exact:true}).selectOption('11');
  await page.getByText('The imported standings already include this event', {exact:false}).waitFor();
  assert.equal(await page.locator('.calculator-table').count(), 0);
  await page.getByLabel('Event', {exact:true}).selectOption('22');
  assert.equal(await page.locator('.calculator-table').count(), 0);
  assert.deepEqual(writes,[]); assert.deepEqual(errors,[]);
  console.log('Calculator browser tests passed: arithmetic, swaps, baseline guard, reset, viewer access, no writes, responsive scroll.');
 } finally { await browser.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
