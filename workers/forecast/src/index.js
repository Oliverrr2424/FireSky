import { REGIONS, slotFor } from '../../../functions/_shared/regions.js';
import { collectRegion } from '../../../functions/_shared/regionalForecast.js';
export async function runSchedule(env, now) {
 // A 10-minute recovery window covers missed ticks without replaying old forecasts.
 for (const region of REGIONS) {
  const slot=slotFor(region,now);
  if(now-slot<10*60000) await collectRegion(env,region,slot,Date.now());
 }
 // Bounded tables, retention after UTC midnight. Claims are retained for 35 days.
 if(new Date(now).getUTCHours()===0 && new Date(now).getUTCMinutes()<2) {
  await env.FIRESKY_DB.batch([
   env.FIRESKY_DB.prepare('DELETE FROM regional_forecasts WHERE sampled_at<?').bind(now-30*86400000),
   env.FIRESKY_DB.prepare('DELETE FROM regional_runs WHERE sampled_at<?').bind(now-35*86400000),
  ]);
 }
}
export default {
 async scheduled(event,env,ctx) { ctx.waitUntil(runSchedule(env,event.scheduledTime)); },
 async fetch() { return new Response('Scheduled regional forecasts only', {status:404}); }
};
