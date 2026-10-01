const { chromium } = require('playwright');
const assert = require('node:assert/strict');
// The shareable timing link (#/live/<token>) against a stubbed API, signed out:
// every API call carries the token, nothing admin shows, links stay under the
// link, and a revoked link says so instead of bouncing to sign-in.
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true});
 try {
  const page = await browser.newPage({viewport:{width:390,height:844}});
  const TOKEN = 'Abc_123-xyz';
  const t0 = Date.UTC(2026, 8, 30, 16, 25);
  const session = (id, name, current=false, eventId=null) => ({sessionDbId:id,eventId,name,type:'FREE_PRACTICE',dateMs:t0+id,laps:100,cars:17,current});
  const impc = {feedEventDbId:611,champDbId:612,champName:'IMSA Michelin Pilot Challenge',feedEventName:'Fox Factory 120',track:'Road Atlanta',
   eventId:340,eventName:'Fox Factory 120 (Oct 2)',boundBy:'AUTO',boundByEmail:null,firstSessionMs:t0,lastSessionMs:t0,sessions:[session(3183,'Practice 1',false,340)],candidates:[]};
  const vp = {feedEventDbId:612,champDbId:613,champName:'IMSA VP Racing SportsCar Challenge',feedEventName:'29th Annual Motul Petit Le Mans',track:'Road Atlanta',
   eventId:null,eventName:null,boundBy:null,boundByEmail:null,firstSessionMs:t0+4_000_000,lastSessionMs:t0+4_000_000,sessions:[session(3189,'Practice 2')],candidates:[]};
  const feedSession = {championship:impc.champName,event:'Fox Factory 120',name:'Practice 1',type:'FREE_PRACTICE',flag:'GREEN',running:true,finished:false,feedEventDbId:611};
  const status = {state:'LIVE',configured:true,desiredConnected:true,eventId:null,eventName:null,filedEventId:340,filedEventName:'Fox Factory 120 (Oct 2)',lastError:null,session:feedSession};
  const tower = {state:'LIVE',eventId:null,eventName:null,filedEventId:340,filedEventName:'Fox Factory 120 (Oct 2)',session:feedSession,sessionDbId:3183,feedClockMs:t0,matched:1,total:1,classes:[
   {className:'GS',feedClass:'GS',color:null,cars:[{position:1,carNumber:'2',entryId:9,teamName:'An IMPC Team',vehicle:null,manufacturer:null,status:'CLASSIFIED',laps:5,
    gapToLeaderMs:null,gapToLeaderLaps:null,intervalMs:null,intervalLaps:null,driverOrder:1,driverName:'Some Driver',driverShortName:null,driverRating:'S',lastLap:5,lastLapMs:90_000,
    bestLap:4,bestLapMs:89_000,inPit:false,stintStartMs:null,stintLaps:null,energyPct:null,energyLapsLeft:null}]}]};
  let revoked = false;
  const seen = [], errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', async route => {
   const request = route.request(), url = new URL(request.url()), path = url.pathname;
   seen.push({path, header: request.headers()['x-pit-pass-share'] ?? null});
   if (path === '/api/me') return route.fulfill({json:{email:null,isAdmin:false,authEnabled:true}});
   if (revoked) return route.fulfill({status:401, json:{status:401}});
   const body = path === '/api/live/status' ? status
    : path === '/api/live/weekends' ? [{track:'Road Atlanta',fromMs:t0,toMs:t0+4_000_000,championships:[impc, vp]}]
    : path === '/api/live/feed-events/611' ? impc
    : path === '/api/live/timing' ? tower
    : path === '/api/live/sessions' ? impc.sessions
    : null;
   return body == null ? route.fulfill({status:403, json:{status:403}}) : route.fulfill({json:body});
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  await page.goto(`${base}/#/live/${TOKEN}`);

  // The home: weekends, read-only, every link under the link.
  await page.getByRole('heading',{name:/Road Atlanta/}).waitFor();
  assert.equal(await page.getByLabel('Live timing connection').count(), 0, 'no connect switch');
  assert.equal(await page.locator('select').count(), 0, 'no filing control');
  assert.equal(await page.getByText('← Pit Pass').count(), 0, 'no way into the rest of Pit Pass');
  const links = await page.locator('a').evaluateAll(as => as.map(a => a.getAttribute('href')));
  assert.ok(links.length > 0);
  for (const href of links) assert.ok(href.startsWith(`#/live/${TOKEN}`), `stays under the link: ${href}`);
  assert.ok(links.includes(`#/live/${TOKEN}/weekend/611?view=gaps&session=3183`), 'a filed series opens as its weekend');
  assert.match(await page.locator('.timing-head').innerText(), /On track:[\s\S]*filed under Fox Factory 120/);
  assert.match(await page.locator('.timing-credit').innerText(), /Al Kamel Systems/);

  // A weekend page: the tower, with a way back home and nowhere else.
  await page.getByRole('link',{name:'IMSA Michelin Pilot Challenge',exact:true}).click();
  await page.getByRole('table',{name:'Running order by class'}).waitFor();
  assert.equal(await page.locator('.timing-back').getAttribute('href'), `#/live/${TOKEN}`);
  assert.match(await page.locator('.tower-row').first().innerText(), /Some Driver/); // the team column folds away at phone width
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'phone width, no sideways scroll');

  // Every API call but the public /api/me carried the token.
  const api = seen.filter(s => s.path !== '/api/me');
  assert.ok(api.length > 3);
  for (const s of api) assert.equal(s.header, TOKEN, `token on ${s.path}`);
  assert.equal(seen.some(s => s.header && s.header !== TOKEN), false);

  // Revoked: the next poll's 401 shows a message, not the sign-in screen.
  revoked = true;
  await page.getByText('This timing link no longer works').waitFor({timeout:8000});
  assert.match(page.url(), /#\/live\//, 'still on the shared page');
  assert.match(await page.locator('.timing-credit').innerText(), /Al Kamel Systems/);

  assert.deepEqual(errors, []);
  console.log('shared timing link: ok');
 } finally { await browser.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
