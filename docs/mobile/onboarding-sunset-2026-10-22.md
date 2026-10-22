# Android photographic onboarding — 2026-10-22

The six existing onboarding steps now use full-screen AI-generated sunset photographs instead of the illustrated planetary horizon. The supplied reference informs the photo-led composition, bottom black fade, white centered copy, rounded primary button and centered page dots. Existing location, alerts and unit controls remain available. Each landscape crossfades into the next with a slow 3.5% photographic push-in, preserving the previous quiet breathing feel.

## Assets

Generated with the built-in `image_gen` tool, using six separate landscape prompts. These are photorealistic AI images, not photographs of verified locations. Original prompts are saved in [`output/imagegen/onboarding-sunset-prompts.json`](../../output/imagegen/onboarding-sunset-prompts.json); the three additional prompts are in [`output/imagegen/onboarding-sunset-additional-prompts.json`](../../output/imagegen/onboarding-sunset-additional-prompts.json).

Original PNGs are retained in `output/imagegen/onboarding-sunset-{coast,city,prairie,mountain,lake,desert}.png`. Runtime JPEGs are bundled in `android/app/src/main/res/drawable-nodpi/onboarding_sunset_{coast,city,prairie,mountain,lake,desert}.jpg`, totaling 1,436,928 bytes (about 1.37 MiB). No network image fetch is needed. Original generated files remain in the Codex generated-images folder as well.

- Coast: welcome; layered coastal sunset clouds.
- Prairie: value explanation; wispy cirrus and coral cloud layers.
- City: location; textured small cloudlets over a waterfront skyline.
- Mountain: alerts; large golden-edged cumulus towers above mountain ridges.
- Lake: units/language; broad horizontal cloud bands above calm water.
- Desert: completion; softly layered lenticular clouds over dunes.

Setup steps include a stronger background veil for readable forms. Content rests near the bottom when space allows and scrolls when it exceeds the viewport. The current step survives activity recreation, and Android Back moves to the previous step.

The follow-up revision preserves the photographic dimming veil and gradient exactly as requested. Choice controls have opaque warm-white surfaces, solid sea-glass selected fills, dark text and 14 dp corners. Primary actions and active page dots use sea-glass. Back/Skip controls use opaque warm-white surfaces. Disabled primary actions use a solid pale surface. Selection semantics expose the current state to accessibility services. The briefly tried monochrome palette was reverted at the user's request.

## Validation

- `node scripts/android-gradle.mjs assembleDebug testDebugUnitTest`: passed. Existing 13 unit tests passed with no failures or errors.
- Installed the final debug APK on `emulator-5554`; inspected all six steps, Next, top Skip, Android Back, and completion back into the app. Used Skip and Back to inspect units without changing notification preferences.
- Inspected the units page at 1080×1920 with 130% system font scale. Scrolling reveals the entire primary action; text and selection controls remain accessible. Step state survived display/font configuration changes. Restored 1080×2400 and font scale 1.0 afterward.
- Inspected white copy, landscape crops and controls in the initial screenshots under `output/onboarding-preview/`. The final six-landscape revision and opaque controls were checked again and saved under `output/onboarding-preview-v2/`, including all six steps and the compact 130% font units screen before/after scrolling. No AndroidRuntime error was emitted in the inspected log windows.
- `git diff --check` passed for the changed onboarding source. Existing unrelated working-tree changes were preserved.
- The reverted sea-glass/warm-white palette passed `assembleDebug` again. The earlier monochrome experiment's screenshots remain in `output/onboarding-preview-monochrome/` as historical artifacts; the current palette matches `output/onboarding-preview-v2/`. No new tests were added for these color-only revisions.

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. This task did not produce a release build or perform physical-device validation.

For the follow-up validation, the emulator was initially stopped. Started `firesky_api36` headlessly, installed the final debug APK, inspected the UI, restored display/font settings and shut the emulator down again. No saved choices or notification permissions were changed during the walkthrough.
