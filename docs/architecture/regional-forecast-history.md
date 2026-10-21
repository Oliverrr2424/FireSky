# Regional forecast history v1

Implemented and deployed on 2026-10-21. Release source: master at 9d788d0. Production database migrations, both Pages sites and the scheduled collector are deployed; see docs/mobile/release-2026-10-21.md.

## Product contract

80 fixed city representatives in `functions/_shared/regions.js`, covering selected population centres in Canada, the US, Mexico, plus San Juan and Honolulu. Coverage radii are explicit (70–220 km), with smaller radii on coasts/mountains. These radii are product coverage bounds, NOT validated weather accuracy. Gaps outside them intentionally return `outside_coverage`. No claim of continuous North American coverage.

Nearest eligible representative wins deterministically. Histories are labelled with city and sample distance. They use a versioned, centre-point rules score; they are not the user's exact local forecast, the ML score, or a 225-point directional-cloud score. No interpolation or invented history. A coarse regional trend must not be appended to local observations/scores. Validate representative accuracy before widening radii.

## Budget and schedule

Each region has a stable UTC offset: index × 2 minutes in a 180-minute cycle. 80 tasks spread over the first 160 minutes, eight samples per region per UTC day. Sunrise and sunset for all usable future dates use the SAME seven-day weather/air bundle.

Weather: 21 hourly + 4 daily + 5 current variables -> reserve ceil(30/10) = 3 units.
Air: 9 hourly + 5 current variables -> reserve ceil(14/10) = 2 units.
Each location/time range <= 14 days, default model selection. Conservative reservation = 5 units/sample.
80 × 8 × 5 = **3,200 reserved units/day**, 1,280 physical HTTP requests/day. Normal average = 133.3 units/hour. No per-slot retries. Remaining 800 units are safety headroom, not silently spent.

This estimator conservatively counts all variable groups. It is not supplier billing telemetry; compare against provider accounting when available. Official references:
- https://open-meteo.com/en/pricing
- https://github.com/open-meteo/open-meteo/issues/1295

D1 single-statement conditional INSERT atomically checks daily sum <= 4,000 and claims UNIQUE(region_id,slot). Concurrent/replayed cron invocations cannot claim twice. Failed/abandoned runs remain charged. DB unavailable => fail closed before upstream requests. No public route can start collection. Disable by removing the cron trigger. All collector instances MUST share the same D1 database.

The 4,000 limit applies to this BACKGROUND collector, not all FireSky traffic. Existing interactive forecast/grid/geocode endpoints retain their existing budgets/caches. Their 225-point grid misses remain a separate quota risk; monitor total application use before promising a reserved 6,000 units to users. This implementation does not change the main map's accuracy or silently route it to coarse data.

## Data correctness

Request Unix timestamps and normalize to UTC for scoring; preserve daily local date in the source timezone. This avoids applying today's UTC offset to forecasts across DST changes. Skip past/missing solar events and events beyond available weather or air hours. Required clouds/visibility/precipitation must exist. Air request failure skips this batch rather than mixing degraded and complete scores.

Shared web rules were extracted unchanged into `functions/_shared/scoring.js`. History uses `regional-point-rules-v1` and no ML inference, so model availability cannot cause false changes. Bump the version when rules/input/method change; public reads only return one version. `sampled_at` is collection time, not upstream issuance time; UI must not call it model-run time. Equal values at two sampling times are valid repeated estimates, not proof of a new model run.

Key: region_id + target local date + sunrise/sunset + collection time + scoring version. Original point-event millisecond equality is intentionally not used across nearby coordinates. Never backfill past samples using today's weather.

## Storage / caches

D1: durable budget claims, run outcomes, event snapshots. 30-day history and 35-day run retention. Maximum nominal event writes: 80 × 8 × 14 = 8,960/day before horizon/past-event filtering; indexes incur additional writes. Verify D1 plan allowance and storage as the history grows.

KV: per-run normalized raw input retained two days; latest scored bundle seven days; history results (including empty responses) five minutes. History reads NEVER call Open-Meteo. Cache failure falls back to D1; raw-cache failure does not invalidate committed scores. Partial DB publication is prevented by D1 batch transaction. Negative cache can delay first visibility up to five minutes. API marks current histories older than seven hours stale; empty, unsupported and unavailable states are distinct.

Android additionally caches a region-history query five minutes; stale/failure never falls back to unrelated personal history. Responses are guarded against selection races. Web effects cancel stale responses. Past local/account snapshots remain in storage for compatibility but are no longer written/displayed as this regional series.

## Verification / rollout

`node --test tests/regional-forecast.test.mjs` tests schedule/cost, coverage, atomic SQL budget and dedup, dates/modes, DST, collector-to-read integration, and failure charging without retries.
`npm run build`
`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' android/gradlew -p android -Dhttp.proxyHost= -Dhttps.proxyHost= :app:assembleDebug`
`npx wrangler deploy --config workers/forecast/wrangler.jsonc --dry-run`

Production rollout order:
1. Execute ONLY migrations/0005_regional_forecasts.sql against firesky D1. Do not apply unrelated uncommitted migrations.
2. Deploy the Pages API containing /api/regional-history and shared modules, plus web client, from a reviewed release containing the current UI work.
3. Deploy workers/forecast/wrangler.jsonc. This starts real scheduled API usage.
4. Release/install Android after API availability. Watch claimed/complete/failed counts, reserved units per UTC day and total upstream 429s.
5. First coverage arrives within three hours; two historical samples require the next cycle. Do not prefill artificial points.

The local build alone does not activate sampling. For this release, remote deployment was explicitly performed after the user's release request.
