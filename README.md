# MyRunningApp

A personal Android running tracker — a map of where you ran, a spoken
announcement every mile (distance, total time, last-mile pace), and a history of
completed runs (time, distance, pace, estimated calories). Fully offline; all
data stays on the phone.

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

## Milestones

1. **Skeleton** *(current)* — builds; bottom-nav between Track / History /
   Profile; Room database; the Profile screen works end to end.
2. Tracking core — GPS foreground service, distance/pace math, persistence.
3. Map — live route and per-run route via osmdroid.
4. Announcements — mile splits spoken via TTS; start countdown.
5. History & detail — run list, totals, splits table, calories.
6. Polish — permission flow, rich notification, data export, field-test fixes.
