const {chromium} = require('playwright');
const assert = require('node:assert/strict');
(async () => {
 const { createServer } = await import('vite');
 const server = await createServer({ server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
 await server.listen();
 const base = `http://127.0.0.1:${server.httpServer.address().port}`;
 let browser;
 try { browser = await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,headless:true}); }
 catch (error) { await server.close(); throw error; }
 try {
  const page = await browser.newPage();
  const calls = [];
  const bodies = [];
  let failSheet = false;
  await page.route('**/api/**', async route => {
   const req=route.request(), path = new URL(req.url()).pathname;
   if (path === '/api/car-images/uploads/config') return route.fulfill({json:{directUpload:true}});
   if (path === '/api/logo-uploads') {
    calls.push({path,body:req.postDataJSON()});
    return route.fulfill({json:{id:'logo-ticket',uploadUrl:'https://bucket.example/logo'}});
   }
   if (path === '/api/logo-uploads/logo-ticket/complete') {
    calls.push({path,body:req.postDataJSON()});
    return route.fulfill({json:{logoUrl:'https://images.example/series-logos/id/logo'}});
   }
   if(path==='/api/car-images/uploads') {
    calls.push({path,body:req.postDataJSON()});
    return route.fulfill({json:{id:'ticket',originalUrl:'https://bucket.example/original',sheetUrl:'https://bucket.example/sheet'}});
   }
   if(path==='/api/car-images/uploads/ticket/complete') {
    calls.push({path,body:req.postDataJSON()});
    return route.fulfill({json:{id:123,replaced:false}});
   }
   return route.fulfill({json:path==='/api/me'?{email:null,role:'ADMIN',authEnabled:false}:[]});
  });
  await page.route('https://bucket.example/**', async route => {
   const req=route.request();
   bodies.push({url:req.url(),method:req.method(),type:req.headers()['content-type'],auth:req.headers()['authorization'],length:req.postDataBuffer()?.length,body:req.postDataBuffer()});
   return route.fulfill({status:failSheet && req.url().endsWith('/sheet') ? 503 : 200,headers:{'Access-Control-Allow-Origin':'*'}});
  });
  await page.goto(base);
  const result = await page.evaluate(async()=> {
   const {uploadCarImage} = await import('/src/lib/carImageUpload.ts');
   const canvas = document.createElement('canvas');canvas.width=2400;canvas.height=1200;
   const ctx=canvas.getContext('2d');ctx.fillStyle='red';ctx.fillRect(0,0,2400,1200);
   const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg'));
   const file=new File([blob],'023.jpg',{type:'image/jpeg'});
   const messages=[];
   const saved=await uploadCarImage(1,'023',file,message=>messages.push(message));
   let invalid;
   try {await uploadCarImage(1,'023',new File(['<svg/>'],'bad.svg',{type:'image/svg+xml'}),()=>{});}catch(e){invalid=e.message;}
   return {saved,messages,originalSize:file.size,invalid};
  });
  assert.equal(result.saved.id,123);assert.equal(calls.length,2);assert.equal(bodies.length,2);
  assert.equal(calls[0].body.originalSize,result.originalSize);
  assert.equal(bodies[0].length,result.originalSize);assert.equal(bodies[1].length,calls[0].body.sheetSize);
  assert.equal(bodies[1].type,'image/webp');assert.equal(bodies[0].auth,undefined);
  assert.ok(result.invalid.includes('JPEG'));assert.equal(result.messages.length,3);
  // Inspect the actual worker output dimensions independently.
  const dimensions=await page.evaluate(async()=>{
   const canvas=document.createElement('canvas');canvas.width=1200;canvas.height=2400;
   const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));
   const worker=new Worker('/src/workers/resizeCarImage.ts',{type:'module'});
   const result=await new Promise((resolve,reject)=>{worker.onmessage=e=>resolve(e.data);worker.onerror=reject;worker.postMessage(new File([blob],'portrait.png',{type:'image/png'}));});
   worker.terminate();const bitmap=await createImageBitmap(result.blob);const size=[bitmap.width,bitmap.height,result.blob.type];bitmap.close();return size;
  });
  assert.deepEqual(dimensions,[200,400,'image/webp']);
  failSheet = true;
  const completionsBefore = calls.filter(call => call.path.endsWith('/complete')).length;
  const failure = await page.evaluate(async () => {
    const { uploadCarImage } = await import('/src/lib/carImageUpload.ts');
    const canvas = document.createElement('canvas'); canvas.width = 8; canvas.height = 8;
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    try { await uploadCarImage(1, '023', new File([blob], '023.png', {type:'image/png'}), () => {}); }
    catch (error) { return error.message; }
  });
  assert.ok(failure.includes('503'));
  assert.equal(calls.filter(call => call.path.endsWith('/complete')).length, completionsBefore);
  const logos = await page.evaluate(async () => {
    const { uploadLogo } = await import('/src/lib/logoUpload.ts');
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4"><path d="M0 0h4v4H0z"/></svg>';
    const messages = [];
    await uploadLogo('SERIES', '1', new File([svg], 'logo.svg', {type:'image/svg+xml'}), m => messages.push(m));
    const canvas = document.createElement('canvas'); canvas.width = 2048; canvas.height = 1024;
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    await uploadLogo('MANUFACTURER', 'Porsche', new File([blob], 'logo.png', {type:'image/png'}), () => {});
    const worker = new Worker('/src/workers/resizeCarImage.ts', {type:'module'});
    const resized = await new Promise((resolve, reject) => {
      worker.onmessage = e => resolve(e.data); worker.onerror = reject;
      worker.postMessage({file: new File([blob], 'logo.png', {type:'image/png'}), maxSize:1024});
    });
    worker.terminate();
    const bitmap = await createImageBitmap(resized.blob);
    const dimensions = [bitmap.width, bitmap.height]; bitmap.close();
    return {svg, messages, dimensions};
  });
  const logoPuts = bodies.filter(body => body.url.endsWith('/logo'));
  assert.equal(logoPuts.length, 2);
  assert.equal(logoPuts[0].body.toString(), logos.svg);
  assert.equal(logoPuts[0].type, 'image/svg+xml');
  assert.equal(logoPuts[1].type, 'image/webp');
  assert.equal(logoPuts[1].auth, undefined);
  assert.deepEqual(logos.dimensions, [1024, 512]);
  assert.ok(!logos.messages.some(message => message.includes('Resizing')));
  await page.evaluate(async () => {
    const { uploadLogo } = await import('/src/lib/logoUpload.ts');
    const canvas = document.createElement('canvas'); canvas.width = 1600; canvas.height = 800;
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    await uploadLogo('DRIVER', '42', new File([blob], 'driver.png', {type:'image/png'}), () => {});
  });
  const headshot = bodies.at(-1);
  assert.equal(headshot.type, 'image/webp');
  assert.equal(headshot.auth, undefined);
  const headshotSize = await page.evaluate(async (bytes) => {
    const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], {type:'image/webp'}));
    const size = [bitmap.width, bitmap.height]; bitmap.close(); return size;
  }, Array.from(headshot.body));
  assert.deepEqual(headshotSize, [640, 320]);
  assert.equal(calls.filter(c => c.path === '/api/logo-uploads').at(-1).body.kind, 'DRIVER');
  console.log(JSON.stringify({passed:true,checks:['driver headshot resized to 640px and uploaded directly','worker creates 200x400 WebP','original and variant PUT directly to bucket','backend receives JSON only','declared byte sizes match uploads','no credentials sent to bucket','SVG rejected for car photos','SVG logos preserved byte-for-byte','raster logos resized to 1024px in browser','failed PUT never completes or changes the image'],result},null,2));
 } finally {await browser.close();await server.close();}
})().catch(e=>{console.error(e);process.exit(1)});
