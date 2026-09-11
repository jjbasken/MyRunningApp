# Health Connect workout sync — design

**Status:** approved design, not yet implemented
**Lands as:** milestone 7 in `docs/design.md`

## Context

The app stores runs on-device only; a GPX/JSON export is the backup. That keeps
it private and free of accounts, but it also means nothing else on the phone can
see a run. Health Connect closes that gap without giving up any of it: it is a
local, on-device store, no account, no API key, no billing, no network. Other
apps — Fitbit, Samsung Health, Strava, the phone's health dashboard — read from
it with the user's per-app consent.

**Not Google Fit.** The Google Fit Android and REST APIs are deprecated and being
shut down; Health Connect is their replacement. There is no product named "Google
Health".

### Decisions made with the user

| Topic | Decision |
|---|---|
| Direction | **Write only.** The app publishes its runs; it does not import other apps' workouts. |
| Trigger | Automatic on finish, plus a **one-time backfill** of existing history when the feature is switched on. |
| Lifecycle | **Mirror edits and deletes.** Deleting a run deletes its session; correcting the activity type rewrites it. |
| Payload | **Numbers plus the GPS route**, degrading to numbers-only where route writing is unavailable or unpermitted. |
| Default | **Off.** The feature is opt-in and the app is fully functional without it. |
| Engine | Outbox in Room, drained by a WorkManager worker. |

### Non-goals

- Reading workouts recorded by other apps (rejected: needs dedup rules and roughly
  doubles the surface area for no benefit the user asked for).
- Deleting previously-written health data when the toggle is switched off.
  Health Connect's own UI does this better than a button here could.
- Any network, account, or backend. Unchanged from the base design.

## Architecture

New package `data/health/`, following the split the rest of the project uses —
a pure decision pulled out of each Android-facing piece:

```
data/health/
  HealthConnectGateway.kt       interface: availability, permissions, write, delete
  HealthConnectGatewayImpl.kt   the only type that mentions androidx.health
  WorkoutRecordBuilder.kt       pure: (Run, Splits, Points) -> HealthWorkout?
  HealthSyncEngine.kt           drains the outbox; owns retry + state transitions
  HealthSyncWorker.kt           WorkManager shell around the engine
  HealthSyncStatus.kt           pure: (sdk status, toggle, permissions, counts) -> UI state
domain/model/
  HealthWorkout.kt              platform-free description of one workout to publish
```

`HealthConnectGateway` is an interface for the same reason `Announcer` and
`RunRecorder` are: the engine's rules are then testable on the JVM against a fake,
with no device and no Health Connect installed.

### Dependencies

- `androidx.health.connect:connect-client` (requires `minSdk 26` — already met)
- `androidx.work:work-runtime-ktx` + `androidx.hilt:hilt-work` (new to the project)

## Data model

Room `version = 2` -> `3`.

**`runs.healthSyncState`** — `TEXT NOT NULL DEFAULT 'NOT_SYNCED'`

| State | Meaning |
|---|---|
| `NOT_SYNCED` | Never queued. The state of every row after the migration. |
| `NOT_APPLICABLE` | Nothing publishable in it — no distance, or no duration, so the builder returns null. Excluded from every count and from *Sync now*, so a run that can never be published does not sit in the failure count forever. Added 2026-09-10 during the final review. |
| `PENDING` | Queued for write. Set by `finishRun`, by `updateActivityType`, and by backfill. |
| `SYNCED` | Written to Health Connect. |
| `FAILED` | Rejected for a reason retrying will not fix. Surfaced in settings; retried only by *Sync now*. |

**`health_deletions`** — new table.

| Column | Type |
|---|---|
| `runId` | `INTEGER PRIMARY KEY` |
| `requestedAt` | `INTEGER NOT NULL` (epoch millis) |

Deleting a run destroys the row that would have remembered the deletion, so a
queued delete needs somewhere else to live. `deleteRun` inserts here **only if**
the run was `SYNCED`; the worker removes the row once Health Connect confirms.
No foreign key — the point of the row is that the run is gone.

**Migration 2 -> 3**

```sql
ALTER TABLE runs ADD COLUMN healthSyncState TEXT NOT NULL DEFAULT 'NOT_SYNCED';
CREATE TABLE IF NOT EXISTS health_deletions (
    runId INTEGER NOT NULL PRIMARY KEY,
    requestedAt INTEGER NOT NULL
);
```

The migration deliberately does **not** mark anything `PENDING`: nothing may be
written before the user consents. Backfill is a separate step (below).

### `clientRecordId` is what makes this cheap

Every record is written with `clientRecordId = "run-<id>"`. Health Connect treats
a second insert with the same client id as an update, and accepts deletes by
client id. Therefore:

- Health Connect's own record uids are never stored.
- An edit is just "write it again".
- A retry after an ambiguous failure is safe, rather than a duplicate workout in
  the user's health data.

## What a run becomes

`WorkoutRecordBuilder` is a pure function. Every mapping decision below is a unit
test.

| Local | Health Connect |
|---|---|
| Run + activity type | `ExerciseSessionRecord`, `RUNNING` or `WALKING` |
| `startedAt`..`endedAt` | session start/end — elapsed, the honest wall clock |
| `segmentIndex` gaps | one `ExerciseSegment` per active stretch, so a reader sees moving time rather than counting pauses as running |
| mile splits | one `ExerciseLap` per split, including the partial final one |
| `distanceMeters` | `DistanceRecord` |
| `calories` | `ActiveCaloriesBurnedRecord` — active, not total: the MET estimate excludes the resting baseline |
| `RunPoint` track | `ExerciseRoute` attached to the session |

Metadata records the app as the recording device with an actively-recorded
recording method, so readers can tell a tracked run from a hand-typed one.

**Route is the one conditional path.** The same session record is written either
way, with the route attached only when the route permission is granted and the
installed Health Connect supports it. An older Health Connect or a declined route
permission costs the map and nothing else.

## Sync engine

The engine is a loop over the outbox, and every step is idempotent.

1. If the toggle is off, or the SDK is unavailable, or the required permissions
   are not granted: **stop, change nothing.** Revocation is not a write failure —
   nothing is marked `FAILED`, so no work is lost if permission is granted later.
2. Drain `health_deletions`: `delete(clientRecordId)`, then remove the row.
   Deletions go first so a delete-then-rewrite race cannot resurrect a run.
3. Drain runs in `PENDING`, oldest first: build the workout, `write`, mark
   `SYNCED`. A transient failure leaves the row `PENDING` for the next run of the
   worker; a permanent rejection marks it `FAILED`.

**Enqueue points:** run finish, activity-type edit, run delete, toggle switched
on, and *Sync now*. The work is unique-named with `ExistingWorkPolicy.APPEND_OR_REPLACE`
so a burst of edits collapses into one drain.

**Backfill** is one statement when the toggle is first switched on:

```sql
UPDATE runs SET healthSyncState = 'PENDING' WHERE isInProgress = 0 AND healthSyncState = 'NOT_SYNCED'
```

then the ordinary worker drains it. A process death mid-backfill costs nothing —
the un-drained rows are still `PENDING`.

In-progress runs are never synced; a run enters the outbox only when it finishes.

## Permissions, availability, and the settings UI

Health Connect permissions are their own system and do **not** go through the
existing `PermissionGate`.

**Manifest:** `WRITE_EXERCISE`, `WRITE_DISTANCE`, `WRITE_ACTIVE_CALORIES_BURNED`,
`WRITE_EXERCISE_ROUTE`; a `<queries>` entry so the app can see the Health Connect
package on Android 13; and an activity answering the permissions-rationale intent
(both the pre-Android-14 and Android-14+ forms), which shows the privacy policy.

**Availability** — `getSdkStatus()` gives unavailable / update-required /
available:

- *Unavailable* (older phone, no Health Connect): the settings section is hidden
  entirely rather than offering a toggle that cannot work.
- *Update required*: a one-line prompt with a Play link.
- *Available*: the normal toggle.

**Granting:** Android permanently stops showing the Health Connect permission
dialog after two declines — the same trap as background location, so it gets the
same treatment. A `healthPermissionAsked` preference remembers that the
conversation has happened, and once the dialog is spent the app links into Health
Connect's settings instead of firing an intent that silently does nothing.

**Revocation** can happen at any moment, including mid-backfill. Handled by step 1
of the engine. Surfaced in settings; never nagged about.

**Settings screen** gains one section:

- Master toggle, **off by default**.
- Status line: `Synced 42 runs` / `3 waiting` / `Permission needed`. There is no
  "not installed" line: when Health Connect is absent the whole section is hidden,
  per the availability rule above. (Corrected 2026-09-10 — the original draft listed
  both, which contradicted itself; the implementation hides, which is the better call.)
- Manual **Sync now** (also the only retry path for `FAILED` rows).
- Switching the toggle off stops future syncing and leaves already-written data
  alone, with a line saying so.

Route sharing follows the route permission rather than adding a second app-level
toggle: one switch, one meaning.

`HealthSyncStatus` turns (sdk status, toggle, granted permissions, pending count,
failed count) into that UI state as a pure function, tested without a device —
the same shape as `PermissionGate` and `RunNotificationSpec`.

## Documents

**`docs/privacy-policy.md`** — a real policy, and an unusually easy one to state
honestly: location and body metrics are collected, everything stays on the device,
nothing is transmitted, no account, no analytics, no ads, no third-party sharing.
It covers what the app writes into Health Connect, notes that data is then
governed by Google's terms and the user's choices in that app, and explains
deletion (delete the run; uninstall; Health Connect's own controls). Play requires
it at a **public URL**. **Written and published 2026-09-09:** the source lives at
`docs/privacy-policy.md`, and a standalone page is served from the `gh-pages`
branch at <https://jjbasken.github.io/MyRunningApp/>. Contact is the repository's
issue tracker rather than an email address.

**`docs/play-health-declaration.md`** — a worksheet, since the declaration itself
is a form in the Play Console: each permission requested, the user-facing
justification for each, the data-use answers (not transferred off device, not
sold, not used for advertising), the policy URL, and the app-category answers.
Sideloading needs none of this; publishing to Play does.

## Testing

**JVM unit tests** (the bulk):

- `WorkoutRecordBuilder`: pause gaps become segments; splits become laps including
  the partial final one; RUN vs WALK maps correctly; route omitted cleanly when
  unpermitted; calories map to *active* burn; a zero-distance run does not produce
  a nonsense record.
- `HealthSyncEngine` against `FakeHealthConnectGateway`: pending drains to synced;
  a transient failure leaves the row pending and retries; an edit rewrites under
  the same client id rather than duplicating; a queued delete drains and clears;
  deletions drain before writes; revocation mid-drain leaves the queue intact and
  marks nothing failed; a full-history backfill drains completely.
- `HealthSyncStatus`: every availability/permission/toggle combination.

**Instrumented:** the 2 -> 3 Room migration.

**Field checklist:** grant, complete a run, confirm the workout *and its route*
appear in Health Connect and in one reader app; edit the activity type and confirm
it updates in place rather than duplicating; delete and confirm it disappears;
revoke permission and confirm the app degrades quietly; switch the toggle on with
existing history and confirm the backfill completes.

## Verification

- `./gradlew assembleDebug` builds; `./gradlew testDebugUnitTest` passes.
- Every existing test and every existing flow works unchanged with the toggle off
  or Health Connect absent — this milestone is strictly additive.
- The field checklist above passes on a real device.
