# MyRunningApp

A personal Android running tracker — a map of where you ran, a spoken
announcement every mile (distance, total time, last-mile pace), and a history of
completed runs (time, distance, pace, estimated calories). Run recording works offline; all activity data stays on the phone. Map tiles
need an internet connection on first viewing and are cached for offline use.

Built as a learning project. See
[`docs/design.md`](docs/design.md) for the full design and milestone plan.

## Stack

Kotlin · Jetpack Compose · Hilt · Room · Coroutines/Flow ·
FusedLocationProvider (GPS) · osmdroid (OpenStreetMap) · Android TextToSpeech.
`minSdk 26`, single Gradle module.

## Building

Requires **JDK 17** and the **Android SDK** (API 35). Open the project in a
recent Android Studio (Ladybug or newer) and let it sync, or from the command
line:

```bash
./gradlew assembleDebug            # build the debug APK
./gradlew testDebugUnitTest        # run JVM unit tests
./gradlew installDebug             # install on a connected device/emulator
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is checked in, so no separate
Gradle install is needed.

## Project layout

```
app/src/main/java/com/myrunningapp/
  data/        Room database, DAOs, repositories, DataStore preferences
  domain/      Pure models and math (units, calories, tracking) — no Android deps
  ui/          Compose screens + ViewModels (track, history, detail, profile)
  di/          Hilt modules
```

## Testing the GPS pipeline without going outside

`app/src/test/resources/traces/` holds recorded-shaped GPS traces of known
length, and `GpsReplay` feeds one through the real filter, distance and split
code. `GpsReplayTest` asserts a surveyed 3240 m loop measures within the design's
2% budget and produces the right mile splits — so distance and pace changes can
be checked in seconds rather than on a run.

## Milestones

1. **Skeleton** — builds; bottom-nav between Track / History /
   Profile; Room database; the Profile screen works end to end.
2. **Tracking core** — GPS foreground service, distance/pace math,
   mile splits, persistence; a plain Track screen with start / pause / stop.
3. **Map** *(current)* — live route and saved routes via osmdroid, camera follow,
   pause gaps, and numbered mile markers.
4. Announcements — mile splits spoken via TTS; start countdown.
5. History & detail — run list, totals, splits table, calories.
6. Polish — permission flow, rich notification, data export, field-test fixes.

## Checking the maps (Milestone 3)

- Start an activity outdoors with location permission. Accepted GPS points should
  appear immediately on the Track map, with a current-position marker.
- Pan the map to unlock the camera; new fixes should leave the camera where you
  put it. Tap **Follow position** to recenter and follow again. Pinch to zoom.
- Pause, move somewhere else, and resume. The route should have a gap, with no
  line or mile-marker distance added across the pause.
- Switch tabs and rotate the phone during tracking, then return: the whole live
  route should remain. Background and reopen the app to check map lifecycle.
- Finish and tap **View saved route**, or open a route from **History**. The map
  should fit the full route and show a numbered pin at each full mile. A run
  without GPS points should show an empty-route message.
- View an area online, then revisit offline: cached tiles should render. Uncached
  areas may have a blank base map; recorded route lines remain available.

The basic History list makes saved maps accessible; totals, splits tables, and
management actions remain part of Milestone 5. Map geometry and live-route
buffer behavior are covered by JVM tests. The checks above require a device.

Validation on the Linux ARM64 development host: the debug APK builds using the
host's existing x86 resource-compiler compatibility wrapper. 89 of 92 JVM tests
pass, including all six new map/live-route tests; the three existing Room tests
require Robolectric's native runtime, which does not support Linux ARM64. Run the
full suite and the device checklist on a supported Android development machine.
