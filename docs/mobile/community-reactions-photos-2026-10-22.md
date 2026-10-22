# Reactions, viewpoint photos, sharing and Community — rollout notes (2026-10-22)

Originally developed on Android 1.0.3. These changes were subsequently shipped as Android 1.0.4 (code 5), with the remote migration and both Pages deployments completed. See [release-2026-10-22.md](release-2026-10-22.md) for rollout and verification results.

## Deploy order

1. Apply `migrations/0006_reactions_and_viewpoint_photos.sql` to the remote D1 database **first**. `GET /api/sync` now selects `viewpoints.photo_id`, so deploying the Functions before the migration breaks sync for signed-in users.
2. Deploy Pages (Functions and the web app together). The web app now opens shared links (`/?lat=&lon=&name=&region=&mode=`).
3. Ship a new Android build. Old builds keep working: the old `POST /api/feedback` body is still accepted.

## Backend

- `functions/api/feedback.js`: `GET` returns public like/dislike counts for an event (±20 min, ±0.25°) and, with a valid session, the caller's own reaction. `POST` stores `sentiment` plus an optional `reason` (`too_high`, `too_low`, `wrong_time`, `other`, dislike only); a null sentiment withdraws the reaction (row deleted). The old `outcome` mapping (like = yes, dislike = no) is gone: a vote is a vote, not a claim about what happened.
- `functions/api/viewpoint-photos/`: `POST ?id=` uploads a JPEG/PNG/WebP body (checked against its magic bytes), `GET /{id}` and `DELETE /{id}` for the owner only. Limits: 1.5 MB per photo, 60 photos and 40 MB per account. Photos are D1 BLOBs; D1 caps a row at 2 MB. R2 is the better home if volume grows.
- `functions/api/sync.js`: viewpoints carry `photo_id`; an id is kept only if the same account uploaded it; unreferenced photos older than an hour are swept on each sync. Account deletion removes photos.
- Tests: `node --test "tests/*.test.mjs"` (`tests/community-data.test.mjs` is new). Also exercised against local workerd/D1 (`wrangler pages dev`): a 137 KB JPEG round-trips byte-identical.

## Android

- Like/dislike: per event (selected day and mode), animated, tap again to withdraw, optional "what was off?" after a dislike, community counts. Reactions made offline or signed out stay queued (`synced=false`) and upload on resume or sign-in. The notification's rate buttons write the same entries.
- Viewpoint photos: picked with the system photo picker, resized to ≤1280 px JPEG (≤900 KB) into app storage, uploaded when signed in, shown as a thumbnail on the saved-viewpoint chip.
- Share: a 1080×1350 PNG card plus text with a link back to the same place and event.
- Community tab: two equal columns, row heights follow each photo's aspect ratio. **Sample content only**: the 12 photos are generated artwork (`res/drawable-nodpi/community_demo_*.jpg`) and the captions are made up. There is no posting, no backend and no moderation yet.
- Home: heat maps size themselves so the score gauge is fully visible on first open (`HOME_FIXED_CHROME` in `HomeScreen.kt` is the tuning constant); the bottom bar is transparent over the weather backdrop; stars and clouds now cover the whole screen height.

## Tap animations on every icon (Android)

Tapping an icon plays a short animation. `LucideIcon`, `ControlIconButton` and the bottom tabs all draw through `ui/icons/AnimatedIcon.kt`, which reads each vector drawable's strokes and moves them per path (rotate, scale, translate, or draw the line in with path trimming). Tapping a button plays the icons inside it, not only a tap on the icon itself (`tapPulse`). A system "remove animations" setting turns it off.

- No library: `lucide-animated` is React/Motion and hover-driven, and Lottie needs an animation file per icon. Both were used as design reference only.
- Each icon's motion lives in `ui/icons/IconMotion.kt`. A new icon draws itself in by default; `IconMotionsTest` fails until it gets its own motion, and also checks every motion ends exactly at rest.
- Not animated: the notification small icon and the launcher art, and the thumbs inside the feedback card (they have their own pop). The web app is unchanged.

## Known edge

- Sync replaces the whole viewpoint list. During release preparation, legacy-client compatibility was added: omitted photo fields retain the existing photo for the same viewpoint id, while explicit null removes it. A regression test covers both cases.

## Not verified

- Signed-in photo upload and reaction sync from the app against a real server (the server side was verified with curl; the app's side was verified only up to the point of a signed-out device).
- The R8 release APK was built but not run on a device.
