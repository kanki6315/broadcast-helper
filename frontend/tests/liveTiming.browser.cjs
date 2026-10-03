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
  // Real names and teams, the longest the grid has: the 1024px checks below only mean something with them.
  const crews = {'7':['Frederik Schandorff','Acura Meyer Shank Racing w/Curb Agajanian'],'31':['Sébastien Bourdais','Cadillac Whelen'],
   '04':['Klaus Bachler','Corvette Racing by Pratt Miller Motorsports'],'4':['Antonio García','Heart of Racing Team'],'23':['Alessio Rovera','Paul Miller Racing']};
  const car = (position, carNumber, extra={}) => ({position,carNumber,entryId:100+position,teamName:crews[carNumber][1],vehicle:null,manufacturer:null,
   status:'CLASSIFIED',laps:50,gapToLeaderMs:null,gapToLeaderLaps:null,intervalMs:null,intervalLaps:null,driverOrder:1,driverName:crews[carNumber][0],
   driverShortName:null,driverRating:'G',lastLap:50,lastLapMs:98_000,bestLap:12,bestLapMs:97_500,inPit:false,stintStartMs:now-30*60_000,stintLaps:18,energyPct:null,energyLapsLeft:null,pitStops:0,lastPitMs:null,trackStatus:null,currentSector:null,sectors:null,bestSectorMs:null,idealMs:null,startPosition:null,topSpeed:null,
   overallPosition:null,overallGapMs:null,overallGapLaps:null,overallIntervalMs:null,overallIntervalLaps:null,checkered:false,bestLapDriver:null,...extra});
  const sec = (ms, currentLap=true, valid=true) => ({ms,valid,currentLap});
  const session = {championship:'IMSA WeatherTech SportsCar Championship',event:'Petit Le Mans',name:'Race',type:'RACE',flag:'FULL_YELLOW',running:true,finished:false,
   clock:{finalType:'BY_TIME',startMs:now-10*60_000,finalMs:2*3_600_000,finalLaps:null,currentLap:null,stopMs:null,stoppedMs:60_000,utcOffsetHours:-4}};
  let tower = {state:'LIVE',eventId:22,eventName:'Petit Le Mans',filedEventId:22,filedEventName:'Petit Le Mans',session,sessionDbId:3150,feedClockMs:now-20_000,matched:4,total:5,speedUnit:'mph',classes:[
   {className:'GTP',feedClass:'GTP',color:'#1a1a1a',bestSectors:[{ms:31_900,car:'31',driver:'Aitken'},{ms:33_000,car:'7',driver:null},{ms:32_700,car:'31',driver:'Derani'}],idealMs:97_600,cars:[car(1,'7',{overallPosition:1,bestLapDriver:'Driver Seven',startPosition:3,topSpeed:151.3,energyPct:62.4,energyLapsLeft:9.6,pitStops:2,lastPitMs:65_000,
    trackStatus:'TRACK',currentSector:3,sectors:[sec(32_000),sec(33_000),sec(32_900,false)],bestSectorMs:[32_000,33_000,32_800],idealMs:97_800}),car(2,'31',{overallPosition:3,overallGapMs:6100,overallIntervalMs:1900,startPosition:1,topSpeed:150.2,gapToLeaderMs:4200,intervalMs:4200,lastLapMs:97_100,bestLapMs:97_100,inPit:true,trackStatus:'BOX',currentSector:1,
    sectors:[sec(31_900,false),sec(33_100,false,false),null],bestSectorMs:[31_900,33_050,32_700]})]},
   {className:'GTD PRO',feedClass:'GTDPRO',color:'#e30d0d',bestSectors:[],idealMs:null,cars:[car(1,'04',{overallPosition:2,overallGapMs:4200,overallIntervalMs:4200,lastLapMs:105_000,bestLapMs:105_000,trackStatus:'OUT_LAP',
    stintStartMs:now-(75*60_000+42_000),stintLaps:41,currentSector:3,sectors:[sec(74_218),sec(61_480),null],bestSectorMs:[35_102,36_877,34_950]}),
    car(2,'4',{overallPosition:4,overallGapLaps:1,overallIntervalLaps:1,gapToLeaderLaps:1,intervalLaps:1,bestLapMs:104_000,entryId:null,checkered:true}),
    car(3,'23',{overallPosition:5,status:'RETIRED',stintStartMs:null,stintLaps:null})]}],
   raceControl:{lines:[{key:'1',dayTimeMs:null,text:'FULL COURSE YELLOW',group:null,line:1,foreground:'#000000',background:'#ffff00',blink:true}],
    latest:{key:'3600000',dayTimeMs:Date.UTC(2026,9,4,18,5,9),text:'CAR 7 DRIVE THROUGH - PIT LANE SPEEDING',group:'GTP',line:2,foreground:null,background:'#ff0000',blink:false}}};
  const wx = (min, trackF, airF, extra={}) => ({dayTimeMs:Date.UTC(2026,9,4,18,min,0),airC:(airF-32)*5/9,airF,trackC:(trackF-32)*5/9,trackF,humidityPct:61,
   pressureMbar:1012.4,pressureInHg:29.9,windDirection:225,windKmh:14,windMph:8.7,...extra});
  tower.weather = wx(3, 100.9, 75.7);
  const wxLog = {sessionDbId:3150,readings:[wx(0, 98.0, 75.0), wx(1, 99.2, 75.4, {humidityPct:null}), wx(2, 100.6, 75.6, {windDirection:null,windMph:null,windKmh:null})]};
  const rcLog = {sessionDbId:3150,messages:[tower.raceControl.latest,
   {key:'3500000',dayTimeMs:Date.UTC(2026,9,4,18,1,0),text:'FULL COURSE YELLOW',group:null,line:1,foreground:'#000000',background:'#ffff00',blink:true}]};
  const laps = [1,2,3].map(n => ({lap:n,driverOrder:n<3?1:2,driverLap:n,position:1,startTimeMs:now-(4-n)*98_000,lapTimeMs:n===3?null:98_000-n*100,
   sectorMs:[32_000,33_000,n===3?null:32_900],sectorFlags:['GREEN',n===2?'YELLOW':'GREEN',null],valid:true,longLap:false,shortLap:false,trackLimits:0,topSpeed:280.5,pitInMs:n===2?1:null,pitOutMs:null,energyPct:n===3?null:100-n*3.5,energyUsedPct:n===2?3.5:null}));
  const carDetail = {sessionDbId:3150,carNumber:'31',drivers:[{driverOrder:1,firstName:'Jack',lastName:'Aitken',shortName:'Ait',license:'Platinum',rating:'P',driverId:1},
   {driverOrder:2,firstName:'Pipo',lastName:'Derani',shortName:'Der',license:'Platinum',rating:'P',driverId:2}],laps,
   stints:[{startTimeMs:now-3_600_000,type:'TRACK',pitType:null,driverOrder:1,openLap:1,closeLap:2,finishTimeMs:now-1_800_000,driverAccumSessionTrackMs:1_780_000,driverAccumSessionMs:1_800_000,driverAccumTrackMs:1_780_000,driverAccumMs:1_800_000,avgEnergyPerLapPct:3.5},
    {startTimeMs:now-1_800_000,type:'TRACK',pitType:null,driverOrder:2,openLap:3,closeLap:null,finishTimeMs:null,driverAccumSessionTrackMs:null,driverAccumSessionMs:null,driverAccumTrackMs:null,driverAccumMs:null,avgEnergyPerLapPct:null}]};
  let rules = [{className:'GTP',rating:null,minMs:null,maxMs:4*3_600_000,note:'4h max'}];
  const driveResult = (car, driverOrder, name, rating, driveMs, status, extra={}) => ({car,driverOrder,name,rating,driverId:null,className:'GTP',driveMs,inCar:false,minMs:null,maxMs:4*3_600_000,status,owedMs:null,remainingMs:4*3_600_000-driveMs,overMs:null,...extra});
  const drive = () => ({sessionDbId:3150,eventId:22,rules,drivers:[
   driveResult('31',1,'Jack Aitken','P',3*3_600_000,'OK'),
   driveResult('31',2,'Pipo Derani','P',4*3_600_000+300_000,'OVER_MAX',{inCar:true,overMs:300_000,remainingMs:-300_000}),
   driveResult('7',1,'Bronze Driver','B',1_800_000,'UNDER_MIN',{minMs:3_600_000,owedMs:1_800_000})]});
  const puts = [], posts = [], errors = [];
  let isAdmin = true;
  let status = {state:'LIVE',configured:true,desiredConnected:true,eventId:22,eventName:'Petit Le Mans',filedEventId:22,filedEventName:'Petit Le Mans',lastError:null};
  let carRequests = 0;
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', async route => {
   const request = route.request(), url = new URL(request.url()), path = url.pathname;
   if (request.method() === 'POST' && path.startsWith('/api/live/')) {
    posts.push({path, body: request.postData() ? JSON.parse(request.postData()) : null});
    status = path.endsWith('/disconnect') ? {...status, desiredConnected:false, state:'OFF'}
     : {...status, desiredConnected:true, state:'LIVE', eventId:JSON.parse(request.postData()).eventId};
    return route.fulfill({json:status});
   }
   if (request.method() === 'PUT' && path === '/api/events/22/drive-time-rules') {
    const body = JSON.parse(request.postData());
    puts.push(body);
    if (body.some(r => r.className === 'NOPE')) return route.fulfill({status:422, json:{message:'The minimum for NOPE is above its maximum'}});
    rules = body;
    return route.fulfill({json:rules});
   }
   if (path === '/api/live/cars/31') carRequests++;
   const body = path === '/api/me' ? {email:null,isAdmin,authEnabled:true}
    : path === '/api/live/status' ? status
    : path === '/api/live/timing' ? tower
    : path === '/api/live/sessions' ? [{sessionDbId:3150,eventId:22,name:'Race',type:'RACE',dateMs:now,laps:3000,cars:5,current:true},{sessionDbId:3149,eventId:22,name:'Qualifying',type:'QUALIFYING',dateMs:now-86_400_000,laps:200,cars:5,current:false}]
    : path === '/api/live/cars/31' ? carDetail
    : path === '/api/live/drive-time' ? drive()
    : path === '/api/live/sectors' ? {sessionDbId:3150,classes:[]}
    : path === '/api/live/weather' ? (url.searchParams.get('session') === '3149' ? {sessionDbId:3149,readings:[]} : wxLog)
    : path === '/api/live/race-control' ? (url.searchParams.get('session') === '3149' ? {sessionDbId:3149,messages:[]} : rcLog)
    : /^\/api\/events\/\d+$/.test(path) ? {event:{id:Number(path.split('/')[3]),name:path.endsWith('/22') ? 'Petit Le Mans' : 'Road America'}}
    : [];
   return route.fulfill({json:body});
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  await page.goto(`${base}/#/timing/22`);

  // The tower: class bands in the running order, each with its cars.
  const towerTable = page.getByRole('table',{name:'Running order by class'});
  await towerTable.waitFor();
  assert.deepEqual(await towerTable.locator('.class-band .band-label').allInnerTexts(), ['GTP','GTD PRO (GTDPRO)']);
  assert.deepEqual(await towerTable.locator('.tower-car').allInnerTexts(), ['7','31','04','4','23']);
  assert.match(await page.locator('.timing-head').innerText(), /Race\s*Full yellow/);
  assert.match(await page.locator('.timing-status').innerText(), /^Live$/);
  const row31 = towerTable.locator('.tower-row').nth(1);
  assert.match(await row31.innerText(), /\+4\.200/);
  assert.match(await row31.innerText(), /Pit/);
  assert.equal(await row31.locator('td.tower-class-best:not(.tower-sector)').count(), 2, 'the GTP fastest lap is marked, on best and on the last lap that set it');
  assert.equal(await towerTable.locator('.tower-row').nth(2).locator('.tower-last.tower-pb').count(), 1, "#04's last lap was its own best, not the class's");
  assert.equal(await towerTable.locator('.tower-row').nth(0).locator('.tower-last.tower-pb, .tower-last.tower-class-best').count(), 0, 'a slower last lap is plain');
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('.tower-pits').innerText(), /^1:05\s+×2/, "the last stop's pit-lane time first, then the stops");
  assert.equal(await towerTable.locator('thead th', {hasText:'Last pit'}).count(), 1);
  assert.equal((await towerTable.locator('.tower-row').nth(1).locator('.tower-pits').innerText()).trim(), '', 'no stops yet: nothing to read');
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('td:not(.tower-last)').filter({hasText:'1:37.500'}).getAttribute('title'), /Set by Driver Seven/);
  // Past the chequered flag: the flag itself, in place of any other mark.
  assert.equal(await towerTable.locator('.tower-row').nth(3).locator('.tower-flag-mark').count(), 1);
  assert.match(await towerTable.locator('.tower-row').nth(3).locator('.tower-state').innerText(), /chequered flag/);
  assert.equal(await towerTable.locator('.tower-flag-mark').count(), 1);

  // Sectors as they are run: purple for the class's fastest, green for the car's own best, the previous lap's muted.
  assert.deepEqual(await towerTable.locator('thead th.tower-sector').allInnerTexts(), ['S1','S2','S3']);
  const s7 = towerTable.locator('.tower-row').nth(0).locator('td.tower-sector');
  assert.match(await s7.nth(0).getAttribute('class'), /tower-pb/);
  assert.match(await s7.nth(1).getAttribute('class'), /tower-class-best/);
  assert.match(await s7.nth(2).getAttribute('class'), /tower-sector--old/);
  const s31 = towerTable.locator('.tower-row').nth(1).locator('td.tower-sector');
  assert.match(await s31.nth(0).getAttribute('class'), /tower-class-best/, 'a previous-lap time keeps its mark');
  assert.match(await s31.nth(1).getAttribute('class'), /tower-sector--invalid/);
  assert.equal((await s31.nth(2).innerText()).trim(), '');
  assert.match((await towerTable.locator('.band-bests').first().innerText()).replace(/\s+/g,' '), /S1 31\.900 #31 Aitken\s+S2 33\.000 #7\s+S3 32\.700 #31 Derani\s+Ideal 1:37\.600/);
  assert.equal(await towerTable.locator('.class-band').nth(1).locator('.band-bests').count(), 0, 'no bests, no strip');
  assert.match(await towerTable.locator('.tower-row').nth(2).locator('.tower-state').innerText(), /Out/);

  // The session clock counts down: 2 h, 10 min since the start, 1 min of it stopped.
  assert.match(await page.locator('.timing-clock-main').innerText(), /^1:5[01]:\d\d\s*to go$/);
  assert.match(await page.locator('.timing-clock-sub').first().innerText(), /^\d{1,2}:\d\d:\d\d at the track$/);
  assert.match(await towerTable.locator('.tower-row').nth(3).innerText(), /not entered[\s\S]*\+1 lap/);
  assert.match(await towerTable.locator('.tower-row').nth(4).innerText(), /retired/);
  assert.equal(await towerTable.locator('.tower-row').nth(0).locator('.tower-stint').innerText().then(t => /18 L\s+30:\d\d/.test(t)), true, 'the stint counts up from its start');
  assert.match(await page.locator('.timing-foot').innerText(), /4 of 5 cars matched/);
  // IMSA telemetry: the Energy column appears once any car has a reading.
  assert.equal(await towerTable.locator('thead th', {hasText:'Energy'}).count(), 1);
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('.tower-energy').innerText(), /62%\s+~9 L/);
  assert.equal((await towerTable.locator('.tower-row').nth(1).locator('.tower-energy').innerText()).trim(), '');

  // Race control: the screen's lines, and the newest message when it is not on the screen, timed at the track.
  const strip = page.getByRole('region',{name:'Race control'});
  assert.deepEqual(await strip.locator('.rc-msg').allInnerTexts(), ['FULL COURSE YELLOW']);
  assert.match(await strip.locator('.rc-msg').getAttribute('class'), /rc-msg--blink/);
  assert.match(await strip.locator('.rc-latest').innerText(), /^14:05:09\s*GTP\s*CAR 7 DRIVE THROUGH - PIT LANE SPEEDING$/);
  assert.equal(await strip.getByRole('link',{name:'All messages'}).getAttribute('href'), '#/timing/22?view=control');

  // Weather: the latest reading in the feed's units (it times in mph, so °F, mph and inHg).
  const wxStrip = page.getByRole('region',{name:'Weather'});
  assert.equal((await wxStrip.locator('.wx-now').innerText()).replace(/\s+/g,' ').trim(),
   'Track 100.9° Air 75.7° Humidity 61% Wind 9 mph SW Pressure 29.90 inHg');
  assert.equal(await wxStrip.getByRole('link',{name:'Over the session'}).getAttribute('href'), '#/timing/22?view=weather');
  // The admin's switch: bound here, so only Disconnect — and it asks first.
  const control = page.getByLabel('Live timing connection');
  await control.getByRole('button',{name:'Disconnect'}).waitFor();
  assert.equal(await control.getByRole('button',{name:'Score this event'}).count(), 0);
  await control.getByRole('button',{name:'Disconnect'}).click();
  assert.match(await control.innerText(), /Disconnect for everyone\?/);
  await control.getByRole('button',{name:'Keep connected'}).click();
  assert.equal(posts.length, 0, 'backing out sends nothing');
  await control.getByRole('button',{name:'Disconnect'}).click();
  await control.getByRole('button',{name:'Disconnect for everyone'}).click();
  await control.getByRole('button',{name:'Connect for this event'}).waitFor();
  assert.deepEqual(posts.map(p => p.path), ['/api/live/disconnect']);
  await control.getByRole('button',{name:'Connect for this event'}).click();
  await control.getByRole('button',{name:'Disconnect'}).waitFor();
  assert.deepEqual(posts.at(-1), {path:'/api/live/connect', body:{eventId:22}});
  posts.length = 0;
  // The tower with sectors, pits and energy fits a laptop and up: 1009 is 1024 beside a classic scrollbar.
  const fits = async (what) => {
   for (const width of [1009,1024,1280,1440]) {
    await page.setViewportSize({width,height:900});
    assert.equal(await page.evaluate(() => document.body.scrollWidth > document.documentElement.clientWidth), false, `${what} fits ${width}px`);
   }
   await page.setViewportSize({width:1280,height:900});
  };
  await fits('the tower by class');
  // Narrow, the driver is initial and surname, in full on hover; the team gives way, the energy cell does not.
  await page.setViewportSize({width:1024,height:900});
  const name7 = towerTable.locator('.tower-row').nth(0).locator('.tower-driver-name');
  assert.equal(await name7.innerText(), 'F. Schandorff');
  assert.equal(await name7.getAttribute('title'), 'Frederik Schandorff');
  assert.equal(await towerTable.locator('thead th.tower-team').isVisible(), false);
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('.tower-energy').innerText(), /62%\s+~9 L/);
  await page.setViewportSize({width:1440,height:900});
  assert.equal(await name7.innerText(), 'Frederik Schandorff', 'in full where there is room');
  assert.equal(await towerTable.locator('.tower-row').nth(2).locator('.tower-team').isVisible(), true);
  await page.setViewportSize({width:1280,height:900});

  // Optional columns: top speed is opt-in, the rest can be hidden; only columns with data are offered.
  assert.equal(await towerTable.locator('thead th.tower-speed').count(), 0, 'top speed starts hidden');
  await page.locator('.tower-columns summary').click();
  const choice = page.locator('.tower-columns fieldset');
  assert.deepEqual(await choice.locator('label').allInnerTexts(), ['Sectors','Top speed','Pits','Energy']);
  await choice.getByLabel('Top speed').check();
  assert.equal(await towerTable.locator('thead th.tower-speed').innerText(), 'Top');
  assert.equal(await towerTable.locator('thead th.tower-speed').getAttribute('title'), 'Best speed trap of the session (mph)');
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('td.tower-speed').getAttribute('class'), /tower-class-best/);
  assert.match(await towerTable.locator('.tower-row').nth(1).locator('td.tower-speed').innerText(), /^150\.2\s+mph$/);
  await choice.getByLabel('Sectors').uncheck();
  assert.equal(await towerTable.locator('thead th.tower-sector').count(), 0);
  assert.equal(await page.evaluate(() => localStorage.getItem('pitpass.timing.columns')),
    JSON.stringify({sectors:false,topSpeed:true,pits:true,energy:true}), 'remembered in this browser');
  await choice.getByLabel('Sectors').check();
  await choice.getByLabel('Top speed').uncheck();
  await page.locator('.tower-columns summary').click();

  // Overall: one list in the feed's overall order, each car's class beside it, gaps overall.
  await page.getByRole('group',{name:'Running order'}).getByRole('button',{name:'Overall'}).click();
  const overallTable = page.getByRole('table',{name:'Running order overall'});
  await overallTable.waitFor();
  assert.equal(await overallTable.locator('.class-band').count(), 0);
  assert.deepEqual(await overallTable.locator('.tower-car').allInnerTexts(), ['7','04','31','4','23']);
  assert.deepEqual(await overallTable.locator('.tower-pos').allInnerTexts(), ['1','2','3','4','5'], 'overall places; no class gains');
  assert.match((await overallTable.locator('.tower-row').nth(1).locator('.tower-class').innerText()).replace(/\s+/g,' '), /^GTD PRO 1$/);
  assert.match(await overallTable.locator('.tower-row').nth(2).innerText(), /\+6\.100\s+\+1\.900/, "#31's gap and interval overall, not in class");
  assert.equal(await page.evaluate(() => localStorage.getItem('pitpass.timing.order')), 'overall');
  assert.equal(await overallTable.locator('.tower-row').nth(1).locator('.tower-last.tower-pb').count(), 1, 'marks still count against the class');
  await fits('the tower overall');
  if (process.env.TIMING_SHOT_DIR) await page.screenshot({path:`${process.env.TIMING_SHOT_DIR}/overall.png`});
  await page.getByRole('group',{name:'Running order'}).getByRole('button',{name:'By class'}).click();
  await towerTable.waitFor();
  if (process.env.TIMING_SHOT_DIR) await page.screenshot({path:`${process.env.TIMING_SHOT_DIR}/by-class.png`});

  // Places gained in class since the start, and the field at a glance.
  assert.match(await towerTable.locator('.tower-row').nth(0).locator('.tower-pos').innerText(), /^1\s*▲2/);
  assert.match(await towerTable.locator('.tower-row').nth(1).locator('.tower-pos').innerText(), /^2\s*▼1/);
  assert.equal(await towerTable.locator('.tower-row').nth(2).locator('.tower-gain--up, .tower-gain--down').count(), 0, 'no start position, no mark');
  assert.equal(await page.locator('.timing-counts').innerText(), '3 on track · 1 in pit · 0 stopped · 1 retired');

  // A car that changes place flashes, then settles; the first tower seen never flashes.
  assert.equal(await towerTable.locator('.tower-row--moved').count(), 0);
  const gtd = tower.classes[1].cars;
  tower = {...tower, classes:[tower.classes[0], {...tower.classes[1], cars:[{...gtd[1], position:1}, {...gtd[0], position:2}, gtd[2]]}]};
  await towerTable.locator('.tower-row--moved').first().waitFor();
  assert.deepEqual(await towerTable.locator('.tower-row--moved .tower-car').allInnerTexts(), ['4','04']);
  await towerTable.locator('.tower-row--moved').first().waitFor({state:'detached', timeout:8000});
  tower = {...tower, classes:[tower.classes[0], {...tower.classes[1], cars:gtd}]};
  await towerTable.locator('.tower-row--moved').first().waitFor();
  await towerTable.locator('.tower-row--moved').first().waitFor({state:'detached', timeout:8000});

  // A red flag stops the clock where it stood, in red and in words.
  tower = {...tower, session:{...session, flag:'RED', running:false, clock:{...session.clock, stopMs:now-5*60_000}}};
  await page.locator('.timing-clock--stopped').waitFor();
  assert.match(await page.locator('.timing-clock-main').innerText(), /^1:56:00\s*Clock stopped$/);
  tower = {...tower, session};

  // A car's laps (newest first) and stints, polled only while open.
  await page.getByRole('button',{name:'#31 Cadillac Whelen: laps and stints'}).click();
  const modal = page.getByRole('dialog',{name:'Car 31 laps and stints'});
  await modal.locator('tbody tr').first().waitFor();
  assert.match(await modal.locator('.lc-head').innerText(), /#31[\s\S]*Jack Aitken[\s\S]*Pipo Derani/);
  assert.deepEqual(await modal.locator('tbody tr td:first-child').allInnerTexts(), ['3','2','1']);
  assert.match(await modal.locator('tbody tr').first().innerText(), /Derani[\s\S]*In progress/);
  assert.match(await modal.locator('tbody tr').nth(1).innerText(), /Pit in/);
  assert.equal(await modal.locator('.lc-best').count(), 1);
  assert.match((await modal.locator('.lc-caption').innerText()).replace(/\s+/g,' '), /^Best lap 1:37\.800 by Aitken \(lap 2\)/, 'who set the fastest lap');
  assert.match(await modal.locator('.lc-drivers li').nth(0).locator('.lc-driver-best--car').innerText(), /1:37\.800/);
  assert.equal(await modal.locator('.lc-drivers li').nth(1).locator('.lc-driver-best').count(), 0, 'no completed lap for Derani yet');
  if (process.env.TIMING_SHOT_DIR) await page.screenshot({path:`${process.env.TIMING_SHOT_DIR}/car.png`});
  assert.match(await modal.locator('tbody tr').nth(1).innerText(), /93\.0%\s+3\.5%/, 'energy at the line and used');
  await modal.getByRole('tab',{name:/Stints/}).click();
  assert.match(await modal.locator('tbody tr').first().innerText(), /Derani[\s\S]*Current/);
  assert.match(await modal.locator('tbody tr').nth(1).innerText(), /Aitken[\s\S]*1–2[\s\S]*29:40\s+3\.50%/);
  await page.keyboard.press('Escape');
  await modal.waitFor({state:'detached'});
  const before = carRequests;
  await page.waitForTimeout(2500);
  assert.equal(carRequests, before, 'a closed panel stops polling');

  // The race control log: newest first, track time while live; an older session with none says so.
  await page.getByRole('tab',{name:'Race control'}).click();
  assert.match(page.url(), /view=control/);
  const rcTable = page.getByRole('table',{name:'Race control messages, newest first'});
  await rcTable.waitFor();
  assert.deepEqual((await rcTable.locator('tbody tr').allInnerTexts()).map(t => t.replace(/\s+/g,' ').trim()),
   ['14:05:09 GTP CAR 7 DRIVE THROUGH - PIT LANE SPEEDING', '14:01:00 FULL COURSE YELLOW']);
  assert.equal(await rcTable.locator('td.rc-log-time').first().evaluate(td => getComputedStyle(td).getPropertyValue('--rc-color').trim()), '#ff0000');
  await page.getByRole('group',{name:'Session'}).getByRole('button',{name:'Qualifying'}).click();
  await page.getByText('No race control messages recorded for this session.').waitFor();
  await page.getByRole('group',{name:'Session'}).getByRole('button',{name:'Race · live'}).click();

  // Weather over the session: where track and air stand and how far they moved; every reading, newest first.
  await page.getByRole('tab',{name:'Weather'}).click();
  assert.match(page.url(), /view=weather/);
  await page.getByRole('img',{name:/Track and air temperature over the session/}).waitFor();
  assert.deepEqual((await page.locator('.wx-stat').allInnerTexts()).map(t => t.replace(/\s+/g,' ').trim()),
   ['TRACK 100.6° +2.6° since 98.0° Low 98.0° · high 100.6°', 'AIR 75.6° +0.6° since 75.0° Low 75.0° · high 75.6°']);
  assert.equal(await page.locator('.wx-chart .an-line').count(), 2);
  await page.getByText('Every reading (3)').click();
  const wxTable = page.getByRole('table',{name:'Weather readings, newest first'});
  assert.deepEqual((await wxTable.locator('tbody tr').allInnerTexts()).map(t => t.replace(/\s+/g,' ').trim()),
   ['14:02:00 100.6° 75.6° 61% — 29.90 inHg', '14:01:00 99.2° 75.4° — 9 mph SW 29.90 inHg', '14:00:00 98.0° 75.0° 61% 9 mph SW 29.90 inHg']);
  if (process.env.TIMING_SHOT_DIR) await page.screenshot({path:`${process.env.TIMING_SHOT_DIR}/weather.png`, fullPage:true});
  await page.getByRole('group',{name:'Session'}).getByRole('button',{name:'Qualifying'}).click();
  await page.getByText('No weather recorded for this session.').waitFor();
  await page.getByRole('group',{name:'Session'}).getByRole('button',{name:'Race · live'}).click();

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
  tower = {...tower, eventId:99, eventName:'Road America', filedEventId:99, filedEventName:'Road America'};
  await page.getByRole('tab',{name:'Tower'}).click();
  await page.getByText('Live timing is following').waitFor({timeout:5000});
  assert.equal(await page.getByRole('link',{name:'Road America'}).getAttribute('href'), '#/timing/99');
  assert.equal(await page.locator('h1').innerText(), 'Petit Le Mans', "this event's own name, not the feed's");
  // Bound here, but the session on track is another series', filed nowhere: its own teams, said once.
  tower = {...tower, eventId:22, eventName:'Petit Le Mans', filedEventId:null, filedEventName:null};
  await page.locator('.timing-unfiled').waitFor({timeout:5000});
  assert.match(await page.locator('.timing-unfiled').innerText(), /not filed under this event/);
  assert.equal(await page.locator('.tower-unmatched').count(), 0, 'no per-row "not entered" when nothing is filed');
  assert.doesNotMatch(await page.locator('.timing-foot').innerText(), /matched to this event/);
  // Switched off from the iPad: the page follows within a poll.
  tower = {...tower, state:'OFF', eventId:22, eventName:'Petit Le Mans', filedEventId:22, filedEventName:'Petit Le Mans'};
  await page.getByText('Live timing is off.').waitFor({timeout:5000});
  assert.match(await page.locator('.timing-status').innerText(), /Off/);

  // Following another event: the admin can bring it here.
  status = {...status, desiredConnected:true, state:'LIVE', eventId:99, eventName:'Road America'};
  await page.reload();
  await page.getByLabel('Live timing connection').getByRole('button',{name:'Score this event'}).click();
  await page.getByLabel('Live timing connection').getByRole('button',{name:'Score this event'}).waitFor({state:'detached'});
  assert.deepEqual(posts.at(-1), {path:'/api/live/connect', body:{eventId:22}});

  // A viewer sees the status, never the switch.
  isAdmin = false;
  await page.reload();
  await page.locator('.timing-status').waitFor();
  assert.equal(await page.getByLabel('Live timing connection').count(), 0);
  assert.equal(await page.getByRole('button',{name:/connect/i}).count(), 0);

  assert.deepEqual(errors, []);
  console.log('live timing page: ok');
 } finally {
  await browser.close();
  await server.close();
 }
})().catch(e => { console.error(e); process.exit(1); });
