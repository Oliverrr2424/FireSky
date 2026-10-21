import { makeForecastUrl, makeAirUrl, fetchOpenMeteoJson, isUsableWeatherPayload } from './openMeteo.js';
import { pickWindow, computeWindowScore, nearestIndex, localIsoToUtcDate } from './scoring.js';
export const VERSION = 'regional-point-rules-v1';
export const DAILY_BUDGET = 4000;
// Count every requested variable, including daily/current, conservatively rounding up.
export function requestUnits(url) {
 const p=new URL(url).searchParams;
 const count=['hourly','daily','current','minutely_15'].reduce((n,k)=>n+(p.get(k)?.split(',').filter(Boolean).length||0),0);
 const locations=p.get('latitude').split(',').length;
 return locations*Math.max(1,Math.ceil(count/10))*Math.max(1,Math.ceil(Number(p.get('forecast_days')||7)/14));
}
export function sampleUrls(region) {
 return [makeForecastUrl(region), makeAirUrl(region)].map(value=>{
  const url=new URL(value);url.searchParams.set('timeformat','unixtime');return url.toString();
 });
}
export function normalizeTimes(payload) {
 const zone=payload.timezone;
 const date=value=>new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(value*1000));
 const instant=value=>value==null||value===0?null:new Date(value*1000).toISOString().slice(0,19);
 return {...payload,utc_offset_seconds:0,
  hourly:{...payload.hourly,time:payload.hourly.time.map(instant)},
  ...(payload.daily?{daily:{...payload.daily,time:payload.daily.time.map(date),sunrise:payload.daily.sunrise.map(instant),sunset:payload.daily.sunset.map(instant)}}:{}),
 };
}
export function reservationUnits(region) { return sampleUrls(region).reduce((n,u)=>n+requestUnits(u),0); }
export async function reserveRun(db, region, slot, now) {
 const units=reservationUnits(region), day=new Date(now).toISOString().slice(0,10);
 const result=await db.prepare(`INSERT OR IGNORE INTO regional_runs(region_id,slot,budget_day,units,sampled_at)
 SELECT ?,?,?,?,? WHERE COALESCE((SELECT SUM(units) FROM regional_runs WHERE budget_day=?),0)+?<=?`)
 .bind(region.id,slot,day,units,now,day,units,DAILY_BUDGET).run();
 return result.meta.changes===1;
}
export async function fetchBundle(region) {
 const [weatherUrl,airUrl]=sampleUrls(region);
 // No hidden retries. Each claimed slot reserves both requests, even on failure.
 const weather=await fetchOpenMeteoJson(weatherUrl,'Regional weather',12000,{maxAttempts:1,validate:isUsableWeatherPayload});
 const air=await fetchOpenMeteoJson(airUrl,'Regional air',12000,{maxAttempts:1});
 if (!air?.hourly?.time?.length) throw new Error('Missing regional air data');
 return {weather:normalizeTimes(weather),air:normalizeTimes(air)};
}
export function scoreBundle(bundle, sampledAt) {
 const {weather,air}=bundle, results=[];
 for (let i=0;i<weather.daily.time.length;i++) {
  for (const mode of ['sunrise','sunset']) {
   const value=weather.daily[mode]?.[i];
   if (!value) continue; // Polar regions may not have this solar event.
   const event=localIsoToUtcDate(value,weather.utc_offset_seconds||0);
   if (!Number.isFinite(+event)||+event<=sampledAt) continue;
   // Reject events outside the hourly horizon rather than reusing the last hour.
   const first=localIsoToUtcDate(weather.hourly.time[0],weather.utc_offset_seconds||0);
   const last=localIsoToUtcDate(weather.hourly.time.at(-1),weather.utc_offset_seconds||0);
   if (+event<+first||+event>+last) continue;
   const ai=nearestIndex(air.hourly.time,event,air.utc_offset_seconds||0);
   const airAt=localIsoToUtcDate(air.hourly.time[ai],air.utc_offset_seconds||0);
   if (Math.abs(+airAt-+event)>90*60000) continue;
   const snapshot=Object.fromEntries(Object.entries(air.hourly).filter(([k])=>k!=='time').map(([k,v])=>[k,v[ai]]));
   const window=pickWindow(weather.hourly,event,mode,weather.utc_offset_seconds||0);
   if (['cloudTotal','cloudLow','cloudMid','cloudHigh','precipProbability','visibility'].some(k=>window[k]==null)) continue;
   const score=computeWindowScore(window,snapshot,mode);
   if (![score.probability,score.quality].every(Number.isFinite)) continue;
   results.push({eventDate:weather.daily.time[i],mode,eventAt:+event,calculatedAt:sampledAt,
    probability:score.probability,quality:score.quality,modelVersion:VERSION});
  }
 }
 return results;
}
export async function collectRegion(env,region,slot,now) {
 if (!await reserveRun(env.FIRESKY_DB,region,slot,now)) return 'skipped';
 try {
  const bundle=await fetchBundle(region);
  const scores=scoreBundle(bundle,now);
  if (!scores.length) throw new Error('No valid future event scores');
  await env.FIRESKY_DB.batch(scores.map(s=>env.FIRESKY_DB.prepare(`INSERT OR IGNORE INTO regional_forecasts
   (region_id,event_date,mode,sampled_at,event_at,probability,quality,model_version) VALUES(?,?,?,?,?,?,?,?)`)
   .bind(region.id,s.eventDate,s.mode,now,s.eventAt,s.probability,s.quality,VERSION)));
  // Cache raw inputs for diagnosis/recomputation. Public history reads never fetch weather.
  if (env.FIRESKY_CACHE) {
   try {
    await env.FIRESKY_CACHE.put(`regional:raw:${VERSION}:${region.id}:${slot}`,JSON.stringify({sampledAt:now,...bundle}),{expirationTtl:2*86400});
    await env.FIRESKY_CACHE.put(`regional:latest:${VERSION}:${region.id}`,JSON.stringify({sampledAt:now,scores}),{expirationTtl:7*86400});
   } catch(error) { console.warn('regional-cache-write',region.id,String(error)); }
  }
  await env.FIRESKY_DB.prepare("UPDATE regional_runs SET status='complete' WHERE region_id=? AND slot=?").bind(region.id,slot).run();
  return 'complete';
 } catch(error) {
  await env.FIRESKY_DB.prepare("UPDATE regional_runs SET status='failed',error=? WHERE region_id=? AND slot=?").bind(String(error).slice(0,400),region.id,slot).run();
  console.error('regional-sample-failed',region.id,String(error));
  return 'failed';
 }
}
