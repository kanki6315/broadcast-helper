const { chromium } = require('playwright');
const assert = require('node:assert/strict');
// The live timing page against a stubbed API: the tower by class, a car's laps
// and stints, drive time against the rules, the admin rules editor, and what
// the page says when the feed is off or following another event. It never
// offers to connect: that stays on the iPad.
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true});
 try {
  const page = await browser.newPage({viewport:{width:1280,height:900}});
  const now = Date.now();
  const car = (position, carNumber, extra={}) => ({position,carNumber,entryId:100+position,teamName:`Team ${carNumber}`,vehicle:null,manufacturer:null,
   status:'CLASSIFIED',laps:50,gapToLeaderMs:null,gapToLeaderLaps:null,intervalMs:null,intervalLaps:null,driverOrder:1,driverName:`Driver ${carNumber}`,
   driverShortName:null,driverRating:'G',lastLap:50,lastLapMs:98_000,bestLap:12,bestLapMs:97_500,inPit:false,stintStartMs:now-30*60_000,stintLaps:18,energyPct:null,...extra});
  const session = {championship:'IMSA WeatherTech SportsCar Championship',event:'Petit Le Mans',name:'Race',type:'RACE',flag:'FULL_YELLOW',running:true,finished:false};
  let tower = {state:'LIVE',eventId:22,eventName:'Petit Le Mans',session,sessionDbId:3150,feedClockMs:now-20_000,matched:4,total:5,classes:[
   {className:'GTP',feedClass:'GTP',color:'#1a1a1a',cars:[car(1,'7'),car(2,'31',{gapToLeaderMs:4200,intervalMs:4200,bestLapMs:97_100,inPit:true})]},
   {className:'GTD PRO',feedClass:'GTDPRO',color:'#e30d0d',cars:[car(1,'04',{bestLapMs:105_000}),car(2,'4',{gapToLeaderLaps:1,intervalLaps:1,bestLapMs:104_000,entryId:null}),
    car(3,'23',{status:'RETIRED',stintStartMs:null,stintLaps:null})]}]};
  const laps = [1,2,3].map(n => ({lap:n,driverOrder:n<3?1:2,driverLap:n,position:1,startTimeMs:now-(4-n)*98_000,lapTimeMs:n===3?null:98_000-n*100,
   sectorMs:[32_000,33_000,n===3?null:32_900],sectorFlags:['GREEN',n===2?'YELLOW':'GREEN',null],valid:true,longLap:false,shortLap:false,trackLimits:0,topSpeed:280.5,pitInMs:n===2?1:null,pitOutMs:null}));
  const carDetail = {sessionDbId:3150,carNumber:'31',drivers:[{driverOrder:1,firstName:'Jack',lastName:'Aitken',shortName:'Ait',license:'Platinum',rating:'P',driverId:1},
   {driverOrder:2,firstName:'Pipo',lastName:'Derani',shortName:'Der',license:'Platinum',rating:'P',driverId:2}],laps,
   stints:[{startTimeMs:now-3_600_000,type:'TRACK',pitType:null,driverOrder:1,openLap:1,closeLap:2,finishTimeMs:now-1_800_000,driverAccumSessionTrackMs:1_780_000,driverAccumSessionMs:1_800_000,driverAccumTrackMs:1_780_000,driverAccumMs:1_800_000},
    {startTimeMs:now-1_800_000,type:'TRACK',pitType:null,driverOrder:2,openLap:3,closeLap:null,finishTimeMs:null,driverAccumSessionTrackMs:null,driverAccumSessionMs:null,driverAccumTrackMs:null,driverAccumMs:null}]};
  let rules = [{className:'GTP',rating:null,minMs:null,maxMs:4*3_600_000,note:'4h max'}];
  const driveResult = (car, driverOrder, name, rating, driveMs, status, extra={}) => ({car,driverOrder,name,rating,driverId:null,className:'GTP',driveMs,inCar:false,minMs:null,maxMs:4*3_600_000,status,owedMs:null,remainingMs:4*3_600_000-driveMs,overMs:null,...extra});
  const drive = () => ({sessionDbId:3150,eventId:22,rules,drivers:[
   driveResult('31',1,'Jack Aitken','P',3*3_600_000,'OK'),
   driveResult('31',2,'Pipo Derani','P',4*3_600_000+300_000,'OVER_MAX',{inCar:true,overMs:300_000,remainingMs:-300_000}),
   driveResult('7',1,'Bronze Driver','B',1_800_000,'UNDER_MIN',{minMs:3_600_000,owedMs:1_800_000})]});
  const puts = [], errors = [];
  let carRequests = 0;
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', async route => {
   const request = route.request(), url = new URL(request.url()), path = url.pathname;
   if (request.method() === 'PUT' && path === '/api/events/22/drive-time-rules') {
    const body = JSON.parse(request.postData());
    puts.push(body);
    if (body.some(r => r.className === 'NOPE')) return route.fulfill({status:422, json:{message:'The minimum for NOPE is above its maximum'}});
    rules = body;
    return route.fulfill({json:rules});
   }
   if (path === '/api/live/cars/31') carRequests++;
   const body = path === '/api/me' ? {email:null,isAdmin:true,authEnabled:false}
    : path === '/api/live/timing' ? tower
    : path === '/api/live/sessions' ? [{sessionDbId:3150,eventId:22,name:'Race',type:'RACE',dateMs:now,laps:3000,cars:5,current:true},{sessionDbId:3149,eventId:22,name:'Qualifying',type:'QUALIFYING',dateMs:now-86_400_000,laps:200,cars:5,current:false}]
    : path === '/api/live/cars/31' ? carDetail
    : path === '/api/live/drive-time' ? drive()
    : /^\/api\/events\/\d+$/.test(path) ? {event:{id:Number(path.split('/')[3]),name:path.endsWith('/22') ? 'Petit Le Mans' : 'Road America'}}
    : [];
   return route.fulfill({json:body});
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  await page.goto(`${base}/#/timing/22`);

  // The tower: class bands in the running order, each with its cars.
  const towerTable = page.getByRole('table',{name:'Running order by class'});
  await towerTable.waitFor();
  assert.deepEqual(await towerTable.locator('.class-band').allInnerTexts(), ['GTP','GTD PRO (GTDPRO)']);
  assert.deepEqual(await towerTable.locator('.tower-car').allInnerTexts(), ['7','31','04','4','23']);
  assert.match(await page.locator('.timing-head').innerText(), /Race\s*Full yellow/);
  assert.match(await page.locator('.timing-status').innerText(), /^Live$/);
  const row31 = towerTable.locator('.tower-row').nth(1);
  assert.match(await row31.innerText(), /\+4\.200/);
  assert.match(await row31.innerText(), /Pit/);
  assert.equal(await row31.locator('.tower-class-best').count(), 1, 'the GTP fastest lap is marked');
  assert.match(await towerTable.locator('.tower-row').nth(3).innerText(), /not entered[\s\S]*\+1 lap/);
  assert.match(await towerTable.locator('.tower-row').nth(4).innerText(), /retired/);
  assert.equal(await towerTable.locator('.tower-row').nth(0).locator('.tower-stint').innerText().then(t => /18 L\s+30:\d\d/.test(t)), true, 'the stint counts up from its start');
  assert.match(await page.locator('.timing-foot').innerText(), /4 of 5 cars matched/);
  assert.equal(await page.getByRole('button',{name:/connect/i}).count(), 0, 'no connection controls on the web');
  assert.equal(await page.evaluate(() => document.body.scrollWidth > document.documentElement.clientWidth), false);

  // A car's laps (newest first) and stints, polled only while open.
  await page.getByRole('button',{name:'#31 Team 31: laps and stints'}).click();
  const modal = page.getByRole('dialog',{name:'Car 31 laps and stints'});
  await modal.locator('tbody tr').first().waitFor();
  assert.match(await modal.locator('.lc-head').innerText(), /#31[\s\S]*Jack Aitken[\s\S]*Pipo Derani/);
  assert.deepEqual(await modal.locator('tbody tr td:first-child').allInnerTexts(), ['3','2','1']);
  assert.match(await modal.locator('tbody tr').first().innerText(), /Derani[\s\S]*In progress/);
  assert.match(await modal.locator('tbody tr').nth(1).innerText(), /Pit in/);
  assert.equal(await modal.locator('.lc-best').count(), 1);
  await modal.getByRole('tab',{name:/Stints/}).click();
  assert.match(await modal.locator('tbody tr').first().innerText(), /Derani[\s\S]*Current/);
  assert.match(await modal.locator('tbody tr').nth(1).innerText(), /Aitken[\s\S]*1–2[\s\S]*29:40/);
  await page.keyboard.press('Escape');
  await modal.waitFor({state:'detached'});
  const before = carRequests;
  await page.waitForTimeout(2500);
  assert.equal(carRequests, before, 'a closed panel stops polling');

  // Drive time: statuses in words, the rules beside them.
  await page.getByRole('tab',{name:'Drive time'}).click();
  assert.match(page.url(), /view=drive/);
  const driveTable = page.getByRole('table',{name:'Drive time by driver'});
  await driveTable.waitFor();
  assert.match(await driveTable.innerText(), /Pipo Derani\s*In car[\s\S]*Over by 5:00/);
  assert.match(await driveTable.innerText(), /Bronze Driver[\s\S]*30:00 to go/);
  assert.match(await driveTable.innerText(), /Jack Aitken[\s\S]*OK · 1:00:00 left/);
  assert.match(await page.locator('.timing-summary').innerText(), /1 over the maximum[\s\S]*1 short of the minimum[\s\S]*1 within the rules/);
  assert.deepEqual(await page.getByRole('group',{name:'Session'}).getByRole('button').allInnerTexts(), ['Race · live','Qualifying']);

  // The admin rules editor: a bad time is caught here, a refused rule shows the server's reason.
  await page.getByRole('button',{name:'Edit rules'}).click();
  await page.getByRole('button',{name:'Add rule'}).click();
  await page.getByLabel('Rule 2 class').fill('GTD PRO');
  await page.getByLabel('Rule 2 applies to').selectOption('B');
  await page.getByLabel('Rule 2 minimum').fill('soon');
  await page.getByRole('button',{name:'Save rules'}).click();
  await page.getByText('Row 2: write times as hours').waitFor();
  assert.equal(puts.length, 0, 'nothing sent while a time is unreadable');
  await page.getByLabel('Rule 2 minimum').fill('1:30');
  await page.getByLabel('Rule 1 class').fill('NOPE');
  await page.getByRole('button',{name:'Save rules'}).click();
  await page.getByText('The minimum for NOPE is above its maximum').waitFor();
  await page.getByLabel('Rule 1 class').fill('GTP');
  await page.getByRole('button',{name:'Save rules'}).click();
  await page.getByRole('button',{name:'Edit rules'}).waitFor();
  assert.deepEqual(puts.at(-1), [
   {className:'GTP',rating:null,minMs:null,maxMs:4*3_600_000,note:'4h max'},
   {className:'GTD PRO',rating:'B',minMs:5_400_000,maxMs:null,note:null}]);
  assert.match(await page.locator('.rules-list').innerText(), /GTD PRO\s*Bronze\s*min 1:30:00/);

  // Feed following another event: this page says so and points there.
  tower = {...tower, eventId:99, eventName:'Road America'};
  await page.getByRole('tab',{name:'Tower'}).click();
  await page.getByText('Live timing is following').waitFor({timeout:5000});
  assert.equal(await page.getByRole('link',{name:'Road America'}).getAttribute('href'), '#/timing/99');
  assert.equal(await page.locator('h1').innerText(), 'Petit Le Mans', "this event's own name, not the feed's");
  // Switched off from the iPad: the page follows within a poll.
  tower = {...tower, state:'OFF', eventId:22, eventName:'Petit Le Mans'};
  await page.getByText('Live timing is off.').waitFor({timeout:5000});
  assert.match(await page.locator('.timing-status').innerText(), /Off/);

  assert.deepEqual(errors, []);
  console.log('live timing page: ok');
 } finally {
  await browser.close();
  await server.close();
 }
})().catch(e => { console.error(e); process.exit(1); });
