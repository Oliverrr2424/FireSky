import { jsonResponse, errorResponse } from '../_shared/openMeteo.js';
import { regionFor } from '../_shared/regions.js';
import { VERSION } from '../_shared/regionalForecast.js';
export async function onRequestGet({request,env}) {
 const q=new URL(request.url).searchParams;
 const latitude=Number(q.get('lat')),longitude=Number(q.get('lon')),eventDate=q.get('date'),mode=q.get('mode');
 if (!q.has('lat')||!q.has('lon')||!Number.isFinite(latitude)||!Number.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180||!/^\d{4}-\d{2}-\d{2}$/.test(eventDate||'')||!['sunrise','sunset'].includes(mode)) return errorResponse('Valid coordinates, local event date and mode required',400);
 const match=regionFor({latitude,longitude});
 if (!match) return jsonResponse({status:'outside_coverage',snapshots:[]});
 const {region,distance}=match;
 const metadata={id:region.id,name:region.name,latitude:region.latitude,longitude:region.longitude,radiusKm:region.radiusKm,distanceKm:Math.round(distance),resolution:'city-representative',intervalHours:3};
 if (!env.FIRESKY_DB) return errorResponse('Regional history is not configured',503);
 const key=`regional:history:${VERSION}:${region.id}:${eventDate}:${mode}`;
 try {
  let data;
  if(env.FIRESKY_CACHE) { try { data=await env.FIRESKY_CACHE.get(key,'json'); } catch {} }
  if(!data) {
   const rows=await env.FIRESKY_DB.prepare(`SELECT sampled_at,event_at,probability,quality,model_version FROM regional_forecasts
   WHERE region_id=? AND event_date=? AND mode=? AND model_version=? ORDER BY sampled_at DESC LIMIT 64`).bind(region.id,eventDate,mode,VERSION).all();
   const snapshots=(rows.results||[]).reverse().map(r=>({mode,eventAt:r.event_at,latitude:region.latitude,longitude:region.longitude,calculatedAt:r.sampled_at,probability:r.probability,quality:r.quality,modelVersion:r.model_version,regionName:region.name}));
   data={snapshots};
   if(env.FIRESKY_CACHE) { try { await env.FIRESKY_CACHE.put(key,JSON.stringify(data),{expirationTtl:300}); } catch {} }
  }
  const latest=data.snapshots.at(-1)?.calculatedAt;
  return jsonResponse({...data,region:metadata,status:!latest?'collecting':Date.now()-latest>7*3600000?'stale':'ready',updatedAt:latest??null},{headers:{'Cache-Control':'public, max-age=300'}});
 } catch(error) { console.error('regional-history-read',String(error));return errorResponse('Regional history temporarily unavailable',503); }
}
