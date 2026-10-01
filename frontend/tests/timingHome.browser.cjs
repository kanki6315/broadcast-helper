const { chromium } = require('playwright');
const assert = require('node:assert/strict');
// /timing and /timing/weekend/:id against a stubbed API: recorded series
// weekends filed or not, the admin's filing control, connecting without an
// event, and a weekend's own timing page for a series Pit Pass does not follow.
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true});
 try {
  const page = await browser.newPage({viewport:{width:1280,height:900}});
  const t0 = Date.UTC(2026, 8, 30, 16, 25);
  const session = (id, name, current=false, eventId=null) => ({sessionDbId:id,eventId,name,type:'FREE_PRACTICE',dateMs:t0+id,laps:100,cars:17,current});
  const impc = {feedEventDbId:611,champDbId:612,champName:'IMSA Michelin Pilot Challenge',feedEventName:'Fox Factory 120',track:'Road Atlanta',
   eventId:340,eventName:'Fox Factory 120 (Oct 2)',boundBy:'AUTO',boundByEmail:null,firstSessionMs:t0,lastSessionMs:t0,
   sessions:[session(3183,'Practice 1',false,340)],candidates:[{id:340,name:'Fox Factory 120 (Oct 2)',seriesName:'IMSA Michelin Pilot Challenge',date:'2026-10-03'}]};
  let vp = {feedEventDbId:612,champDbId:613,champName:'IMSA VP Racing SportsCar Challenge',feedEventName:'29th Annual Motul Petit Le Mans',track:'Road Atlanta',
   eventId:null,eventName:null,boundBy:null,boundByEmail:null,firstSessionMs:t0+4_000_000,lastSessionMs:t0+12_000_000,
   sessions:[session(3185,'Qualifying',true),session(3189,'Practice 2')],
   candidates:[{id:340,name:'Fox Factory 120 (Oct 2)',seriesName:'IMSA Michelin Pilot Challenge',date:'2026-10-03'},{id:339,name:'Motul Petit LeMans',seriesName:'IMSA WeatherTech SportsCar Championship',date:'2026-10-03'}]};
  const feedSession = {championship:vp.champName,event:vp.feedEventName,name:'Qualifying',type:'QUALIFYING_BEST_LAP',flag:'GREEN',running:true,finished:false,feedEventDbId:612};
  let status = {state:'OFF',configured:true,desiredConnected:false,eventId:340,eventName:'Fox Factory 120 (Oct 2)',filedEventId:null,filedEventName:null,lastError:null,session:null};
  const tower = {state:'LIVE',eventId:null,eventName:null,filedEventId:null,filedEventName:null,session:feedSession,sessionDbId:3185,feedClockMs:t0,matched:0,total:1,classes:[
   {className:'LMP3',feedClass:'LMP3',color:null,cars:[{position:1,carNumber:'2',entryId:null,teamName:'A VP Team',vehicle:null,manufacturer:null,status:'CLASSIFIED',laps:5,
    gapToLeaderMs:null,gapToLeaderLaps:null,intervalMs:null,intervalLaps:null,driverOrder:1,driverName:'Some Driver',driverShortName:null,driverRating:'S',lastLap:5,lastLapMs:90_000,
    bestLap:4,bestLapMs:89_000,inPit:false,stintStartMs:null,stintLaps:null,energyPct:null,energyLapsLeft:null}]}]};
  const posts = [], puts = [], errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', async route => {
   const request = route.request(), url = new URL(request.url()), path = url.pathname;
   if (request.method() === 'POST' && path === '/api/live/connect') {
    posts.push(JSON.parse(request.postData()));
    status = {...status, desiredConnected:true, state:'LIVE', eventId:null, eventName:null, session:feedSession};
    return route.fulfill({json:status});
   }
   if (request.method() === 'PUT' && path === '/api/live/feed-events/612/event') {
    const body = JSON.parse(request.postData());
    puts.push(body);
    vp = body.none ? {...vp, boundBy:'ADMIN_NONE', eventId:null, eventName:null}
     : body.auto ? {...vp, boundBy:null, eventId:null, eventName:null}
     : {...vp, boundBy:'ADMIN', eventId:body.eventId, eventName:'Motul Petit LeMans'};
    return route.fulfill({json:vp});
   }
   const body = path === '/api/me' ? {email:null,isAdmin:true,authEnabled:true}
    : path === '/api/live/status' ? status
    : path === '/api/live/weekends' ? [{track:'Road Atlanta',fromMs:t0,toMs:t0+12_000_000,championships:[impc, vp]}]
    : path === '/api/live/feed-events/612' ? vp
    : path === '/api/live/timing' ? tower
    : path === '/api/live/sessions' ? (url.searchParams.get('feedEvent') === '612' ? vp.sessions : [])
    : path === '/api/live/drive-time' ? {sessionDbId:3189,eventId:null,rules:[],drivers:[]}
    : path === '/api/live/gaps' ? {sessionDbId:3189,classes:[]}
    : [];
   return route.fulfill({json:body});
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  await page.goto(`${base}/#/timing`);

  // One weekend, both series: IMPC filed automatically, VP not filed.
  await page.getByRole('heading',{name:/Road Atlanta/}).waitFor();
  const rows = page.locator('.timing-weekend-table tbody tr');
  assert.equal(await rows.count(), 2);
  assert.match(await rows.nth(0).innerText(), /IMSA Michelin Pilot Challenge[\s\S]*Fox Factory 120/);
  const vpSelect = page.getByLabel('Filed under, for IMSA VP Racing SportsCar Challenge');
  assert.equal(await vpSelect.inputValue(), 'auto');
  assert.match(await vpSelect.locator('option:checked').innerText(), /Automatic: not filed yet/);
  assert.match(await page.getByLabel('Filed under, for IMSA Michelin Pilot Challenge').locator('option:checked').innerText(), /Automatic: Fox Factory 120/);
  // Sessions in schedule order, the unfiled ones linked to their weekend page.
  assert.deepEqual(await rows.nth(1).locator('.timing-weekend-session').allInnerTexts(), ['Practice 2', 'Qualifying · live']);
  assert.equal(await rows.nth(1).getByRole('link',{name:'Practice 2'}).getAttribute('href'), '#/timing/weekend/612?view=gaps&session=3189');
  assert.equal(await rows.nth(0).getByRole('link',{name:'Practice 1'}).getAttribute('href'), '#/timing/340?view=gaps&session=3183');

  // Connecting from here binds no event.
  assert.match(await page.locator('.timing-head').innerText(), /Live timing is off/);
  await page.getByLabel('Live timing connection').getByRole('button',{name:'Connect',exact:true}).click();
  await page.getByText('On track:').waitFor({timeout:8000});
  assert.deepEqual(posts, [{}]);
  assert.match(await page.locator('.timing-head').innerText(), /IMSA VP Racing SportsCar Challenge · Qualifying\s*· not filed under a Pit Pass event/);

  // The admin says VP is not in Pit Pass, then files it under an event, then hands it back.
  await vpSelect.selectOption('none');
  await page.waitForFunction(() => document.querySelector('[aria-label="Filed under, for IMSA VP Racing SportsCar Challenge"]')?.value === 'none');
  await vpSelect.selectOption('339');
  await page.waitForFunction(() => document.querySelector('[aria-label="Filed under, for IMSA VP Racing SportsCar Challenge"]')?.value === '339');
  await vpSelect.selectOption('auto');
  await page.waitForFunction(() => document.querySelector('[aria-label="Filed under, for IMSA VP Racing SportsCar Challenge"]')?.value === 'auto');
  assert.deepEqual(puts, [{none:true},{eventId:339},{auto:true}]);

  // VP's own weekend page: its name, the tower as the feed has it, no drive-time rules to edit.
  await page.goto(`${base}/#/timing/weekend/612`);
  await page.locator('h1', {hasText:'IMSA VP Racing SportsCar Challenge · 29th Annual Motul Petit Le Mans'}).waitFor();
  assert.equal(await page.locator('.timing-back').getAttribute('href'), '#/timing');
  await page.getByRole('table',{name:'Running order by class'}).waitFor();
  assert.match(await page.locator('.timing-unfiled').innerText(), /Not filed under a Pit Pass event/);
  assert.match(await page.locator('.tower-row').first().innerText(), /A VP Team/);
  await page.goto(`${base}/#/timing/weekend/612?view=drive&session=3189`);
  await page.getByText('Drive-time rules belong to a Pit Pass event').waitFor();
  assert.equal(await page.getByRole('button',{name:/Edit rules|Add rules/}).count(), 0);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);

  assert.deepEqual(errors, []);
  console.log('timing home and weekend pages: ok');
 } finally { await browser.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
