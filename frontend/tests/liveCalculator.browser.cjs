const { chromium } = require('playwright');
const assert = require('node:assert/strict');
// Live mode of the web calculator against a stubbed API: it follows the shared
// connection but never manages it, scores every standings row from the live
// positions, and steps aside when the feed is off or scoring another season.
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
 await server.listen();
 const browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true});
 try {
  const page = await browser.newPage({viewport:{width:1280,height:900}});
  const base = {groupTitle:'IMSA WeatherTech',className:'GTP',isCup:false,year:2026,seasonId:1,seriesName:'IMSA',rowCount:3};
  const championships = [{...base,id:1,kind:'TEAMS',title:'GTP Teams'},{...base,id:2,kind:'MANUFACTURERS',title:'GTP Manufacturers'},
   {...base,id:3,kind:'TEAMS',title:'Endurance Cup GTP',isCup:true,groupTitle:'Michelin Endurance Cup'}];
  const events = [{id:11,name:'Daytona',roundOrdinal:1},{id:22,name:'Road Atlanta',roundOrdinal:2}];
  const rounds = events.map((e,i)=>({round:i+1,eventId:e.id,venue:e.name,sessions:[],races:[],raceCount:1}));
  const row = (competitorKey, position, totalPoints, extra={}) => ({competitorKey,competitorName:null,carNumber:null,teamName:null,position,totalPoints,pointsByRound:{1:totalPoints},sessionPoints:{},cells:{},...extra});
  const recaps = {
   1:{championship:{...championships[0],family:'IMSA'},rounds,rows:[row('6',1,1000,{carNumber:'6',teamName:'Porsche Penske Motorsport'}),row('31',2,990,{carNumber:'31',teamName:'Cadillac Whelen'}),row('10',3,900,{carNumber:'10',teamName:'Cadillac WTR'})]},
   2:{championship:{...championships[1],family:'IMSA'},rounds,rows:[row('Porsche',1,1000),row('Cadillac',2,990),row('BMW',3,900)]}};
  const running = (position, carNumber, gapToLeaderMs=null) => ({position,carNumber,teamName:null,status:'CLASSIFIED',laps:70,gapToLeaderMs,gapToLeaderLaps:null});
  const session = {championship:'IMSA WeatherTech SportsCar Championship',event:'Petit Le Mans',name:'Race',type:'RACE',flag:'FULL_YELLOW',running:true,finished:false};
  const live = {
   1:{kind:'TEAMS',rows:[{competitorKey:'6',live:running(2,'6',1830),qualifyingPosition:3},{competitorKey:'31',live:running(1,'31'),qualifyingPosition:1},{competitorKey:'10',live:null,qualifyingPosition:null}],newcomers:[{name:'Late Entry Racing',carNumber:'99',position:3}]},
   2:{kind:'MANUFACTURERS',rows:[{competitorKey:'Porsche',live:running(2,'6',1830),qualifyingPosition:2},{competitorKey:'Cadillac',live:running(1,'31'),qualifyingPosition:1},{competitorKey:'BMW',live:null,qualifyingPosition:null}],newcomers:[]}};
  let status = {state:'LIVE',configured:true,replaying:false,desiredConnected:true,eventId:22,eventName:'Road Atlanta',lastError:null,session};
  const writes=[], errors=[];
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/**', route => {
   const request = route.request(), path = new URL(request.url()).pathname, id = Number(path.split('/')[3]);
   if(!['GET','HEAD','OPTIONS'].includes(request.method())) writes.push(path);
   const body = path === '/api/me' ? {email:null,role:'VIEWER',authEnabled:false}
    : path === '/api/seasons/1' ? {id:1,year:2026,seriesId:1,seriesName:'IMSA',events,championships,entryClasses:['GTP'],kind:'MAIN'}
    : path === '/api/live/status' ? status
    : /^\/api\/live\/championships\/\d+$/.test(path) ? {state:status.state,eventId:22,session,championshipId:Number(path.split('/')[4]),className:'GTP',livePhase:'RACE',qualifyingImported:true,...live[Number(path.split('/')[4])]}
    : /calculator$/.test(path) ? recaps[id]
    : path.endsWith('/class-styles') ? {styles:[],unconfiguredClasses:[]} : [];
   return route.fulfill({json:body});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/seasons/1/calculator`);

  // Follows the connection, never manages it: that is the iPad's job for now.
  const bar = page.locator('.calculator-live-status');
  await bar.waitFor();
  assert.match(await bar.innerText(), /Live · Race · Full yellow/);
  assert.match(await bar.innerText(), /Scoring Road Atlanta/);
  assert.equal(await page.getByRole('button',{name:/connect/i}).count(), 0);
  // Scenario stays the default; Live is a choice.
  assert.equal(await page.getByLabel('Add team').count(), 1);
  await page.getByRole('button',{name:'Live',exact:true}).click();

  // Teams: every row, scored from live race + imported qualifying positions.
  const teams = page.getByRole('region',{name:'GTP live teams',exact:true});
  await teams.locator('tbody tr').first().waitFor();
  assert.deepEqual(await teams.locator('tbody th').allTextContents(), ['#31 · Cadillac Whelen','#6 · Porsche Penske Motorsport','#10 · Cadillac WTR']);
  assert.deepEqual(await teams.locator('.calculator-total').allTextContents(), [String(990+35+350), String(1000+30+320), '900']);
  assert.deepEqual(await teams.locator('tbody td:nth-child(2)').allTextContents(), ['▲1','▼1','–']);
  assert.match(await teams.locator('tbody tr').nth(1).innerText(), /#6 · \+1\.830/);
  assert.match(await teams.locator('tbody tr').nth(2).innerText(), /Not running/);
  assert.match(await teams.innerText(), /Late Entry Racing · #99 · P3/);
  assert.equal(await teams.locator('select, input').count(), 0, 'read-only: nothing to set');

  // Manufacturers, and no Endurance Cup among the kinds.
  assert.deepEqual(await page.getByRole('group',{name:'Championship kind'}).getByRole('button').allTextContents(), ['Teams','Manufacturers']);
  await page.getByRole('button',{name:'Manufacturers',exact:true}).click();
  const makes = page.getByRole('region',{name:'GTP live manufacturers',exact:true});
  await makes.locator('tbody tr').first().waitFor();
  assert.deepEqual(await makes.locator('tbody th').allTextContents(), ['Cadillac','Porsche','BMW']);
  assert.deepEqual(await makes.locator('.calculator-total').allTextContents(), ['1375','1352','900']);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);

  // The feed moves to another season's event: say so, show no table.
  status = {...status, eventId:999, eventName:'Somewhere Else'};
  await page.getByText('which is not an event of this season').waitFor({timeout:9000});
  assert.equal(await page.locator('.calculator-live-table').count(), 0);
  // Switched off from the iPad: the page follows within a poll.
  status = {...status, desiredConnected:false, state:'OFF'};
  await page.getByText('Live timing is off. Once it is connected').waitFor({timeout:9000});
  assert.match(await bar.innerText(), /Live timing is off/);

  assert.deepEqual(writes,[]); assert.deepEqual(errors,[]);
  console.log('Live calculator browser tests passed: read-only status, teams and manufacturers projections, movement, newcomers, no cup, other-season and off states, no writes.');
 } finally { await browser.close(); await server.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
