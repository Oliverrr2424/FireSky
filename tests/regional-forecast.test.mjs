import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {REGIONS,regionFor,slotFor} from '../functions/_shared/regions.js';
import {reservationUnits,reserveRun,scoreBundle,normalizeTimes,collectRegion,VERSION} from '../functions/_shared/regionalForecast.js';
import {onRequestGet} from '../functions/api/regional-history.js';
function database() {
 const sql=new DatabaseSync(':memory:');sql.exec(readFileSync(new URL('../migrations/0005_regional_forecasts.sql',import.meta.url),'utf8'));
 const wrap=(q,args=[])=>({bind:(...a)=>wrap(q,a),run:async()=>({meta:sql.prepare(q).run(...args)}),all:async()=>({results:sql.prepare(q).all(...args)})});
 return {prepare:q=>wrap(q),batch:async items=>Promise.all(items.map(x=>x.run())),sql};
}
const now=Date.parse('2026-10-21T00:00:00Z');
function fixture() {
 const time=Array.from({length:72},(_,i)=>(now+i*3600000)/1000);
 const hourly={time};
 for(const key of ['cloud_cover','cloud_cover_low','cloud_cover_mid','cloud_cover_high','relative_humidity_2m','precipitation_probability','visibility']) hourly[key]=time.map(()=>key==='visibility'?20000:30);
 const weather={latitude:43.65,longitude:-79.38,timezone:'America/Toronto',hourly,daily:{time:[now/1000+4*3600],sunrise:[now/1000+11*3600],sunset:[now/1000+22*3600]}};
 const air={timezone:'America/Toronto',hourly:{time,us_aqi:time.map(()=>20),pm2_5:time.map(()=>5)}};
 return {weather,air};
}
test('80 stable regions, exactly 8 slots each day, 3200 reserved units, evenly staggered',()=>{
 assert.equal(REGIONS.length,80);assert.equal(new Set(REGIONS.map(r=>r.id)).size,80);
 assert.equal(REGIONS.reduce((n,r)=>n+8*reservationUnits(r),0),3200);
 const slots=[];for(const r of REGIONS) {assert.equal(reservationUnits(r),5);for(let i=0;i<8;i++)slots.push(slotFor(r,now+r.offsetMinutes*60000+i*180*60000));}
 assert.equal(new Set(slots).size,640);
});
test('bounded coverage and stable matching',()=>{
 assert.equal(regionFor({latitude:43.7,longitude:-79.4}).region.id,'toronto');
 assert.equal(regionFor({latitude:0,longitude:0}),null);
});
test('atomic claims: duplicate blocked, failed claims charged, cap 4000 and UTC rollover',async()=>{
 const db=database();const r=REGIONS[0];assert.equal(await reserveRun(db,r,now,now),true);
 assert.equal(await reserveRun(db,r,now,now),false);
 for(let i=1;i<800;i++)assert.equal(await reserveRun(db,r,now+i,now),true);
 assert.equal(await reserveRun(db,r,now+800,now),false);
 assert.equal(await reserveRun(db,r,now+86400000,now+86400000),true);
});
test('one input bundle scores both events; past, missing and out-of-horizon events are excluded',()=>{
 const raw=fixture(),bundle={weather:normalizeTimes(raw.weather),air:normalizeTimes(raw.air)};
 const results=scoreBundle(bundle,now);assert.equal(results.length,2);assert.equal(results[0].eventDate,'2026-10-21');assert.equal(results[0].eventAt,now+11*3600000);
 assert.equal(results[0].modelVersion,VERSION);assert.equal(scoreBundle(bundle,now+12*3600000).length,1);
 bundle.weather.hourly.cloud_cover_low=[];assert.equal(scoreBundle(bundle,now).length,0);
});
test('unix event timestamps survive DST boundaries',()=>{
 const p=fixture().weather;p.daily.time=[Date.parse('2026-11-01T04:00:00Z')/1000];p.daily.sunrise=[Date.parse('2026-11-01T12:00:00Z')/1000];
 const n=normalizeTimes(p);assert.equal(n.daily.time[0],'2026-11-01');assert.equal(n.daily.sunrise[0],'2026-11-01T12:00:00');
});
test('collector + public API: two upstream calls, dedup, date/mode isolation, no upstream on history reads',async()=>{
 const db=database(),region=REGIONS.find(r=>r.id==='toronto');const raw=fixture();let calls=0;
 const original=globalThis.fetch;
 globalThis.fetch=async url=>{calls++;return Response.json(String(url).includes('air-quality')?raw.air:raw.weather);};
 try {
  const env={FIRESKY_DB:db};assert.equal(await collectRegion(env,region,now,now),'complete');assert.equal(calls,2);
  assert.equal(await collectRegion(env,region,now,now),'skipped');assert.equal(calls,2);
  const req=date=>new Request(`https://example.test/api/regional-history?lat=43.65&lon=-79.38&date=${date}&mode=sunset`);
  const result=await (await onRequestGet({request:req('2026-10-21'),env})).json();assert.equal(result.snapshots.length,1);assert.equal(result.region.name,'Toronto');
  assert.equal((await (await onRequestGet({request:req('2026-10-22'),env})).json()).snapshots.length,0);assert.equal(calls,2);
 } finally {globalThis.fetch=original;}
});
test('upstream failure consumes reservation, does not write fabricated history or retry',async()=>{
 const db=database();const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;return new Response('',{status:429});};
 try {assert.equal(await collectRegion({FIRESKY_DB:db},REGIONS[0],now,now),'failed');assert.equal(calls,1);assert.equal(db.sql.prepare('SELECT SUM(units) n FROM regional_runs').get().n,5);assert.equal(db.sql.prepare('SELECT COUNT(*) n FROM regional_forecasts').get().n,0);}finally{globalThis.fetch=original;}
});
test('shared history cache serves nearby coordinates; cache failure falls back to durable data',async()=>{
 const db=database(), cache=new Map();let reads=0;
 const prepare=db.prepare;db.prepare=q=>{if(q.startsWith('SELECT sampled_at'))reads++;return prepare(q);};
 const env={FIRESKY_DB:db,FIRESKY_CACHE:{get:async key=>cache.get(key),put:async(key,value)=>cache.set(key,JSON.parse(value))}};
 const request=lat=>new Request(`https://example.test/api/regional-history?lat=${lat}&lon=-79.38&date=2026-10-21&mode=sunset`);
 const first=await (await onRequestGet({request:request(43.65),env})).json();
 const second=await (await onRequestGet({request:request(43.70),env})).json();
 assert.equal(first.status,'collecting');assert.equal(second.region.id,first.region.id);assert.equal(reads,1);
 assert.notEqual(first.region.distanceKm,second.region.distanceKm);
 env.FIRESKY_CACHE={get:async()=>{throw new Error('cache offline');},put:async()=>{throw new Error('cache offline');}};
 assert.equal((await onRequestGet({request:request(43.65),env})).status,200);assert.equal(reads,2);
});
