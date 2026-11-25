# MyRunningApp — Android running tracker

## Context

Endomondo was discontinued; the user currently uses MapMyRun but wants their own
Android app covering the three features they actually rely on:

1. A map of where they ran (live during the run + per-run history).
2. A spoken announcement every mile: cumulative distance, total time, and the
   pace of the last mile.
3. A history of completed runs showing time, distance, pace, and estimated
   calories.

This is a **greenfield project** (empty directory, not yet a git repo) and a
**learning project** for the user — so the stack and structure should reflect
current-standard modern Android practice, and implementation should favor clear,
well-bounded units over cleverness.

### Decisions already made with the user

| Topic | Decision |
|---|---|
| Nature | Learning project; user builds/runs in Android Studio, reviews code, tests on their phone |
| Storage | **On-device only** (Room). No backend, no account. Export file is the backup. |
| Maps | **osmdroid** (OpenStreetMap tiles) — no API key, no billing account |
| Distribution | Personal sideload now, signed APKs on GitHub Releases that the app can update itself from; **design with eventual Play Store release in mind** |
| Announcement content | "N miles. Time, MM minutes SS seconds. Last mile pace, M minutes S seconds." (no avg/current pace in the voice line) |
| Auto-pause | **No** — manual pause/resume only |
| Calorie inputs | Profile stores weight + height + age + sex |
| Activity types | **Running + Walking + Cycling** (chosen before a run; different calorie coefficients, and a ride is read in mph rather than min/mile) |
| Start flow | Split control: **Start now** / **Start in 30s** countdown (length configurable: 0/10/30s) |
| Units | Miles / feet, fixed (matches user's usage) |

## Tech stack

- **Kotlin**, **Jetpack Compose**, single-Activity, Compose Navigation
- **MVVM**: Compose screen ← `ViewModel` (`StateFlow`) ← repository ← Room / service
- **Hilt** for dependency injection (standard in real codebases; worth learning)
- **Room** for persistence, **Kotlin Coroutines + Flow**
- **FusedLocationProviderClient** (Google Play Services Location) for GPS — this is
  separate from Google Maps and needs **no API key**
- **osmdroid** for map rendering (via `AndroidView` in Compose)
- Android **`TextToSpeech`** for announcements
- `minSdk 26`, `targetSdk` latest stable
- Single Gradle module (multi-module is overkill at this size)

## Project structure

```
app/
  MyRunningApp.kt            Application: Hilt, osmdroid init (User-Agent, cache dir)
  MainActivity.kt            Single activity, Compose Navigation host
  data/
    db/                      Room: entities, DAOs, AppDatabase, type converters
    location/                LocationTrackingService + GPS point pipeline
    repository/              RunRepository, ProfileRepository
    export/                  GPX / JSON writers, GPX reader (Storage Access Framework)
    update/                  UpdateManager, installer receiver, daily check worker
  domain/
    model/                   Run, RunPoint, Split, Profile, ActivityType, RunSessionState
    tracking/                RunSession state machine, distance & pace math, GPS filter
    calories/                MET-based CalorieCalculator
    announce/                AnnouncementEngine (wraps TextToSpeech), announcement text builder
    update/                  Release parsing, version compare, verified download copy
  ui/
    track/                   Live run screen (map, live stats, start/countdown/pause/stop)
    history/                 Run list + totals header
    detail/                  Single run: route map, stat block, splits table
    profile/                 Weight/height/age/sex + preferences
    update/                  App updates section of the Profile screen
    theme/                   Compose theme
```

## Data model (Room)

**Run** — one completed activity. Denormalized summary fields for fast list rendering.
- `id` (autogen), `startedAt: Instant`, `endedAt: Instant`
- `activityType: RUN | WALK | BIKE`
- `distanceMeters: Double`, `movingDurationSec: Long`, `elapsedDurationSec: Long`
- `avgPaceSecPerMile: Double`, `calories: Int`
- `weightKgAtRun: Double` — **profile snapshot**, so editing the profile later does
  not silently rewrite history

**RunPoint** — raw GPS track (~1 Hz; ~1–2k rows/hour, fine for Room).
- `id`, `runId` (FK, indexed), `timestamp: Instant`
- `lat`, `lon`, `altitude`, `accuracyMeters: Float`
- `segmentIndex: Int` — increments on each pause/resume so the map draws separate
  polylines instead of a straight line across a pause

**Split** — one per mile, computed and persisted at the moment the mile completes
(same instant as the announcement).
- `id`, `runId` (FK, indexed), `splitNumber: Int`
- `distanceMeters: Double` (1609.34 for full miles, remainder for the last)
- `durationSec: Long`, `paceSecPerMile: Double`

**Profile** — single row (`id = 0`).
- `weightKg`, `heightCm`, `age`, `sex`

Write points to Room in batches (~every 10 s) during the run so a crash mid-run
does not lose the whole track.

## GPS tracking & math

**LocationTrackingService** — foreground service, `foregroundServiceType="location"`.
- Started on **Start** (or on entering countdown). Persistent notification shows
  live distance/time + pause/resume/stop actions.
- Subscribes to `FusedLocationProviderClient` at ~1 Hz, `PRIORITY_HIGH_ACCURACY`.
- Owns the authoritative `RunSession`; exposes state as a `StateFlow` observed by
  ViewModels. If the app process is killed while the service lives, reopening the
  app re-attaches to the in-progress session.
- Holds a CPU wakelock while tracking so points keep coming with the screen off.

> *Built in milestone 2 as two pieces rather than one:* `RunTracker` is an
> `@Singleton` owning the `RunSession`, the writes to Room and the live
> `StateFlow`; the service keeps the process alive, subscribes to GPS and mirrors
> the state into the notification. The behaviour above is unchanged — a
> process-wide singleton outlives the service just as the service outlives the UI —
> but the run screen observes the tracker directly rather than binding to a
> service, and the tracker's timing rules are unit-tested against a fake recorder.

**State machine:** `IDLE → COUNTDOWN → TRACKING ⇄ PAUSED → FINISHED`,
plus `COUNTDOWN → IDLE` on cancel.

**Countdown ("Start in 30s"):**
- Service + GPS start immediately so the fix is warming up; distance/time
  accumulation begins only at zero.
- Screen + notification show a ticking countdown; TTS speaks the last few seconds
  ("3, 2, 1, go").
- **Cancel** and **Skip (start now)** available during countdown.
- Fixes received during warm-up establish accuracy/first position only — not added
  to distance.

**Per-fix pipeline:**
1. **Filter:** drop fixes with `accuracy > 25 m`; drop implied speed > ~13 m/s
   (kills GPS jumps); drop fixes older than 5 s.
2. **Distance:** add great-circle `previousPoint.distanceTo(newPoint)` to the
   running total — only while `TRACKING`, never while `PAUSED`.
3. **Persist:** buffer the point; flush buffer to Room every ~10 s.
4. **Emit** live stats: distance, moving time, current-segment pace.

**Pace:**
- *Last-mile pace* (announcement) = Δtime between consecutive mile markers
  (distance is exactly 1 mile, so pace = elapsed time).
- *Average pace* (live + final) = `movingDuration / distanceMiles`.
- Mile marker detected when cumulative distance crosses `N × 1609.34 m`;
  **interpolate** the exact crossing time between the two straddling fixes so
  splits do not drift over a long run.

## Announcements

**`TextToSpeechAnnouncer`** wraps Android `TextToSpeech`.
- Triggered by the mile-crossing event.
- Text: *"3 miles. Time, 27 minutes 42 seconds. Last mile pace, 9 minutes 5 seconds."*
- Also short cues on Start, Pause, Resume, Finish ("Run complete. Total distance
  3.4 miles, average pace ...").
- Requests `AudioManager` focus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so it ducks
  music/podcasts rather than fighting them.
- Setting to mute the voice entirely.
- Announcement text building is a **pure function** (event → string) so it is
  unit-tested without TTS.
- The engine is a process-wide singleton, not owned by the tracking service: the
  finish line is still being spoken while the service is stopping itself.
- Only whole miles are spoken. The partial final split goes in the splits table
  but never gets a mile announcement — the finish line covers that stretch.

## Map (osmdroid)

One-time init in `Application`: set User-Agent, set a writable tile cache dir.
osmdroid disk-caches tiles, so previously-seen routes render offline.

**Live run screen:** `MapView` via `AndroidView`. One `Polyline` per
`segmentIndex` (pause gaps stay gaps), current-position marker, camera follows
user with a **lock/unlock-camera toggle** so panning doesn't snap back.

**Run detail screen:** full route from `RunPoint` rows, auto-zoomed to bounds,
numbered mile-marker pins. *Later milestone:* color the polyline by per-point pace
(fast green → slow red).

## History & stats

**History list:** `LazyColumn` from a Room `Flow`, newest first. Row = date,
activity-type icon, distance, moving time, avg pace, calories. Tap → detail.
Long-press → delete (confirm). **Import GPX** in the header reads a track from
another app, replays it through `RunSession` (segments become pauses) so its
distance, moving time and splits follow the same rules as a recorded run, and
opens the new activity. Header with simple totals (this week / all time:
distance, run count, time).

**Run detail:** route map, then stat block (distance, moving time, elapsed time,
avg pace, calories, activity type, date), then splits table (mile #, pace, time —
the same numbers announced). Actions: edit activity type, delete, export this run
as GPX.

## Calories (`domain/calories`)

MET-based, explicitly approximate (as Endomondo/MapMyRun were):
- Running MET derived from speed (ACSM/Léger-style running equation).
- Walking MET from speed (ACSM walking equation).
- Cycling MET from speed, fitted to the Compendium of Physical Activities'
  outdoor-cycling entries — the ACSM's cycling equation wants a leg ergometer's
  power output, which a phone does not have.
- `calories = MET × 3.5 × weightKg / 200 × minutes`, using the **profile
  snapshot** on the run.
- Age/height/sex refine the resting component.
- Pure functions — fully unit-tested.

## Profile / settings screen

- **Profile:** weight, height, age, sex.
- **Preferences:** countdown length (0/10/30 s), voice on/off, keep-screen-on
  during run, pace-color on map.
- **Data:** export all (JSON + GPX per run) via Storage Access Framework;
  restoring the JSON backup is a later addition.
- **App updates** (release builds only): installed version, **Check for
  updates**, an opt-in **Check for updates daily** switch, and the download /
  install / confirm controls. See milestone 9.

## Permissions & platform

- `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` — requested on first run start,
  behind a rationale screen.
- `ACCESS_BACKGROUND_LOCATION` — requested separately (Android sends the user to
  Settings); needed for long screen-off tracking on Android 10+.
- `POST_NOTIFICATIONS` (Android 13+).
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`.
- `REQUEST_INSTALL_PACKAGES` — self-update only. Android still makes the user
  allow "Install unknown apps" for this app the first time, from its own
  settings screen; nothing installs without that.
- **Graceful degradation:** if background location is denied, tracking still works
  with the screen on / app foregrounded, with a warning to the user.

## Testing strategy

- **Unit tests (JVM — the bulk):** distance accumulation, GPS filter rules,
  mile-crossing interpolation, pace math, calorie formulas, `RunSession` state
  machine, announcement text formatting.
- **Instrumented Room tests:** DAO queries and migrations.
- **GPS replay harness:** feeds a recorded trace (list of lat/lon/time) through
  the real pipeline, so a known route's distance/splits can be verified without
  going outside. Ship 1–2 sample traces as test resources.
- **Manual field-test checklist:** screen off, music playing, pause/resume, GPS
  loss — run through on a real walk/run.
- **Compose UI tests:** light — navigation smoke tests + stats render correctly.

## Build milestones

Each milestone builds, runs, and is testable on its own.

1. **Skeleton** — project builds; single activity; Compose Navigation between 4
   screens; Hilt wired; Room database with all entities + DAOs; **profile screen
   fully working** end to end.
2. **Tracking core** — `LocationTrackingService`, GPS pipeline, `RunSession` state
   machine, distance/pace math, persist `Run` + `RunPoint`. Verified with the
   replay harness. Crude run screen: Start / Pause / Stop + text stats.
3. **Map** — osmdroid live route on the run screen; full route on the run detail
   screen. Implemented with pause-separated polylines, automatic camera unlock
   on pan, follow toggle, fitted saved routes, and interpolated full-mile pins.
   A basic History route browser and post-finish link make saved maps accessible;
   history totals and management remain in milestone 5.
4. **Announcements** — `TextToSpeech` engine; mile splits computed, persisted, and
   spoken; audio-focus ducking; the Start-now / Start-in-30s countdown + its
   setting. *Built as three pieces:* `AnnouncementText` turns an event into a
   sentence and is a pure function, so every line — "1 mile" rather than
   "1 miles", "9 minutes" rather than "9 minutes 0 seconds" — is pinned down by
   unit tests; `Announcer` is the interface `RunTracker` speaks through, so the
   rules about *what* is worth saying are testable against a fake; and
   `TextToSpeechAnnouncer` is the one Android-facing piece, handling engine
   start-up latency, transient `MAY_DUCK` audio focus and the mute preference.
   The countdown state machine and its setting already existed from milestones 1
   and 2; this milestone gave them their voice ("3, 2, 1, go").
5. **History & detail** — run list with totals header; detail screen with splits
   table; delete; calorie calculation wired in. *Built with the summing and the
   estimating kept pure:* `HistoryStats` rolls a run list into week and all-time
   totals over an injected clock and zone, so "this week" is tested without
   waiting for one, and the week's first day comes from the user's locale rather
   than being hard-coded. `CalorieCalculator` stacks the ACSM metabolic equations
   (speed to oxygen uptake, grade ignored — GPS altitude is too noisy to grade a
   route with) on Mifflin-St Jeor for the resting term, which is the only thing
   height, age and sex do here. Estimates run against the `weightKgAtRun`
   snapshot, so a profile edit never rewrites history; correcting a run's
   activity type does re-estimate it, since the label and the number sit next to
   each other and must agree.
6. **Polish** — permission rationale flow; notification with live stats +
   controls; export to GPX/JSON; pace-colored polyline; fixes from field testing.
   *Built by pulling a pure decision out of each Android-facing piece:*
   `PermissionGate` answers "what do we ask next" from four booleans, so the
   order — foreground first because a run is impossible without it, background
   as a separate optional ask, notifications never blocking — is tested without
   a device; `RunNotificationSpec` says which controls belong to which run
   state, leaving the service to turn them into `PendingIntent`s. Background
   location is asked for exactly once and its refusal costs screen-off tracking
   rather than the run, so a declined ask leaves a dismissible banner instead of
   a dead Start button. Export writes GPX per run and one JSON backup of
   everything; both writers are pure functions over rows, and the Storage Access
   Framework `Uri` never reaches them — the screen owns the picker and the
   bytes, which is also why the app asks for no storage permission at all. The
   pace-coloured polyline cuts the route into ~100 m chunks rather than colouring
   per fix (1 Hz pace is mostly GPS noise) and scales its ramp to the 10th and
   90th percentile of *that run's* own paces, so one wait at a traffic light
   does not wash the whole route into "fast". It stays on the detail screen:
   the live map redraws every second and the ramp needs a finished run's
   distribution to normalise against. Field-testing fixes are still to come —
   they need a real run first.
7. **Health Connect sync** — finished runs are published to Health Connect so
   other apps on the phone (Fitbit, Samsung Health, Strava, the phone's own
   health dashboard) can read them; the app never reads anything back. **Off by
   default**, and a run finished with the setting off costs nothing at all — the
   design lives in
   [`docs/superpowers/specs/2026-09-09-health-connect-sync-design.md`](superpowers/specs/2026-09-09-health-connect-sync-design.md).
   *Built as an outbox, not a direct write on finish:* a `healthSyncState`
   column on `runs` tracks what still needs writing, but deleting a run takes
   its row with it, so there is nowhere left to remember that a delete is owed —
   which is why deletions get their own table, `health_deletions`, populated
   only for runs that had actually made it to Health Connect. A WorkManager
   worker drains deletions before writes on every pass, so a delete can never
   lose a race against a stale write for the same run. Every record is written
   under `clientRecordId = "run-<id>"`, which is what makes the rest of the
   engine simple: Health Connect treats a second write under the same id as an
   update rather than a duplicate, and accepts deletes by that id, so correcting
   a run's activity type is just "write it again," a retry after an ambiguous
   failure cannot double a workout, and no Health Connect uid ever has to be
   stored. A revoked permission is treated as **stop, not fail** — the drain
   halts and nothing is marked `FAILED`, because the user may re-grant a minute
   later and the queue should still be there when they do. The one place this
   milestone had to think harder than "copy the numbers over" is laps: Health
   Connect validates every record on construction and rejects one that falls
   outside its session, so a mile split — which only knows its own moving
   duration, not a wall-clock time — is placed by walking the run's
   pause-separated segments and converting that moving-time offset into an
   instant, which is also what keeps a mile that straddles a pause safely
   inside the session instead of landing in the pause gap. The route is the one
   conditional part of an otherwise-identical record: the same session is
   written either way, with the GPS track attached only when the separate route
   permission is granted, so an older Health Connect or a declined permission
   costs the map and nothing else. Turning the toggle on backfills existing
   history once; turning it off stops future syncing and leaves whatever was
   already written alone. The privacy policy this milestone required is
   published at <https://jjbasken.github.io/MyRunningApp/>
   (source: [`docs/privacy-policy.md`](privacy-policy.md)), and the Play Console
   paperwork for eventually shipping this is worked out ahead of time in
   [`docs/play-health-declaration.md`](play-health-declaration.md). One toolchain
   cost came along with the dependency: `connect-client` 1.1.0 forces
   `compileSdk 36` and AGP 8.9.1 in every stable release back to 1.1.0-beta02, so
   there was no way to add it without moving both — `targetSdk` stays at 35,
   since raising it is a separate decision this milestone had no reason to make.

8. **Training plan & coach** — imports an AI-written race plan and coaches
   runs against it. *No AI call in the app:* `PlanPrompt` writes a prompt
   carrying the whole contract (schema, week/day numbering, "JSON only"), the
   runner takes it to any assistant, and `PlanParser` reads the reply back. The
   parser is forgiving about how a chat reply arrives (fences, preamble,
   trailing commas, week/day or dates, /mi or /km, nested `weeks`) because the
   reply comes through the clipboard. Week/day numbering anchored on
   `startDate` is asked for in place of calendar dates, because models get
   weekdays wrong over a 52-week span. The same anchor lets a reply that ran out
   of room be finished in a second chunk and merged. Two tables arrive in
   database v4: `training_plans` (one row is kept) and `plan_workouts`, whose
   `completedRunId` is `ON DELETE SET NULL` rather than cascade, so deleting a
   run from history never un-does the plan's record that the day was done.
   *Coaching follows the announcement design:* `WorkoutCoach` is pure and
   decides when to speak (intro after "Go", a pace verdict plus distance left
   after each mile line, halfway for timed workouts, target reached once), and
   `AnnouncementText` owns the words. `RunTracker` holds the coach for the run
   it is recording and reports the finished run through a `WorkoutLog` seam,
   so tracker tests need no database. The verdict rules are coaching choices,
   not math: no verdict on intervals or tempo (mile averages mix reps and
   recoveries), no "too slow" on easy or recovery days, and "slow" read kindly
   except on race day. The Plan tab's coach card comes from `PlanOverview.of`,
   also pure, which ranks race week and taper above missed runs, missed runs
   above pace drift, and stays quiet about deliberately skipped workouts.
   *Surviving a process death:* the tracker links the workout to its run the
   moment the run row exists, as `status = PLANNED` with `completedRunId` set,
   which means "a run in progress" and nothing else (`toDomain` hides it).
   `RECOVER_INTERRUPTED_RUNS` then settles it on the next open, before it
   clears `isInProgress`. The run is completed if the salvaged run passed
   `WorkoutCoach.MIN_COMPLETION_METERS`, otherwise the link is released. This
   needed no schema change. The coaching itself does not resume, because the run
   does not: a salvaged run is a finished run. The full backup (version 2)
   carries the plan in `PlanParser`'s own format, and the parser looks inside
   a backup file for it. Statuses are restored, but run links are not: a
   backup's run ids need not exist on the device it is restored to.

9. **Self-update** — release builds update themselves from this repository's
   GitHub Releases, from an **App updates** section on the Profile screen.
   A tap on **Check for updates**, or a daily background check the user opts
   into (**off by default**, like Health Connect, and in the privacy policy
   for the same reason), asks GitHub's `releases/latest` API whether a newer
   version exists. The daily check only posts a notification, once per
   version. Installing is always a tap in the app. *No Play Store, so no Play
   in-app updates:* the app downloads its own APK and hands it to
   `PackageInstaller`. That makes the download the attack surface, and the
   design is about what has to be true before the installer sees a file.
   `ReleaseParser` is pure and strict. It accepts only a non-draft,
   non-prerelease release tagged exactly `vX.Y.Z`, the asset the release
   workflow uploads (`MyRunningApp-<tag>.apk`), a download URL under this
   repository's `releases/download/<tag>/` on HTTPS, and a SHA-256 `digest` on
   the asset. A release-notes link is kept only if it points at this
   repository's releases. `DigestingCopy` streams the download to private
   cache under a fixed filename, so nothing from the network reaches a path,
   while hashing it. It aborts as soon as the stream runs past the expected
   size and refuses a short or mismatched file. The digest arrives over TLS
   from the API, so which CDN host serves the bytes doesn't matter.
   `UpdateManager` then checks that the archive's package name is this app's
   and its `versionCode` is higher, so a bad file fails with a clear message
   rather than an installer dialog. The signing key is deliberately left to
   Android, which already refuses any APK not signed like the installed app,
   and the installer's conflict status is translated into "signed
   differently". *The run comes first:* an install replaces the app and kills
   its process, which would end a recording. The install button is disabled
   during an activity, and `UpdateManager` checks `RunTracker` again
   immediately before committing the session, because a download takes long
   enough for a run to start meanwhile. *The confirmation is state, not a
   side effect:* the installer answers through an unexported receiver with a
   confirmation `Intent`. Android blocks activity starts from the background,
   so if the user left the app mid-download the receiver's attempt to open
   the dialog fails silently. The intent is therefore kept in an
   `AwaitingConfirmation` state and offered as **Finish installing**, which
   the screen starts from the foreground activity. **Start over** abandons
   this app's earlier installer sessions and downloads again, for an install
   that never gets an answer. Downloads run in the manager's own scope, so
   leaving Profile doesn't cancel them, and repeat taps are ignored while one
   is running. The first self-install needs "Install unknown apps" and a
   confirmation. From Android 12, sessions ask for `USER_ACTION_NOT_REQUIRED`,
   so later updates can skip the dialog once the app is its own installer of
   record. Debug builds (`.debug` suffix, debug key) can't take a release APK,
   so they show a note instead. *Release discipline is enforced, not
   remembered:* the updater offers a release on `versionName`, but Android
   compares `versionCode`. A missed bump would ship a release every updater
   offers and then fails to install, so `release.yml` refuses a tag whose
   `versionCode` isn't higher than the previous version tag's. 0.3.0 predates
   all of this, so 0.3.1 had to be sideloaded by hand. It is the first release
   that can update itself, and the next release is the first real test of the
   install path.

## Verification

- **Per milestone:** `./gradlew assembleDebug` builds; `./gradlew testDebugUnitTest`
  passes; app installs and the milestone's feature works on a device/emulator.
- **Distance/splits accuracy:** run the GPS replay harness against a sample trace
  with a known measured distance; assert total distance within ~2% and mile-split
  count/timing correct.
- **Announcements:** replay a >1-mile trace on a device; confirm the voice line
  fires at each mile with correct distance/time/last-mile pace, and that it ducks
  playing audio.
- **Screen-off tracking:** real 1-mile walk with the phone locked in a pocket;
  confirm the track is continuous and distance is plausible.
- **History:** complete a run; confirm it appears in the list with correct
  distance/time/pace/calories and that the detail route map matches where you went.
- **Persistence:** force-stop the app mid-run; reopen; confirm the run either
  resumes (service alive) or the partial track was saved (service killed).
- **Health Connect sync:** with the toggle off, confirm the app behaves exactly
  as before. Switch it on and grant permission: existing history backfills and
  the settings line settles on "Synced N runs." Complete a run and confirm it
  appears in Health Connect within a minute, with distance, duration, calories,
  laps and its route, and that a reader app (Fitbit, Samsung Health, Strava)
  shows it too. Correct a run's activity type and confirm the Health Connect
  entry changes in place rather than duplicating; delete a run and confirm it
  disappears. Revoke permission and finish a run: the app must not crash or nag,
  the settings line must read "Permission needed," and re-granting must publish
  the queued run. Decline the permission dialog twice, then confirm "Grant
  permission" opens Health Connect's own settings instead of doing nothing.
  Grant the permission from inside Health Connect's settings and return within a
  couple of seconds: the section must stop saying "Permission needed"
  immediately, not only after several seconds. On a device with no Health
  Connect, confirm the settings section is absent entirely.
- **Self-update:** on a release build, **Check for updates** reports "latest
  version" when it is, which also proves the minified build still parses
  GitHub's JSON. Debug builds show the release-builds-only note. With a newer
  release published: install it from Profile, allowing "Install unknown apps"
  when sent there, and confirm the install carries on when you come back.
  Start a download, leave the app, and come back: **Finish installing** must
  open the dialog. Back out of the dialog: the update must be offered again.
  Start an activity, then try to install: the button is disabled and says why.
  Start an activity mid-download: the install is postponed, not committed.
  Switch on daily checks with notifications blocked: the section says new
  versions will only show there. After updating once, the next update should
  install without a dialog on Android 12+.
