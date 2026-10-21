# Chase Map coverage fix — 2026-10-21

This pass preserves the ongoing onboarding, navigation, alert-v2, widget and localization work in the working tree. It focuses on the Chase Map's rendering, browsing bounds and controls. No payment flow or remote deployment was added.

## Behavior

- `ChaseMapRegion` derives one geographic footprint from the existing regional samples. Its Mercator calculations set the minimum zoom and inset camera-center limits by half the viewport, keeping the whole visible map inside the sampled area.
- `ChaseMapController` renders a fixed geographic `ImageSource`, with one cached bitmap per metric. Panning/zooming changes the camera only; it neither re-rasterizes heat nor requests another weather grid. Camera listeners no longer capture old samples or map type.
- `inspectChasePoint` uses the existing grid locally. The inspection card identifies the result as a regional estimate; its window comes from the selected regional event. Explicitly selecting “Forecast here” still loads that point as a new primary place. Base-map tiles load normally.
- Replaced the low-contrast crowded header with an opaque dark panel, separate city title and two full-width toggle rows. Shortened the map copy in all five resource directories. Added readable zoom/recenter controls, a local-area legend, a named best-nearby action and a white navigation button.
- No percentage bubbles were restored to the maps.

## Verification

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`: successful; 13 unit tests, zero failures; lint zero errors / 92 warnings.
- Five new coverage tests check portrait and landscape viewports, northern latitudes/tablet dimensions, permitted pan ranges at different zooms and missing data.
- Two old alert assertions now distinguish `kind=alert` from newly added feedback plans; an additional test verifies the feedback notification is separate and occurs 25 minutes after its event window.
- Minimal existing build blockers were corrected: the feedback notification helper now checks notification permission locally, and the literal percent sign in the French improved-forecast description is marked unformatted.
- R8 release compiled successfully using the existing external debug-key signing init script for emulator installation. Production signing settings are unchanged.
- Tested on `emulator-5554`, API 36: repeated drags toward all four edges, minimum zoom, zoom buttons, recenter, probability/intensity switching, offline spot selection and 1.3× system font. Debug viewport audits reported `viewportCovered=true`; no missing heat edge, migrating vertical stripe or app crash observed. Rechecked the edge and toggle UI in the minified release.
- Restored font scale to 1.0 and network connectivity; retained the user's Toronto location and onboarding data. The updated minified test app is left open on Chase Map in the visible emulator.

Local screenshots and camera audits: `F:\tmp\firesky-chase-fix` (`chase-final.png`, `chase-release-edge.png`, `chase-release-font13.png`, `chase-offline-spot.png`, `camera-audit.log`).

These checks cover the map fix and compilation of the current working tree; they do not establish end-to-end readiness of every newly added onboarding/FCM/widget feature.
