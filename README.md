# MyRunningApp

A personal Android running tracker — a map of where you ran, a spoken
announcement every mile (distance, total time, last-mile pace), and a history of
completed runs (time, distance, pace, estimated calories). Run recording works
offline and all activity data stays on the phone; map tiles need an internet
connection on first viewing and are cached for offline use.

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
  domain/      Pure models and math (units, calories, stats, tracking) — no
               Android deps
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
3. **Map** — live route and saved routes via osmdroid, camera follow,
   pause gaps, and numbered mile markers.
4. **Announcements** — mile splits spoken via TTS; start countdown.
5. **History & detail** — run list with week/all-time totals, a
   detail screen with the stat block and splits table, delete, and the
   MET-based calorie estimate wired in.
6. **Polish** *(current)* — permission rationale flow with graceful degradation,
   notification controls, GPX/JSON export, and the pace-coloured route. Fixes
   from field testing are still outstanding; they need a real run first.

## Interrupted activities and privacy

Active activities are excluded from History and protected against deletion or
editing while recording. The tracker saves route points, summary values and
splits together every ten seconds, at mile boundaries, and on pause/finish.
If the process is killed, reopening the app automatically saves the last
checkpoint as a **Recovered activity** in History. Its end time is the checkpoint
time, not the time the app was reopened. The uncheckpointed tail (normally less
than ten seconds) can be lost; recording does not automatically resume.

Database version 2 preserves existing saved activities during upgrade. Recovery
applies to activities recorded with this version; older interrupted activities
have no timing checkpoints to recover.

Automatic cloud backup and device transfer are disabled and explicitly exclude
app data. GPX and JSON exports remain available when requested by the user.
Export preparation and destination writes run off the UI thread.

Tracking durations, countdowns, GPS age/speed checks and checkpoint scheduling
use Android time since boot, including sleep. Clock corrections do not affect
pace or split times. Route timestamps stay anchored to the activity start date
so saved routes and exports remain ordered. Fixes taken before Start or Resume
are rejected even if they arrive in a recent batch. The manifest declares speech
engine visibility for voice announcements on Android 11 and later.

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

Map geometry and live-route buffer behavior are covered by JVM tests. The checks
above require a device.

## Checking the announcements (Milestone 4)

- Set a countdown in **Profile → Preferences**, start an activity, and listen for
  "3, 2, 1, go". Distance stays at zero until the countdown ends.
- Every completed mile is spoken: cumulative distance, total time, and the pace
  of the mile just finished. Partial miles are never announced — the finish line
  covers that stretch.
- Start music or a podcast first. The voice should duck it, not stop it, and the
  audio should come back up straight after each line.
- Toggle **Voice announcements** off mid-run: the very next line should be
  silent, with no catch-up when it is switched back on.

Wording and the rules about what is worth saying are covered by JVM tests
(`AnnouncementTextTest`, `RunTrackerTest`); the checks above require a device.

## Calorie estimates

Calories are estimated, not measured — the same order of accuracy Endomondo and
MapMyRun offer without a heart-rate strap. `CalorieCalculator` stacks two
standard pieces:

- the **ACSM metabolic equations**, which give oxygen uptake from speed on the
  flat (grade is ignored — GPS altitude is far too noisy to grade a route with);
- **Mifflin–St Jeor**, which supplies the resting term from the profile's height,
  age and sex instead of the one-size-fits-all 3.5 mL/kg/min the plain MET
  formula assumes. Tuning that term is the only thing those three fields do.

Each run is estimated against the **weight snapshot** taken when it started, so
editing the profile later never rewrites past runs. Correcting a run's activity
type on the detail screen does re-estimate it, since the same route costs
noticeably more running than walking. Runs recorded before this milestone were
saved with no estimate and still show 0 kcal.

## Checking history and detail (Milestone 5)

- Finish an activity. It should appear at the top of **History** with its date,
  a run or walk icon, distance, moving time, average pace, and calories.
- The header shows **this week** beside **all time** — distance, activity count,
  and moving time. The week starts on the day your locale says it does.
- Tap a row to open it: route map, then distance / moving time / elapsed time /
  average pace / calories, then the splits table. The split paces should match
  what was announced out loud during the run.
- Switch the activity type on the detail screen. The label and the calorie
  number should both change; the same route costs less as a walk.
- Delete from either screen — long-press a history row, or the button at the
  bottom of the detail screen. Both confirm first, and the route and splits go
  with the run. Deleting from the detail screen returns you to the list.
- With no runs saved, History shows its empty message and no totals header.

## Permissions (Milestone 6)

Location is asked for behind a rationale screen the first time you start an
activity, and **background location is a separate, optional second ask**. Android
only offers each permission dialog once, so the app asks for background location
exactly once and remembers that it did.

Declining background location does not stop you running. Tracking still works
with the app open; the Track screen shows a dismissible banner explaining that
recording may stop when the screen does. From Android 11 the "Allow all the
time" setting exists only in system Settings, so the rationale sends you there
rather than firing a request Android refuses without showing you anything.

## Checking the polish (Milestone 6)

- **Permissions:** clear the app's data, then press Start. You should see the
  rationale before Android's own dialog, then the background rationale as a
  separate step. Decline it and confirm the run still starts and the banner
  appears; dismiss the banner and confirm it stays gone.
- **Notification:** start a run and pull down the shade. The notification shows
  live distance and time plus **Pause / Finish** — **Resume / Finish** once
  paused, **Start now / Cancel** during a countdown. Finish from the lock screen
  and confirm the run is saved.
- **Export a run:** open a saved activity and choose **Export as GPX**. The
  system file picker suggests `run-YYYY-MM-DD-HHMM.gpx`. Open the result in any
  map viewer, or import it into another running app; a run that was paused
  should show a gap rather than a straight line across it.
- **Export everything:** **Profile → Data → Export all data**. One JSON file
  with every activity, its route and its splits — this app has no server, so the
  export is the backup. Import is not built yet.
- **Pace colours:** turn on **Profile → Preferences → Colour route by pace**,
  then open a saved run of at least a few hundred metres. The route is drawn
  green through amber to red with a legend. Colours are relative to *that run* —
  an easy run and a tempo run each use the whole ramp — so they say fast-and-slow
  for this run, not in general. Short runs stay a single blue line: there is no
  spread worth colouring.
- **Keep screen on:** the preference has existed since milestone 1 and did
  nothing until now. With it on, the screen should stay awake during a run and
  go back to normal the moment the run ends.

Validation on the Linux ARM64 development host: the debug APK builds using the
host's existing x86 resource-compiler compatibility wrapper. 191 of 198 JVM tests
pass, including tracker checkpoint/protection and asynchronous export coverage.
Seven Room tests (including recovery, rollback and migration coverage) cannot
start because Robolectric's native libraries are unavailable on Linux ARM64.
Separate SQLite checks validate migration schema equivalence, preservation of
saved summaries, deletion protection and the recovery update. Run the full Room
suite and device checklists on a supported Android development machine.
