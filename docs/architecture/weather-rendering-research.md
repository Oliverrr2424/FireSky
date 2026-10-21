# Weather background rendering research — 2026-10-21

Scope: technical research for the supplied Apple Weather references. No renderer changes in this research pass. The screenshots demonstrate a visual target; they do not establish Apple's internal implementation or verify the OS-version labels in the images.

## Current implementation

Android `ui/WeatherBackdrop.kt` uses Compose Canvas, stretched radial-gradient circles for clouds, line particles for rain, and a uniform screen flash for lightning. `HomeScreen.kt` passes only a categorical theme and `isNight`. The web implementation in `src/WeatherBackdrop.jsx` uses the same general 2D approach.

Missing visual cues: structured cloud silhouettes, thickness-dependent transmission, self-shadowing, sun-facing edges, coherent layered motion and local illumination inside storm clouds. Increasing the number of circles will not supply these cues. Canvas itself is not necessarily CPU-only; the issue is representation and lighting, not simply Canvas versus GPU.

An additional Android defect to address during implementation: `lightningNextAt` is initialized with `System.currentTimeMillis()` but compared to frame timestamps supplied by `withFrameNanos`. These clocks have different origins. Use one monotonic elapsed animation clock for both scheduling and stepping.

## Recommended first production approach

Use authored cloud density/detail textures plus runtime GPU lighting and distortion, with two or three independently moving depth layers. Asset authoring quality is part of the task: shaders alone will not make arbitrary noise look like convincing cumulus clouds. Density textures should avoid baked directional lighting so sunrise/sunset relighting remains possible.

Rendering order:
1. Time-of-day sky and horizon haze.
2. Distant cloud layer with low contrast and slow motion.
3. Mid/near cloud density layers with sun-direction lighting, soft transmission and edge illumination.
4. Sun disc/glow attenuated by the same cloud density, rather than painted on top of clouds.
5. Depth-grouped rain/snow with varying size, speed and focus; localized rain impacts where the layout supplies a convincing receiving edge.
6. Lightning branch geometry, narrow bright core and broad halo, synchronized with localized cloud lighting. Reduce-motion mode suppresses flashes.
7. Readability scrim and UI cards. Rain remains behind text; do not render dense streaks over every information card.

This is an artistic approximation of cloud lighting. It does not provide arbitrary fly-through geometry or physically complete multiple scattering. Full 3D density ray marching is a possible later quality tier, after device profiling justifies it.

## Android integration

Android 13/API 33+: `RuntimeShader` (AGSL) + Compose `ShaderBrush`. Image shaders can supply the authored density and detail inputs. Cache shaders, bitmaps and brushes, and update uniforms in drawing rather than rebuilding the screen composition each frame. AGSL is a subset of GLSL, not a general compute/3D engine; check its restrictions before designing a volume-rendering pipeline.

Current minSdk is 24. Earlier Android versions need an explicit fallback: layered bitmap clouds with pre-authored light variants and existing particles. If equal advanced rendering quality on pre-33 devices becomes a requirement, evaluate a dedicated OpenGL ES renderer rather than raising minSdk implicitly.

Proposed separation:
- `WeatherSceneState`: cloud coverage, cloud type/density, precipitation strength, wind motion, solar elevation/direction, day/night palette, thunder activity, transition progress, quality tier and deterministic seed.
- `WeatherSceneMapper`: available forecast data -> smoothed scene parameters. Missing fields receive visual defaults, not additional automatic API calls.
- `SkyRenderer`: sky + clouds + lighting, with independent backend implementations.
- `PrecipitationRenderer`: rain/snow particles.
- `LightningController`: geometry and synchronized light envelope on a common clock.

Potential shader uniforms: resolution, elapsedSeconds, coverage, densityScale, windOffset, sunDirection, sunColor, ambientColor and lightningPosition/intensity. Conceptually, moving density controls opacity via `alpha = 1 - exp(-density * thicknessScale)`; lighting then combines ambient, directional and transmission terms. That expression is an approximation for a layer, not a complete volumetric scattering solver.

The current two parameters (`theme`, `isNight`) are insufficient. Use the existing hourly cloud layers/precipitation/wind speed and computed solar position where available. Wind direction needs checking before wiring: if absent in the current requested/parsed payload, use an artistic direction initially. Choose a clear scene-time policy: current weather by default, or the selected forecast hour with an explicit preview label. Changing sunrise/sunset analysis mode alone should not silently masquerade as current observed weather.

## Performance and validation

Initial targets, not measured results: animate the background at 30 fps while keeping scrolling responsive; test 60 fps as an optional higher tier. For an offscreen cloud render, start with half width/height (one quarter pixel count), then composite at display size. A full-size AGSL shader that merely changes its coordinate scale still shades every output pixel and does NOT achieve this saving. A real lower-resolution render target/pipeline is required.

Cache resources, avoid allocations inside particle loops, stop animation when the screen is hidden/backgrounded, and provide static reduced-motion/power-saving variants. Profile real low/mid/high Android devices: frame time, scroll jank, sustained temperature and battery use. Emulator performance is insufficient to promise production quality.

Build a developer-only fixed-seed scene gallery: clear noon, partly cloudy, overcast, rain, thunderstorm, sunset, night and snow. Compare frozen frames for shape/light quality, then recordings for motion, seams and transitions. Check contrast and scrolling while the scene runs. Start with partly cloudy daylight and sunset because they expose cloud shape and lighting errors; then add storms.

## Primary references

- Android AGSL overview: https://developer.android.com/develop/ui/views/graphics/agsl
- Compose ShaderBrush and Android 13 fallback guidance: https://developer.android.com/develop/ui/compose/graphics/draw/brush
- Compose rendering phases: https://developer.android.com/develop/ui/compose/performance/phases
- Guerrilla Nubis overview: https://www.guerrilla-games.com/read/nubis-realtime-volumetric-cloudscapes-in-a-nutshell
- Guerrilla Nubis³: https://www.guerrilla-games.com/read/nubis-cubed
- Epic volumetric clouds, ray marching, scattering and quality tradeoffs: https://dev.epicgames.com/documentation/unreal-engine/volumetric-cloud-component-in-unreal-engine?lang=en-US

These sources support available techniques and platform capabilities; they do not establish how Apple Weather is implemented. Recommendations above are engineering judgments for FireSky.
