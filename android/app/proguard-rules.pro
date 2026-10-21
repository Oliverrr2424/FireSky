# R8 configuration for the native release build.
#
# The libraries already ship consumer rules that cover their reflective entry
# points, so this file adds no blanket keeps:
#   * MapLibre 13.7.1 keeps its native/Gson entry points, enums and geojson.
#   * OkHttp 4.12.0 keeps PublicSuffixDatabase and silences optional platforms.
#   * WorkManager 2.10.1 keeps ListenableWorker subclasses, their constructors
#     and their class names, which is what ForecastRefreshWorker relies on.
#   * Activities and receivers declared in AndroidManifest.xml are kept by AGP.
#
# Checked against build/outputs/mapping/release/seeds.txt: MainActivity,
# FireSkyApplication, SkyAlertReceiver, BootReceiver and ForecastRefreshWorker
# are seeded with their constructors, while app data classes are renamed.

# Keep stack traces readable in Play Console; mapping.txt still deobfuscates.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
