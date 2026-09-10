# Health Connect Workout Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish every finished run to Health Connect — automatically, opt-in, off by default — so other apps on the phone can read the user's workouts.

**Architecture:** A Room outbox (`runs.healthSyncState` plus a `health_deletions` table) records what needs writing; a WorkManager worker drains it through a `HealthConnectGateway` interface. Every record is written with `clientRecordId = "run-<id>"`, so Health Connect treats a repeat write as an update and accepts deletes by client id — no Health Connect uids are stored and retries cannot duplicate a workout. Each Android-facing piece has a pure decision pulled out of it (`WorkoutRecordBuilder`, `HealthTimeline`, `HealthSyncStatus`), which is where the tests live.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, Coroutines/Flow, `androidx.health.connect:connect-client`, `androidx.work` (both new to this project). Tests: JUnit4, Robolectric, coroutines-test, Turbine.

**Spec:** `docs/superpowers/specs/2026-09-09-health-connect-sync-design.md`

## Global Constraints

- `minSdk 26`, `targetSdk 35`, `compileSdk 35`, JVM target 17 — all unchanged.
- `domain/` stays pure Kotlin: **no `android.*` and no `androidx.health.*` imports** in `domain/`. This is why `HealthWorkout` is a platform-free model.
- `androidx.health.*` types appear in exactly one file: `data/health/HealthConnectGatewayImpl.kt`. Everything else talks to the `HealthConnectGateway` interface.
- The feature is **off by default**. Every existing flow and test must pass unchanged with the toggle off or Health Connect absent.
- `clientRecordId` format is exactly `"run-<id>"` (e.g. `run-42`). Nothing else derives ids.
- New dependency versions: `connect-client 1.1.0`, `work-runtime-ktx 2.10.0`, `androidx.hilt 1.2.0`.
- Room goes `version = 2` → `version = 3`. The exported schema `app/schemas/.../3.json` is checked in.
- Existing conventions: Kotlin tests use backticked names, `org.junit.Assert.*`, Robolectric for anything needing a `Context`.
- Every task ends with a commit. Run `./gradlew testDebugUnitTest` before each commit.

---

### Task 1: `HealthWorkout` model and the pure builder

The platform-free description of one workout, plus the pure function that turns a
run into it. No new dependencies — this task is pure Kotlin and pure TDD.

The subtle part is **laps**. A `Split` carries `durationSec` (moving time) but no
timestamps, and Health Connect rejects laps that fall outside the session or
overlap. So lap boundaries are computed on the *active* timeline: the segments
(pause-separated stretches of the run) are walked to convert a moving-time offset
into a wall-clock `Instant`. That guarantees laps sit inside the session and never
land in a pause.

**Files:**
- Create: `app/src/main/java/com/myrunningapp/domain/model/HealthWorkout.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/health/HealthTimeline.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/health/WorkoutRecordBuilder.kt`
- Test: `app/src/test/java/com/myrunningapp/domain/health/HealthTimelineTest.kt`
- Test: `app/src/test/java/com/myrunningapp/domain/health/WorkoutRecordBuilderTest.kt`

**Interfaces:**
- Consumes: `Run`, `Split`, `RunPoint`, `ActivityType` from `domain/model`.
- Produces:
  - `data class HealthWorkout(clientRecordId: String, activityType: ActivityType, startedAt: Instant, endedAt: Instant, distanceMeters: Double, activeCalories: Int, title: String, segments: List<HealthSegment>, laps: List<HealthLap>, route: List<HealthRoutePoint>)`
  - `data class HealthSegment(startedAt: Instant, endedAt: Instant)`
  - `data class HealthLap(startedAt: Instant, endedAt: Instant, distanceMeters: Double)`
  - `data class HealthRoutePoint(time: Instant, latitude: Double, longitude: Double, altitudeMeters: Double, horizontalAccuracyMeters: Float)`
  - `fun healthClientRecordId(runId: Long): String`
  - `object HealthTimeline { fun segments(run: Run, points: List<RunPoint>): List<HealthSegment>; fun instantAt(segments: List<HealthSegment>, movingOffsetSec: Long): Instant }`
  - `object WorkoutRecordBuilder { fun build(run: Run, splits: List<Split>, points: List<RunPoint>, includeRoute: Boolean): HealthWorkout? }`

- [ ] **Step 1: Write the failing timeline test**

Create `app/src/test/java/com/myrunningapp/domain/health/HealthTimelineTest.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSegment
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HealthTimelineTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun point(offsetSec: Long, segmentIndex: Int) = RunPoint(
        id = 0, runId = 1, timestamp = t0.plusSeconds(offsetSec),
        latitude = 40.0, longitude = -105.0, altitudeMeters = 1600.0,
        accuracyMeters = 5f, segmentIndex = segmentIndex,
    )

    private fun run(endOffsetSec: Long) = Run(
        id = 1, startedAt = t0, endedAt = t0.plusSeconds(endOffsetSec),
        activityType = ActivityType.RUN, distanceMeters = 1000.0,
        movingDurationSec = 300, elapsedDurationSec = endOffsetSec,
        avgPaceSecPerMile = 480.0, calories = 100, weightKgAtRun = 70.0,
    )

    @Test
    fun `one segment spans the whole run when it was never paused`() {
        val segments = HealthTimeline.segments(run(300), listOf(point(0, 0), point(300, 0)))

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(300))), segments)
    }

    @Test
    fun `a pause splits the run into two segments with a gap between them`() {
        val points = listOf(point(0, 0), point(100, 0), point(400, 1), point(500, 1))

        val segments = HealthTimeline.segments(run(500), points)

        assertEquals(
            listOf(
                HealthSegment(t0, t0.plusSeconds(100)),
                HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
            ),
            segments,
        )
    }

    @Test
    fun `a run with no points still reports one segment covering it`() {
        val segments = HealthTimeline.segments(run(300), emptyList())

        assertEquals(listOf(HealthSegment(t0, t0.plusSeconds(300))), segments)
    }

    @Test
    fun `a moving offset inside the first segment is that many seconds after the start`() {
        val segments = listOf(
            HealthSegment(t0, t0.plusSeconds(100)),
            HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
        )

        assertEquals(t0.plusSeconds(60), HealthTimeline.instantAt(segments, 60))
    }

    @Test
    fun `a moving offset past the first segment skips the paused stretch`() {
        val segments = listOf(
            HealthSegment(t0, t0.plusSeconds(100)),
            HealthSegment(t0.plusSeconds(400), t0.plusSeconds(500)),
        )

        // 100 s of moving time happened before the pause; 30 s more lands 30 s
        // into the second segment, not 130 s after the start.
        assertEquals(t0.plusSeconds(430), HealthTimeline.instantAt(segments, 130))
    }

    @Test
    fun `an offset beyond all moving time clamps to the last segment's end`() {
        val segments = listOf(HealthSegment(t0, t0.plusSeconds(100)))

        assertEquals(t0.plusSeconds(100), HealthTimeline.instantAt(segments, 9_999))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthTimelineTest*'`
Expected: FAIL — unresolved references `HealthTimeline`, `HealthSegment`.

- [ ] **Step 3: Write the model**

Create `app/src/main/java/com/myrunningapp/domain/model/HealthWorkout.kt`:

```kotlin
package com.myrunningapp.domain.model

import java.time.Instant

/**
 * One workout, described without reference to any platform type, ready to be
 * handed to Health Connect.
 *
 * Keeping this platform-free is what lets the mapping decisions
 * ([com.myrunningapp.domain.health.WorkoutRecordBuilder]) be unit-tested on the
 * JVM: only the gateway implementation ever mentions `androidx.health`.
 */
data class HealthWorkout(
    /** Stable per-run id. Health Connect upserts on it, so a rewrite is not a duplicate. */
    val clientRecordId: String,
    val activityType: ActivityType,
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceMeters: Double,
    /** Active burn, not total: the MET estimate already excludes the resting baseline. */
    val activeCalories: Int,
    val title: String,
    /** The stretches the user was actually moving; the gaps between them are pauses. */
    val segments: List<HealthSegment>,
    val laps: List<HealthLap>,
    /** Empty when the route is not being shared. The rest of the workout is unaffected. */
    val route: List<HealthRoutePoint>,
)

data class HealthSegment(val startedAt: Instant, val endedAt: Instant)

data class HealthLap(
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceMeters: Double,
)

data class HealthRoutePoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val horizontalAccuracyMeters: Float,
)

/** The one place a run's Health Connect id is derived. */
fun healthClientRecordId(runId: Long): String = "run-$runId"
```

- [ ] **Step 4: Write the timeline**

Create `app/src/main/java/com/myrunningapp/domain/health/HealthTimeline.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSegment
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import java.time.Instant

/**
 * Turns a run's pause structure into wall-clock intervals, and maps moving-time
 * offsets onto them.
 *
 * Splits record how long a mile took to *run*, with no timestamps; Health
 * Connect wants laps on the wall clock, inside the session and clear of the
 * pauses. Walking the segments is what reconciles the two — a mile that finished
 * 130 s into the moving time of a run that was paused at 100 s ends 30 s into the
 * second segment, not 130 s after the start.
 */
object HealthTimeline {

    /**
     * One interval per `segmentIndex`, from its first fix to its last. A run with
     * no points (possible if GPS never delivered) still gets one interval, so a
     * session is never built with nothing inside it.
     */
    fun segments(run: Run, points: List<RunPoint>): List<HealthSegment> {
        if (points.isEmpty()) return listOf(HealthSegment(run.startedAt, run.endedAt))
        return points
            .groupBy { it.segmentIndex }
            .toSortedMap()
            .map { (_, segmentPoints) ->
                HealthSegment(
                    startedAt = segmentPoints.minOf { it.timestamp },
                    endedAt = segmentPoints.maxOf { it.timestamp },
                )
            }
    }

    /**
     * The instant reached after [movingOffsetSec] seconds of moving. Offsets past
     * the end of the run clamp to its last moment rather than running off into
     * the paused time that follows.
     */
    fun instantAt(segments: List<HealthSegment>, movingOffsetSec: Long): Instant {
        require(segments.isNotEmpty()) { "a workout needs at least one segment" }
        var remaining = movingOffsetSec.coerceAtLeast(0)
        for (segment in segments) {
            val length = segment.endedAt.epochSecond - segment.startedAt.epochSecond
            if (remaining <= length) return segment.startedAt.plusSeconds(remaining)
            remaining -= length
        }
        return segments.last().endedAt
    }
}
```

- [ ] **Step 5: Run the timeline tests**

Run: `./gradlew testDebugUnitTest --tests '*HealthTimelineTest*'`
Expected: PASS (6 tests).

- [ ] **Step 6: Write the failing builder test**

Create `app/src/test/java/com/myrunningapp/domain/health/WorkoutRecordBuilderTest.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkoutRecordBuilderTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun run(
        activityType: ActivityType = ActivityType.RUN,
        distanceMeters: Double = 3218.68,
        movingDurationSec: Long = 1080,
        elapsedDurationSec: Long = 1200,
    ) = Run(
        id = 42, startedAt = t0, endedAt = t0.plusSeconds(elapsedDurationSec),
        activityType = activityType, distanceMeters = distanceMeters,
        movingDurationSec = movingDurationSec, elapsedDurationSec = elapsedDurationSec,
        avgPaceSecPerMile = 540.0, calories = 320, weightKgAtRun = 70.0,
    )

    private fun point(offsetSec: Long, segmentIndex: Int = 0) = RunPoint(
        id = 0, runId = 42, timestamp = t0.plusSeconds(offsetSec),
        latitude = 40.0 + offsetSec / 100_000.0, longitude = -105.0,
        altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = segmentIndex,
    )

    private fun split(number: Int, durationSec: Long, distanceMeters: Double = 1609.34) =
        Split(
            id = number.toLong(), runId = 42, splitNumber = number,
            distanceMeters = distanceMeters, durationSec = durationSec,
            paceSecPerMile = durationSec.toDouble(),
        )

    @Test
    fun `client record id is the run id`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false)

        assertEquals("run-42", workout!!.clientRecordId)
    }

    @Test
    fun `the session spans elapsed time, pauses included`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false)!!

        assertEquals(t0, workout.startedAt)
        assertEquals(t0.plusSeconds(1200), workout.endedAt)
    }

    @Test
    fun `a walk is built as a walk`() {
        val workout = WorkoutRecordBuilder
            .build(run(activityType = ActivityType.WALK), emptyList(), listOf(point(0)), false)!!

        assertEquals(ActivityType.WALK, workout.activityType)
    }

    @Test
    fun `calories map to the run's estimate`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0)), false)!!

        assertEquals(320, workout.activeCalories)
    }

    @Test
    fun `pause gaps become separate segments`() {
        val points = listOf(point(0), point(100), point(400, 1), point(1200, 1))

        val workout = WorkoutRecordBuilder.build(run(), emptyList(), points, false)!!

        assertEquals(2, workout.segments.size)
        assertEquals(t0.plusSeconds(100), workout.segments[0].endedAt)
        assertEquals(t0.plusSeconds(400), workout.segments[1].startedAt)
    }

    @Test
    fun `each split becomes a lap laid on the moving timeline`() {
        val splits = listOf(split(1, 540), split(2, 540))
        val points = listOf(point(0), point(1080))

        val workout = WorkoutRecordBuilder.build(run(), splits, points, false)!!

        assertEquals(2, workout.laps.size)
        assertEquals(t0, workout.laps[0].startedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[0].endedAt)
        assertEquals(t0.plusSeconds(540), workout.laps[1].startedAt)
        assertEquals(t0.plusSeconds(1080), workout.laps[1].endedAt)
    }

    @Test
    fun `laps stay inside the session when the run was paused`() {
        val splits = listOf(split(1, 540), split(2, 540))
        // Moving time is 1080 s but the run took 1200 s: a 120 s pause at 540 s.
        val points = listOf(point(0), point(540), point(660, 1), point(1200, 1))

        val workout = WorkoutRecordBuilder.build(run(), splits, points, false)!!

        assertTrue(workout.laps.all { !it.startedAt.isBefore(workout.startedAt) })
        assertTrue(workout.laps.all { !it.endedAt.isAfter(workout.endedAt) })
        // The second mile begins after the pause, not during it.
        assertEquals(t0.plusSeconds(660), workout.laps[1].startedAt)
    }

    @Test
    fun `the partial final split becomes a lap with its own shorter distance`() {
        val splits = listOf(split(1, 540), split(2, 200, distanceMeters = 600.0))

        val workout = WorkoutRecordBuilder
            .build(run(distanceMeters = 2209.34, movingDurationSec = 740), splits, listOf(point(0), point(740)), false)!!

        assertEquals(600.0, workout.laps[1].distanceMeters, 0.001)
    }

    @Test
    fun `the route is omitted when it is not being shared`() {
        val workout = WorkoutRecordBuilder.build(run(), emptyList(), listOf(point(0), point(10)), false)!!

        assertTrue(workout.route.isEmpty())
    }

    @Test
    fun `the route carries every fix when it is being shared`() {
        val points = listOf(point(0), point(10), point(20))

        val workout = WorkoutRecordBuilder.build(run(), emptyList(), points, true)!!

        assertEquals(3, workout.route.size)
        assertEquals(t0, workout.route.first().time)
        assertEquals(-105.0, workout.route.first().longitude, 0.0)
    }

    @Test
    fun `a run that never moved is not worth writing`() {
        val zeroLength = run(distanceMeters = 0.0, movingDurationSec = 0, elapsedDurationSec = 0)

        assertNull(WorkoutRecordBuilder.build(zeroLength, emptyList(), emptyList(), false))
    }
}
```

- [ ] **Step 7: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*WorkoutRecordBuilderTest*'`
Expected: FAIL — unresolved reference `WorkoutRecordBuilder`.

- [ ] **Step 8: Write the builder**

Create `app/src/main/java/com/myrunningapp/domain/health/WorkoutRecordBuilder.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthLap
import com.myrunningapp.domain.model.HealthRoutePoint
import com.myrunningapp.domain.model.HealthWorkout
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import com.myrunningapp.domain.model.healthClientRecordId

/**
 * Turns a finished run into the workout that gets published.
 *
 * Every mapping decision lives here rather than in the gateway, so all of them
 * are unit-tested without a device or an installed Health Connect.
 */
object WorkoutRecordBuilder {

    /**
     * Returns null for a run with nothing in it. A zero-length session is
     * rejected by Health Connect anyway, and a run that never moved is not a
     * workout worth publishing.
     */
    fun build(
        run: Run,
        splits: List<Split>,
        points: List<RunPoint>,
        includeRoute: Boolean,
    ): HealthWorkout? {
        if (!run.endedAt.isAfter(run.startedAt)) return null
        if (run.distanceMeters <= 0.0) return null

        val segments = HealthTimeline.segments(run, points)

        var movingOffsetSec = 0L
        val laps = splits.sortedBy { it.splitNumber }.map { split ->
            val startedAt = HealthTimeline.instantAt(segments, movingOffsetSec)
            movingOffsetSec += split.durationSec
            HealthLap(
                startedAt = startedAt,
                endedAt = HealthTimeline.instantAt(segments, movingOffsetSec),
                distanceMeters = split.distanceMeters,
            )
        }

        return HealthWorkout(
            clientRecordId = healthClientRecordId(run.id),
            activityType = run.activityType,
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            distanceMeters = run.distanceMeters,
            activeCalories = run.calories,
            title = when (run.activityType) {
                ActivityType.RUN -> "Run"
                ActivityType.WALK -> "Walk"
            },
            segments = segments,
            laps = laps,
            route = if (includeRoute) {
                points.sortedBy { it.timestamp }.map { point ->
                    HealthRoutePoint(
                        time = point.timestamp,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        altitudeMeters = point.altitudeMeters,
                        horizontalAccuracyMeters = point.accuracyMeters,
                    )
                }
            } else {
                emptyList()
            },
        )
    }
}
```

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — the new tests plus every existing test.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/myrunningapp/domain/model/HealthWorkout.kt \
        app/src/main/java/com/myrunningapp/domain/health \
        app/src/test/java/com/myrunningapp/domain/health
git commit -m "Add the platform-free workout model and its pure builder

Laps are laid on the moving timeline rather than the wall clock, so a
mile that straddles a pause still lands inside the session."
```

---

### Task 2: Room outbox — migration 2 to 3

The sync state column and the deletions table. Nothing writes to them yet.

**Files:**
- Modify: `app/src/main/java/com/myrunningapp/data/db/entity/RunEntity.kt`
- Create: `app/src/main/java/com/myrunningapp/data/db/entity/HealthDeletionEntity.kt`
- Create: `app/src/main/java/com/myrunningapp/data/db/dao/HealthSyncDao.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/model/HealthSyncState.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/model/HealthSyncCounts.kt`
- Modify: `app/src/main/java/com/myrunningapp/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/myrunningapp/di/DatabaseModule.kt`
- Test: `app/src/test/java/com/myrunningapp/data/db/HealthSyncDaoTest.kt`
- Test: `app/src/test/java/com/myrunningapp/data/db/AppDatabaseTest.kt` (add one migration test)

**Interfaces:**
- Consumes: `RunEntity`, `AppDatabase`, `Converters` from Task 0 (existing code).
- Produces:
  - `enum class HealthSyncState { NOT_SYNCED, PENDING, SYNCED, FAILED }`
  - `RunEntity.healthSyncState: HealthSyncState` (defaults to `NOT_SYNCED`)
  - `HealthDeletionEntity(runId: Long, requestedAt: Instant)`
  - `data class HealthSyncCounts(val pending: Int = 0, val synced: Int = 0, val failed: Int = 0)` in `domain/model`
  - `HealthSyncDao` with: `suspend fun pendingRuns(limit: Int = 50): List<RunEntity>`, `suspend fun markState(runId: Long, state: HealthSyncState)`, `suspend fun queueDeletion(deletion: HealthDeletionEntity)`, `suspend fun pendingDeletions(): List<HealthDeletionEntity>`, `suspend fun clearDeletion(runId: Long)`, `suspend fun markAllPending(): Int`, `fun observeCounts(): Flow<HealthSyncCounts>`, `suspend fun retryFailed(): Int`
  - `AppDatabase.MIGRATION_2_3`

- [ ] **Step 1: Write the failing DAO test**

Create `app/src/test/java/com/myrunningapp/data/db/HealthSyncDaoTest.kt`:

```kotlin
package com.myrunningapp.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class HealthSyncDaoTest {

    private lateinit var db: AppDatabase
    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertRun(
        state: HealthSyncState = HealthSyncState.NOT_SYNCED,
        inProgress: Boolean = false,
    ): Long = db.runDao().insert(
        RunEntity(
            startedAt = t0, endedAt = t0.plusSeconds(600), activityType = ActivityType.RUN,
            distanceMeters = 1609.34, movingDurationSec = 600, elapsedDurationSec = 600,
            avgPaceSecPerMile = 600.0, calories = 120, weightKgAtRun = 70.0,
            isInProgress = inProgress, healthSyncState = state,
        ),
    )

    @Test
    fun `a new run starts out unsynced`() = runTest {
        val id = insertRun()

        assertEquals(HealthSyncState.NOT_SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `pending runs are returned oldest first and nothing else is`() = runTest {
        val pending = insertRun(HealthSyncState.PENDING)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.FAILED)

        val rows = db.healthSyncDao().pendingRuns()

        assertEquals(listOf(pending), rows.map { it.id })
    }

    @Test
    fun `an in-progress run is never pending, even if marked`() = runTest {
        insertRun(HealthSyncState.PENDING, inProgress = true)

        assertTrue(db.healthSyncDao().pendingRuns().isEmpty())
    }

    @Test
    fun `marking a state replaces the old one`() = runTest {
        val id = insertRun(HealthSyncState.PENDING)

        db.healthSyncDao().markState(id, HealthSyncState.SYNCED)

        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `backfill marks every finished unsynced run pending and leaves synced ones alone`() = runTest {
        insertRun(HealthSyncState.NOT_SYNCED)
        insertRun(HealthSyncState.NOT_SYNCED)
        val synced = insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.NOT_SYNCED, inProgress = true)

        val marked = db.healthSyncDao().markAllPending()

        assertEquals(2, marked)
        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(synced)!!.healthSyncState)
    }

    @Test
    fun `a queued deletion survives the run row disappearing`() = runTest {
        val id = insertRun(HealthSyncState.SYNCED)
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = id, requestedAt = t0))
        db.runDao().deleteById(id)

        assertEquals(listOf(id), db.healthSyncDao().pendingDeletions().map { it.runId })
    }

    @Test
    fun `clearing a deletion removes it from the queue`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))

        db.healthSyncDao().clearDeletion(7)

        assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
    }

    @Test
    fun `queueing the same deletion twice leaves one row`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0.plusSeconds(5)))

        assertEquals(1, db.healthSyncDao().pendingDeletions().size)
    }

    @Test
    fun `retrying failed runs puts them back in the queue`() = runTest {
        insertRun(HealthSyncState.FAILED)

        assertEquals(1, db.healthSyncDao().retryFailed())
        assertEquals(1, db.healthSyncDao().pendingRuns().size)
    }

    @Test
    fun `counts describe the queue`() = runTest {
        insertRun(HealthSyncState.PENDING)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.FAILED)

        val counts = db.healthSyncDao().observeCounts().first()

        assertEquals(1, counts.pending)
        assertEquals(2, counts.synced)
        assertEquals(1, counts.failed)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncDaoTest*'`
Expected: FAIL — unresolved references `HealthSyncState`, `healthSyncDao`, `HealthDeletionEntity`.

- [ ] **Step 3: Add the state enum**

Create `app/src/main/java/com/myrunningapp/domain/model/HealthSyncState.kt`:

```kotlin
package com.myrunningapp.domain.model

/** Where a run stands with Health Connect. */
enum class HealthSyncState {
    /** Never queued. Every run starts here, and stays here while the feature is off. */
    NOT_SYNCED,

    /** Waiting to be written. Set on finish, on an edit, and by the backfill. */
    PENDING,

    /** Written. A later edit puts it back to [PENDING]. */
    SYNCED,

    /**
     * Rejected for a reason retrying will not fix. Surfaced on the settings
     * screen; only "Sync now" moves it back to [PENDING]. Missing permission is
     * *not* this — that leaves the row pending.
     */
    FAILED,
}
```

- [ ] **Step 4: Add the column, the entity and the DAO**

In `app/src/main/java/com/myrunningapp/data/db/entity/RunEntity.kt`, add the import
`com.myrunningapp.domain.model.HealthSyncState` and add this field after
`wasRecovered` in the constructor (leave `toDomain`/`fromDomain` untouched — the
domain `Run` has no sync state; it is storage bookkeeping):

```kotlin
    @ColumnInfo(defaultValue = "NOT_SYNCED")
    val healthSyncState: HealthSyncState = HealthSyncState.NOT_SYNCED,
```

Create `app/src/main/java/com/myrunningapp/data/db/entity/HealthDeletionEntity.kt`:

```kotlin
package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A run that was deleted locally and still needs deleting from Health Connect.
 *
 * This is a table rather than a column because deleting a run destroys the row
 * that would have remembered the deletion. Deliberately no foreign key: the
 * whole point is that the run is gone.
 */
@Entity(tableName = "health_deletions")
data class HealthDeletionEntity(
    @PrimaryKey val runId: Long,
    val requestedAt: Instant,
)
```

Create `app/src/main/java/com/myrunningapp/data/db/dao/HealthSyncDao.kt`:

```kotlin
package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthSyncDao {

    /** Oldest first, so a backfill publishes history in the order it happened. */
    @Query(
        """
        SELECT * FROM runs
        WHERE healthSyncState = 'PENDING' AND isInProgress = 0
        ORDER BY startedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingRuns(limit: Int = 50): List<RunEntity>

    @Query("UPDATE runs SET healthSyncState = :state WHERE id = :runId")
    suspend fun markState(runId: Long, state: HealthSyncState)

    /** Returns how many rows the backfill queued. */
    @Query(
        """
        UPDATE runs SET healthSyncState = 'PENDING'
        WHERE isInProgress = 0 AND healthSyncState = 'NOT_SYNCED'
        """,
    )
    suspend fun markAllPending(): Int

    @Query("UPDATE runs SET healthSyncState = 'PENDING' WHERE healthSyncState = 'FAILED'")
    suspend fun retryFailed(): Int

    /** Replaces on conflict: a second delete request for the same run is the same request. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun queueDeletion(deletion: HealthDeletionEntity)

    @Query("SELECT * FROM health_deletions ORDER BY requestedAt ASC")
    suspend fun pendingDeletions(): List<HealthDeletionEntity>

    @Query("DELETE FROM health_deletions WHERE runId = :runId")
    suspend fun clearDeletion(runId: Long)

    @Query(
        """
        SELECT
            SUM(healthSyncState = 'PENDING') AS pending,
            SUM(healthSyncState = 'SYNCED') AS synced,
            SUM(healthSyncState = 'FAILED') AS failed
        FROM runs WHERE isInProgress = 0
        """,
    )
    fun observeCounts(): Flow<HealthSyncCounts>
}
```

Create `app/src/main/java/com/myrunningapp/domain/model/HealthSyncCounts.kt`:

```kotlin
package com.myrunningapp.domain.model

/**
 * How many runs sit in each sync state. Drives the settings screen's status
 * line, and lives in `domain` because the pure status decision reads it —
 * `domain` must never have to import from `data`.
 */
data class HealthSyncCounts(
    val pending: Int = 0,
    val synced: Int = 0,
    val failed: Int = 0,
)
```

- [ ] **Step 5: Wire the database up to version 3**

In `app/src/main/java/com/myrunningapp/data/db/AppDatabase.kt`: import
`HealthDeletionEntity` and `HealthSyncDao`, add `HealthDeletionEntity::class` to
`entities`, change `version = 2` to `version = 3`, add
`abstract fun healthSyncDao(): HealthSyncDao`, and add the migration beside
`MIGRATION_1_2`:

```kotlin
        /**
         * Adds the Health Connect outbox. Deliberately leaves every run
         * NOT_SYNCED: nothing may be published before the user switches the
         * feature on, and switching it on is what queues the backfill.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE runs ADD COLUMN healthSyncState TEXT NOT NULL DEFAULT 'NOT_SYNCED'",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_deletions (
                        runId INTEGER NOT NULL PRIMARY KEY,
                        requestedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
```

In `app/src/main/java/com/myrunningapp/di/DatabaseModule.kt`, import `HealthSyncDao`,
change `.addMigrations(AppDatabase.MIGRATION_1_2)` to
`.addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)`, and add:

```kotlin
    @Provides
    fun provideHealthSyncDao(db: AppDatabase): HealthSyncDao = db.healthSyncDao()
```

- [ ] **Step 6: Run the DAO tests**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncDaoTest*'`
Expected: PASS (10 tests). The exported schema `app/schemas/com.myrunningapp.data.db.AppDatabase/3.json` appears; it is checked in.

- [ ] **Step 7: Write the failing migration test**

Append this test to `app/src/test/java/com/myrunningapp/data/db/AppDatabaseTest.kt`,
following the shape of the existing 1→2 migration test in that file (it opens a
v2 database by hand, closes it, then reopens through Room with the migration):

```kotlin
    @Test
    fun `migrating to 3 leaves existing runs unsynced and adds the deletion queue`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The v2 schema, as checked in at app/schemas/.../2.json.
                        val schema = JSONObject(
                            javaClass.classLoader!!
                                .getResourceAsStream("com.myrunningapp.data.db.AppDatabase/2.json")!!
                                .reader().readText(),
                        )
                        val entities = schema.getJSONObject("database").getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            db.execSQL(entities.getJSONObject(i).getString("createSql")
                                .replace("\${TABLE_NAME}", entities.getJSONObject(i).getString("tableName")))
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            helper.writableDatabase.execSQL(
                "INSERT INTO runs VALUES (1, 1000, 31000, 'RUN', 100.0, 30, 30, 482.8, 7, 70.0, 0, 0)",
            )
            helper.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()

            val run = db.runDao().getById(1)!!
            assertEquals(HealthSyncState.NOT_SYNCED, run.healthSyncState)
            assertEquals(100.0, run.distanceMeters, 0.0)
            assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
        } finally {
            helper.close()
            db.close()
            context.deleteDatabase(name)
        }
    }
```

Add `import com.myrunningapp.domain.model.HealthSyncState` at the top of the file
if it is not already there.

- [ ] **Step 8: Run the migration test**

Run: `./gradlew testDebugUnitTest --tests '*AppDatabaseTest*'`
Expected: PASS. If the insert fails on column count, print the v2 `runs` schema
from `app/schemas/com.myrunningapp.data.db.AppDatabase/2.json` and match the
`INSERT` to it exactly — the v2 row is `(id, startedAt, endedAt, activityType,
distanceMeters, movingDurationSec, elapsedDurationSec, avgPaceSecPerMile,
calories, weightKgAtRun, isInProgress, wasRecovered)`.

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/myrunningapp/data/db app/src/main/java/com/myrunningapp/di/DatabaseModule.kt \
        app/src/main/java/com/myrunningapp/domain/model/HealthSyncState.kt \
        app/src/test/java/com/myrunningapp/data/db app/schemas
git commit -m "Add the Health Connect outbox to Room

Deletions need their own table: deleting a run destroys the row that
would otherwise have remembered the deletion."
```

---

### Task 3: Mark the outbox from the repository

The three moments that put work in the queue. Still nothing writes to Health
Connect — this task is about the repository telling the truth about what needs
publishing.

**Files:**
- Modify: `app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt`
- Test: `app/src/test/java/com/myrunningapp/data/repository/RunRepositoryHealthSyncTest.kt`

**Interfaces:**
- Consumes: `HealthSyncDao`, `HealthDeletionEntity`, `HealthSyncState` (Task 2).
- Produces: `RunRepository` constructor gains `healthSyncDao: HealthSyncDao` and
  `clock: Clock = Clock.systemUTC()`, in that order, after `profileRepository`.
  Task 7 inserts `healthSyncScheduler` between them, so `clock` stays last; behaviour of
  `finishRun`, `updateActivityType` and `deleteRun` is extended as below.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/myrunningapp/data/repository/RunRepositoryHealthSyncTest.kt`.
Follow the setup used by the existing repository tests in
`app/src/test/java/com/myrunningapp/data/repository/` (Robolectric plus an
in-memory Room database):

```kotlin
package com.myrunningapp.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.AppDatabase
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.tracking.RunSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RunRepositoryHealthSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RunRepository
    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RunRepository(
            runDao = db.runDao(),
            runPointDao = db.runPointDao(),
            splitDao = db.splitDao(),
            profileRepository = ProfileRepository(db.profileDao()),
            healthSyncDao = db.healthSyncDao(),
            clock = Clock.fixed(t0, ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun finishedRun(): Long {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)
        repository.finishRun(
            runId = id,
            snapshot = RunSnapshot(
                distanceMeters = 1609.34,
                movingDurationSec = 600,
                elapsedDurationSec = 600,
                avgPaceSecPerMile = 600.0,
                completedSplits = emptyList(),
            ),
            endedAt = t0.plusSeconds(600),
        )
        return id
    }

    @Test
    fun `finishing a run queues it for Health Connect`() = runTest {
        val id = finishedRun()

        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `an in-progress run is not queued`() = runTest {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)

        assertEquals(HealthSyncState.NOT_SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `correcting the activity type queues a rewrite`() = runTest {
        val id = finishedRun()
        db.healthSyncDao().markState(id, HealthSyncState.SYNCED)

        repository.updateActivityType(id, ActivityType.WALK)

        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `deleting a synced run queues the deletion`() = runTest {
        val id = finishedRun()
        db.healthSyncDao().markState(id, HealthSyncState.SYNCED)

        repository.deleteRun(id)

        assertEquals(listOf(id), db.healthSyncDao().pendingDeletions().map { it.runId })
        assertEquals(t0, db.healthSyncDao().pendingDeletions().single().requestedAt)
    }

    @Test
    fun `deleting a run that was never synced queues nothing`() = runTest {
        val id = finishedRun()

        repository.deleteRun(id)

        assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
    }

    @Test
    fun `discarding an in-progress run queues nothing`() = runTest {
        val id = repository.startRun(ActivityType.RUN, t0, weightKg = 70.0)

        repository.discardRun(id)

        assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
    }
}
```

If `RunSnapshot`'s constructor differs from the call above, read
`app/src/main/java/com/myrunningapp/domain/tracking/` for its real shape and match
it; the assertions are what matter.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*RunRepositoryHealthSyncTest*'`
Expected: FAIL — `RunRepository` has no `healthSyncDao` parameter.

- [ ] **Step 3: Extend the repository**

In `app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt`:

Add imports:

```kotlin
import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.domain.model.HealthSyncState
import java.time.Clock
```

Add the two constructor parameters after `profileRepository`:

```kotlin
    private val healthSyncDao: HealthSyncDao,
    private val clock: Clock = Clock.systemUTC(),
```

Add this to the end of `finishRun`, after the `runDao.update(...)` call:

```kotlin
        // The run is only worth publishing once it is complete, so the outbox is
        // marked here rather than at startRun.
        healthSyncDao.markState(runId, HealthSyncState.PENDING)
```

Add the same line to the end of `updateActivityType`, after its `runDao.update(...)`:

```kotlin
        // The label and the published workout must agree, so a correction is a rewrite.
        healthSyncDao.markState(runId, HealthSyncState.PENDING)
```

Replace `deleteRun` with:

```kotlin
    /**
     * Deletes a run; its points and splits go with it via `ON DELETE CASCADE`.
     *
     * A run that reached Health Connect leaves a deletion behind in the outbox,
     * because the row that would otherwise have remembered it is about to be
     * gone. A run that never got there needs no such note.
     */
    suspend fun deleteRun(runId: Long) {
        val existing = runDao.getById(runId) ?: return
        if (existing.healthSyncState == HealthSyncState.SYNCED) {
            healthSyncDao.queueDeletion(
                HealthDeletionEntity(runId = runId, requestedAt = clock.instant()),
            )
        }
        runDao.deleteById(runId)
    }
```

Leave `discardRun` alone: an in-progress run was never published.

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests '*RunRepositoryHealthSyncTest*'`
Expected: PASS (6 tests).

- [ ] **Step 5: Fix the other call sites**

Any existing test that constructs `RunRepository` by hand now fails to compile.

Run: `./gradlew testDebugUnitTest`
For each compile error, add `healthSyncDao = db.healthSyncDao(),` to that
constructor call. Hilt call sites need no change — `HealthSyncDao` is provided by
`DatabaseModule` and `Clock` has a default.

Expected after fixing: the whole suite passes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt \
        app/src/test/java/com/myrunningapp/data/repository
git commit -m "Queue runs for Health Connect as they finish, change and go"
```

---

### Task 4: Preferences and the pure status decision

The toggle, the remembered ask, and the function that decides what the settings
screen says. Pure — no Health Connect dependency yet.

**Files:**
- Modify: `app/src/main/java/com/myrunningapp/data/prefs/AppPreferences.kt`
- Modify: `app/src/main/java/com/myrunningapp/data/prefs/PreferencesRepository.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/health/HealthAvailability.kt`
- Create: `app/src/main/java/com/myrunningapp/domain/health/HealthSyncStatus.kt`
- Test: `app/src/test/java/com/myrunningapp/domain/health/HealthSyncStatusTest.kt`

**Interfaces:**
- Consumes: `HealthSyncCounts` (Task 2).
- Produces:
  - `AppPreferences.healthSyncEnabled: Boolean = false`, `AppPreferences.healthPermissionAsked: Boolean = false`
  - `PreferencesRepository.setHealthSyncEnabled(enabled: Boolean)`, `PreferencesRepository.setHealthPermissionAsked(asked: Boolean)`
  - `enum class HealthAvailability { NOT_INSTALLED, UPDATE_REQUIRED, AVAILABLE }`
  - `sealed interface HealthSyncUiState` with `Hidden`, `UpdateRequired`, `Off`, `NeedsPermission`, `Working(pending: Int)`, `UpToDate(synced: Int)`, `Failed(failed: Int, synced: Int)`
  - `object HealthSyncStatus { fun of(availability: HealthAvailability, enabled: Boolean, writePermissionsGranted: Boolean, counts: HealthSyncCounts): HealthSyncUiState }`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/myrunningapp/domain/health/HealthSyncStatusTest.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSyncCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncStatusTest {

    private fun status(
        availability: HealthAvailability = HealthAvailability.AVAILABLE,
        enabled: Boolean = true,
        granted: Boolean = true,
        counts: HealthSyncCounts = HealthSyncCounts(),
    ) = HealthSyncStatus.of(availability, enabled, granted, counts)

    @Test
    fun `the section is hidden when Health Connect is not installed`() {
        assertEquals(
            HealthSyncUiState.Hidden,
            status(availability = HealthAvailability.NOT_INSTALLED, enabled = false),
        )
    }

    @Test
    fun `it stays hidden even if the toggle was left on from a previous device`() {
        assertEquals(
            HealthSyncUiState.Hidden,
            status(availability = HealthAvailability.NOT_INSTALLED, enabled = true),
        )
    }

    @Test
    fun `an outdated Health Connect asks to be updated`() {
        assertEquals(
            HealthSyncUiState.UpdateRequired,
            status(availability = HealthAvailability.UPDATE_REQUIRED),
        )
    }

    @Test
    fun `the toggle being off beats everything else`() {
        assertEquals(
            HealthSyncUiState.Off,
            status(enabled = false, counts = HealthSyncCounts(pending = 3, failed = 1)),
        )
    }

    @Test
    fun `permission missing is reported before any queue counts`() {
        assertEquals(
            HealthSyncUiState.NeedsPermission,
            status(granted = false, counts = HealthSyncCounts(pending = 3)),
        )
    }

    @Test
    fun `a queue that is draining reports what is left`() {
        assertEquals(
            HealthSyncUiState.Working(pending = 3),
            status(counts = HealthSyncCounts(pending = 3, synced = 10)),
        )
    }

    @Test
    fun `failures are reported once the queue has drained`() {
        assertEquals(
            HealthSyncUiState.Failed(failed = 2, synced = 10),
            status(counts = HealthSyncCounts(synced = 10, failed = 2)),
        )
    }

    @Test
    fun `a drained queue reports how many runs are published`() {
        assertEquals(
            HealthSyncUiState.UpToDate(synced = 42),
            status(counts = HealthSyncCounts(synced = 42)),
        )
    }

    @Test
    fun `just switched on with nothing done yet is up to date with zero`() {
        assertEquals(HealthSyncUiState.UpToDate(synced = 0), status())
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncStatusTest*'`
Expected: FAIL — unresolved reference `HealthSyncStatus`.

- [ ] **Step 3: Write availability and status**

Create `app/src/main/java/com/myrunningapp/domain/health/HealthAvailability.kt`:

```kotlin
package com.myrunningapp.domain.health

/**
 * Whether this phone can accept workouts at all.
 *
 * Health Connect is part of the OS from Android 14 but a separate app before it,
 * so "not installed" is an ordinary state rather than an error.
 */
enum class HealthAvailability {
    NOT_INSTALLED,
    UPDATE_REQUIRED,
    AVAILABLE,
}
```

Create `app/src/main/java/com/myrunningapp/domain/health/HealthSyncStatus.kt`:

```kotlin
package com.myrunningapp.domain.health

import com.myrunningapp.domain.model.HealthSyncCounts

/** What the Health Connect section of the settings screen should show. */
sealed interface HealthSyncUiState {
    /** No Health Connect on this phone: show nothing rather than a dead toggle. */
    data object Hidden : HealthSyncUiState

    data object UpdateRequired : HealthSyncUiState

    data object Off : HealthSyncUiState

    /** Switched on but not granted — or granted and then revoked. */
    data object NeedsPermission : HealthSyncUiState

    data class Working(val pending: Int) : HealthSyncUiState

    data class UpToDate(val synced: Int) : HealthSyncUiState

    data class Failed(val failed: Int, val synced: Int) : HealthSyncUiState
}

/**
 * Decides the settings section's state from the four things that determine it.
 *
 * Pure, so every combination is tested without a device — the same shape as
 * [com.myrunningapp.domain.permission.PermissionGate].
 */
object HealthSyncStatus {

    fun of(
        availability: HealthAvailability,
        enabled: Boolean,
        writePermissionsGranted: Boolean,
        counts: HealthSyncCounts,
    ): HealthSyncUiState = when {
        availability == HealthAvailability.NOT_INSTALLED -> HealthSyncUiState.Hidden
        availability == HealthAvailability.UPDATE_REQUIRED -> HealthSyncUiState.UpdateRequired
        !enabled -> HealthSyncUiState.Off
        !writePermissionsGranted -> HealthSyncUiState.NeedsPermission
        counts.pending > 0 -> HealthSyncUiState.Working(counts.pending)
        counts.failed > 0 -> HealthSyncUiState.Failed(counts.failed, counts.synced)
        else -> HealthSyncUiState.UpToDate(counts.synced)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncStatusTest*'`
Expected: PASS (9 tests).

- [ ] **Step 5: Add the preferences**

In `app/src/main/java/com/myrunningapp/data/prefs/AppPreferences.kt`, add two
fields before the closing brace of the constructor:

```kotlin
    /** Health Connect sync. Opt-in: the app is complete without it. */
    val healthSyncEnabled: Boolean = false,
    /**
     * Remembered flow state, like [backgroundLocationAsked]: Health Connect stops
     * showing its permission dialog after two declines, so once the ask is spent
     * the app must link into Health Connect's settings instead.
     */
    val healthPermissionAsked: Boolean = false,
```

In `app/src/main/java/com/myrunningapp/data/prefs/PreferencesRepository.kt`, add to
the `map` block:

```kotlin
            healthSyncEnabled = prefs[Keys.HEALTH_SYNC_ENABLED] ?: false,
            healthPermissionAsked = prefs[Keys.HEALTH_PERMISSION_ASKED] ?: false,
```

add the setters:

```kotlin
    suspend fun setHealthSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HEALTH_SYNC_ENABLED] = enabled }
    }

    suspend fun setHealthPermissionAsked(asked: Boolean) {
        dataStore.edit { it[Keys.HEALTH_PERMISSION_ASKED] = asked }
    }
```

and the keys:

```kotlin
        val HEALTH_SYNC_ENABLED = booleanPreferencesKey("health_sync_enabled")
        val HEALTH_PERMISSION_ASKED = booleanPreferencesKey("health_permission_asked")
```

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/myrunningapp/data/prefs \
        app/src/main/java/com/myrunningapp/domain/health \
        app/src/test/java/com/myrunningapp/domain/health/HealthSyncStatusTest.kt
git commit -m "Add the Health Connect toggle and its pure status decision"
```

---

### Task 5: The gateway interface and the sync engine

The rules about *when* to write, tested against a fake. Still no Health Connect
dependency: that is the whole point of the interface.

**Files:**
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthConnectGateway.kt`
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthSyncEngine.kt`
- Test: `app/src/test/java/com/myrunningapp/data/health/FakeHealthConnectGateway.kt`
- Test: `app/src/test/java/com/myrunningapp/data/health/HealthSyncEngineTest.kt`

**Interfaces:**
- Consumes: `HealthSyncDao`, `HealthSyncState` (Task 2); `WorkoutRecordBuilder`, `HealthWorkout`, `healthClientRecordId` (Task 1); `HealthAvailability` (Task 4); `PreferencesRepository`, `RunRepository` (existing).
- Produces:
  - `sealed interface HealthWriteResult { data object Success; data object PermissionMissing; data object Retryable; data class Rejected(val reason: String) }`
  - `interface HealthConnectGateway { fun availability(): HealthAvailability; suspend fun hasWritePermissions(): Boolean; suspend fun hasRoutePermission(): Boolean; suspend fun write(workout: HealthWorkout): HealthWriteResult; suspend fun delete(clientRecordId: String): HealthWriteResult }`
  - `class HealthSyncEngine @Inject constructor(...) { suspend fun sync(): HealthSyncOutcome }`
  - `enum class HealthSyncOutcome { DISABLED, UNAVAILABLE, PERMISSION_MISSING, COMPLETED, RETRY_LATER }`

- [ ] **Step 1: Write the gateway interface**

Create `app/src/main/java/com/myrunningapp/data/health/HealthConnectGateway.kt`:

```kotlin
package com.myrunningapp.data.health

import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.HealthWorkout

/** What came of trying to write or delete one workout. */
sealed interface HealthWriteResult {
    data object Success : HealthWriteResult

    /** Not granted, or granted and later revoked. Not a failure — nothing to retry against. */
    data object PermissionMissing : HealthWriteResult

    /** Health Connect was busy, updating or otherwise temporarily unable. Try again later. */
    data object Retryable : HealthWriteResult

    /** Refused for a reason retrying will not fix, e.g. a malformed record. */
    data class Rejected(val reason: String) : HealthWriteResult
}

/**
 * The app's whole surface onto Health Connect.
 *
 * An interface for the same reason [com.myrunningapp.domain.announce.Announcer]
 * is one: it lets [HealthSyncEngine]'s rules be tested against a fake, with no
 * device and no Health Connect installed. Only
 * [HealthConnectGatewayImpl] mentions `androidx.health`.
 */
interface HealthConnectGateway {

    fun availability(): HealthAvailability

    /** The session, distance and calorie write permissions — the ones sync needs. */
    suspend fun hasWritePermissions(): Boolean

    /** The separate, more sensitive route permission. Sync works without it. */
    suspend fun hasRoutePermission(): Boolean

    suspend fun write(workout: HealthWorkout): HealthWriteResult

    /** Deletes by client record id, so no Health Connect uid ever has to be stored. */
    suspend fun delete(clientRecordId: String): HealthWriteResult
}
```

- [ ] **Step 2: Write the fake and the failing engine test**

Create `app/src/test/java/com/myrunningapp/data/health/FakeHealthConnectGateway.kt`:

```kotlin
package com.myrunningapp.data.health

import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.HealthWorkout

class FakeHealthConnectGateway(
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var writePermissions: Boolean = true,
    var routePermission: Boolean = true,
) : HealthConnectGateway {

    /** Every workout handed over, newest last. A rewrite appears twice. */
    val written = mutableListOf<HealthWorkout>()
    val deleted = mutableListOf<String>()

    /** Queued results, consumed one per write. Empty means Success. */
    val writeResults = ArrayDeque<HealthWriteResult>()
    var deleteResult: HealthWriteResult = HealthWriteResult.Success

    override fun availability(): HealthAvailability = availability

    override suspend fun hasWritePermissions(): Boolean = writePermissions

    override suspend fun hasRoutePermission(): Boolean = routePermission

    override suspend fun write(workout: HealthWorkout): HealthWriteResult {
        val result = writeResults.removeFirstOrNull() ?: HealthWriteResult.Success
        if (result is HealthWriteResult.Success) written += workout
        return result
    }

    override suspend fun delete(clientRecordId: String): HealthWriteResult {
        if (deleteResult is HealthWriteResult.Success) deleted += clientRecordId
        return deleteResult
    }
}
```

Create `app/src/test/java/com/myrunningapp/data/health/HealthSyncEngineTest.kt`:

```kotlin
package com.myrunningapp.data.health

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.AppDatabase
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class HealthSyncEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var gateway: FakeHealthConnectGateway
    private lateinit var engine: HealthSyncEngine
    private var enabled = true
    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        gateway = FakeHealthConnectGateway()
        engine = HealthSyncEngine(
            healthSyncDao = db.healthSyncDao(),
            runPointDao = db.runPointDao(),
            splitDao = db.splitDao(),
            gateway = gateway,
            syncEnabled = { enabled },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun pendingRun(id: Long = 0, state: HealthSyncState = HealthSyncState.PENDING): Long {
        val runId = db.runDao().insert(
            RunEntity(
                id = id, startedAt = t0, endedAt = t0.plusSeconds(600),
                activityType = ActivityType.RUN, distanceMeters = 1609.34,
                movingDurationSec = 600, elapsedDurationSec = 600,
                avgPaceSecPerMile = 600.0, calories = 120, weightKgAtRun = 70.0,
                healthSyncState = state,
            ),
        )
        db.runPointDao().insertAll(
            listOf(
                RunPointEntity(runId = runId, timestamp = t0, latitude = 40.0, longitude = -105.0,
                    altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = 0),
                RunPointEntity(runId = runId, timestamp = t0.plusSeconds(600), latitude = 40.01,
                    longitude = -105.0, altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = 0),
            ),
        )
        db.splitDao().insert(
            SplitEntity(runId = runId, splitNumber = 1, distanceMeters = 1609.34,
                durationSec = 600, paceSecPerMile = 600.0),
        )
        return runId
    }

    @Test
    fun `a pending run is written and marked synced`() = runTest {
        val id = pendingRun()

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(listOf("run-$id"), gateway.written.map { it.clientRecordId })
        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `nothing is written while the toggle is off`() = runTest {
        val id = pendingRun()
        enabled = false

        assertEquals(HealthSyncOutcome.DISABLED, engine.sync())

        assertTrue(gateway.written.isEmpty())
        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `nothing is written when Health Connect is absent`() = runTest {
        pendingRun()
        gateway.availability = HealthAvailability.NOT_INSTALLED

        assertEquals(HealthSyncOutcome.UNAVAILABLE, engine.sync())

        assertTrue(gateway.written.isEmpty())
    }

    @Test
    fun `revoked permission leaves the queue untouched rather than failing it`() = runTest {
        val id = pendingRun()
        gateway.writePermissions = false

        assertEquals(HealthSyncOutcome.PERMISSION_MISSING, engine.sync())

        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `permission revoked mid-drain stops without failing the rest`() = runTest {
        val first = pendingRun(id = 1)
        val second = pendingRun(id = 2)
        gateway.writeResults.addLast(HealthWriteResult.Success)
        gateway.writeResults.addLast(HealthWriteResult.PermissionMissing)

        assertEquals(HealthSyncOutcome.PERMISSION_MISSING, engine.sync())

        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(first)!!.healthSyncState)
        assertEquals(HealthSyncState.PENDING, db.runDao().getById(second)!!.healthSyncState)
    }

    @Test
    fun `a retryable failure leaves the run pending and asks to be run again`() = runTest {
        val id = pendingRun()
        gateway.writeResults.addLast(HealthWriteResult.Retryable)

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `a rejection marks the run failed and does not block the next one`() = runTest {
        val bad = pendingRun(id = 1)
        val good = pendingRun(id = 2)
        gateway.writeResults.addLast(HealthWriteResult.Rejected("malformed"))

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(HealthSyncState.FAILED, db.runDao().getById(bad)!!.healthSyncState)
        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(good)!!.healthSyncState)
    }

    @Test
    fun `an edited run is rewritten under the same client id rather than duplicated`() = runTest {
        val id = pendingRun()
        engine.sync()
        db.healthSyncDao().markState(id, HealthSyncState.PENDING)

        engine.sync()

        assertEquals(listOf("run-$id", "run-$id"), gateway.written.map { it.clientRecordId })
    }

    @Test
    fun `queued deletions drain and clear`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(listOf("run-7"), gateway.deleted)
        assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
    }

    @Test
    fun `deletions drain before writes so a delete cannot be undone by a stale write`() = runTest {
        pendingRun(id = 1)
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 9, requestedAt = t0))

        engine.sync()

        assertEquals(1, gateway.deleted.size)
        assertEquals(1, gateway.written.size)
    }

    @Test
    fun `a failed deletion stays queued`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))
        gateway.deleteResult = HealthWriteResult.Retryable

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        assertEquals(listOf(7L), db.healthSyncDao().pendingDeletions().map { it.runId })
    }

    @Test
    fun `the route is left out when its permission is not granted`() = runTest {
        pendingRun()
        gateway.routePermission = false

        engine.sync()

        assertTrue(gateway.written.single().route.isEmpty())
    }

    @Test
    fun `the route is included when its permission is granted`() = runTest {
        pendingRun()

        engine.sync()

        assertEquals(2, gateway.written.single().route.size)
    }

    @Test
    fun `a run with nothing in it is marked failed rather than retried forever`() = runTest {
        val id = db.runDao().insert(
            RunEntity(
                startedAt = t0, endedAt = t0, activityType = ActivityType.RUN,
                distanceMeters = 0.0, movingDurationSec = 0, elapsedDurationSec = 0,
                avgPaceSecPerMile = 0.0, calories = 0, weightKgAtRun = 70.0,
                healthSyncState = HealthSyncState.PENDING,
            ),
        )

        engine.sync()

        assertEquals(HealthSyncState.FAILED, db.runDao().getById(id)!!.healthSyncState)
        assertTrue(gateway.written.isEmpty())
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncEngineTest*'`
Expected: FAIL — unresolved reference `HealthSyncEngine`.

- [ ] **Step 4: Write the engine**

Create `app/src/main/java/com/myrunningapp/data/health/HealthSyncEngine.kt`:

```kotlin
package com.myrunningapp.data.health

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.health.WorkoutRecordBuilder
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.healthClientRecordId
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** How a drain ended. The worker turns this into success, retry or failure. */
enum class HealthSyncOutcome {
    /** The toggle is off. Nothing was touched. */
    DISABLED,

    /** No usable Health Connect on this phone. */
    UNAVAILABLE,

    /** Not granted, or revoked partway. The queue is intact and nothing failed. */
    PERMISSION_MISSING,

    /** The queue drained. Individual runs may have been marked FAILED. */
    COMPLETED,

    /** Something was temporarily unable; run again later. */
    RETRY_LATER,
}

/**
 * Drains the outbox into Health Connect.
 *
 * Every step is idempotent, because every record carries a stable
 * `clientRecordId`: writing twice updates rather than duplicates, and deleting
 * something already gone is not an error. That is what makes retrying safe.
 *
 * Missing permission is deliberately *not* a write failure. The user can revoke
 * at any moment, including mid-backfill, and treating that as failure would burn
 * the whole queue for something they may re-grant a minute later.
 */
@Singleton
class HealthSyncEngine @Inject constructor(
    private val healthSyncDao: HealthSyncDao,
    private val runPointDao: RunPointDao,
    private val splitDao: SplitDao,
    private val gateway: HealthConnectGateway,
    private val syncEnabled: suspend () -> Boolean,
) {

    suspend fun sync(): HealthSyncOutcome {
        if (!syncEnabled()) return HealthSyncOutcome.DISABLED
        if (gateway.availability() != HealthAvailability.AVAILABLE) {
            return HealthSyncOutcome.UNAVAILABLE
        }
        if (!gateway.hasWritePermissions()) return HealthSyncOutcome.PERMISSION_MISSING

        var retryLater = false

        // Deletions first: a delete that lost a race with a stale write would
        // otherwise resurrect a run the user got rid of.
        for (deletion in healthSyncDao.pendingDeletions()) {
            when (gateway.delete(healthClientRecordId(deletion.runId))) {
                is HealthWriteResult.Success -> healthSyncDao.clearDeletion(deletion.runId)
                is HealthWriteResult.PermissionMissing -> return HealthSyncOutcome.PERMISSION_MISSING
                is HealthWriteResult.Retryable -> retryLater = true
                // A record Health Connect will not delete is one we stop asking about.
                is HealthWriteResult.Rejected -> healthSyncDao.clearDeletion(deletion.runId)
            }
        }

        val includeRoute = gateway.hasRoutePermission()

        for (run in healthSyncDao.pendingRuns()) {
            val workout = buildWorkout(run, includeRoute)
            if (workout == null) {
                // Nothing to publish and nothing time will fix.
                healthSyncDao.markState(run.id, HealthSyncState.FAILED)
                continue
            }
            when (gateway.write(workout)) {
                is HealthWriteResult.Success ->
                    healthSyncDao.markState(run.id, HealthSyncState.SYNCED)
                is HealthWriteResult.PermissionMissing ->
                    return HealthSyncOutcome.PERMISSION_MISSING
                is HealthWriteResult.Retryable -> retryLater = true
                is HealthWriteResult.Rejected ->
                    healthSyncDao.markState(run.id, HealthSyncState.FAILED)
            }
        }

        return if (retryLater) HealthSyncOutcome.RETRY_LATER else HealthSyncOutcome.COMPLETED
    }

    private suspend fun buildWorkout(run: RunEntity, includeRoute: Boolean) =
        WorkoutRecordBuilder.build(
            run = run.toDomain(),
            splits = splitDao.observeForRun(run.id).first().map { it.toDomain() },
            points = runPointDao.observeForRun(run.id).first().map { it.toDomain() },
            includeRoute = includeRoute,
        )
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncEngineTest*'`
Expected: PASS (14 tests). If `splitDao.observeForRun` / `runPointDao.observeForRun`
have different names, read those DAOs and use the real ones — the engine only
needs a suspend read of a run's splits and points.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/myrunningapp/data/health app/src/test/java/com/myrunningapp/data/health
git commit -m "Add the Health Connect sync engine and its gateway seam

Revoked permission stops the drain without failing anything: the user
may re-grant a minute later and the queue should survive that."
```

---

### Task 6: The real Health Connect gateway

The one file that talks to `androidx.health`, plus the dependencies and manifest
entries it needs.

**This is the only task whose exact API signatures must be confirmed against the
resolved artifact** rather than taken on faith from this plan. `connect-client`
moved several constructors between versions. The contract that must not change is
`HealthConnectGateway`; how the impl satisfies it is free.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthConnectGatewayImpl.kt`
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthPermissions.kt`
- Create: `app/src/main/java/com/myrunningapp/di/HealthModule.kt`
- Test: `app/src/test/java/com/myrunningapp/data/health/HealthPermissionsTest.kt`

**Interfaces:**
- Consumes: `HealthConnectGateway`, `HealthWriteResult` (Task 5); `HealthWorkout` (Task 1); `PreferencesRepository` (Task 4).
- Produces:
  - `object HealthPermissions { val WRITE: Set<String>; const val ROUTE: String; val ALL: Set<String> }`
  - `class HealthConnectGatewayImpl @Inject constructor(@ApplicationContext context: Context) : HealthConnectGateway`
  - `HealthModule` binding `HealthConnectGateway` to the impl and providing `HealthSyncEngine` with its `syncEnabled` lambda.

- [ ] **Step 1: Add the dependencies**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
healthConnect = "1.1.0"
work = "2.10.0"
androidxHilt = "1.2.0"
```

under `[libraries]`:

```toml
androidx-health-connect = { group = "androidx.health.connect", name = "connect-client", version.ref = "healthConnect" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidxHilt" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
```

In `app/build.gradle.kts`, in `dependencies`, after the osmdroid line:

```kotlin
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
```

and with the other `testImplementation` lines:

```kotlin
    testImplementation(libs.androidx.work.testing)
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i 'connect-client\|work-runtime'`
Expected: both resolve, `connect-client:1.1.0` among them. If 1.1.0 does not
exist in the configured repositories, run
`./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency connect-client`
and pin the newest stable version it reports, updating `healthConnect` in
`libs.versions.toml`. Record the version actually used in the commit message.

- [ ] **Step 3: Write the permission set and its test**

Create `app/src/main/java/com/myrunningapp/data/health/HealthPermissions.kt`:

```kotlin
package com.myrunningapp.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * What the app asks Health Connect for.
 *
 * The route is separate and asked for alongside the rest: it is the more
 * sensitive one, and sync works without it, so a refusal costs the map and
 * nothing else.
 */
object HealthPermissions {

    val WRITE: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
    )

    const val ROUTE: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    val ALL: Set<String> = WRITE + ROUTE
}
```

Create `app/src/test/java/com/myrunningapp/data/health/HealthPermissionsTest.kt`:

```kotlin
package com.myrunningapp.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPermissionsTest {

    @Test
    fun `sync asks for exactly the three write permissions it needs`() {
        assertEquals(3, HealthPermissions.WRITE.size)
        assertTrue(HealthPermissions.WRITE.all { it.contains("WRITE") })
    }

    @Test
    fun `the route is asked for on top of them, not instead of them`() {
        assertEquals(HealthPermissions.WRITE + HealthPermissions.ROUTE, HealthPermissions.ALL)
        assertEquals(4, HealthPermissions.ALL.size)
    }
}
```

Run: `./gradlew testDebugUnitTest --tests '*HealthPermissionsTest*'`
Expected: PASS. If `PERMISSION_WRITE_EXERCISE_ROUTE` is not a `const val` in the
resolved version, drop `const` from `ROUTE`.

- [ ] **Step 4: Add the manifest entries**

In `app/src/main/AndroidManifest.xml`, add the permissions after the existing
`WAKE_LOCK` line:

```xml
    <!-- Health Connect: writing finished workouts. All optional; the feature is off by default. -->
    <uses-permission android:name="android.permission.health.WRITE_EXERCISE" />
    <uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
    <uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.WRITE_EXERCISE_ROUTE" />
```

Add to the existing `<queries>` block, so the app can see Health Connect on
Android 13, where it is a separate app:

```xml
        <package android:name="com.google.android.apps.healthdata" />
```

Inside `<application>`, add the rationale activity. Health Connect links to this
from its own permission screen, and it must exist before permissions are
requested:

```xml
        <!--
            Health Connect links here to explain why the app wants health
            permissions. Two intent filters because Android 14 changed the
            action; both point at the same screen.
        -->
        <activity-alias
            android:name=".HealthPermissionsRationaleActivity"
            android:exported="true"
            android:targetActivity=".MainActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>
```

- [ ] **Step 5: Write the gateway implementation**

Create `app/src/main/java/com/myrunningapp/data/health/HealthConnectGatewayImpl.kt`:

```kotlin
package com.myrunningapp.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthWorkout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one point of contact with `androidx.health`.
 *
 * Everything that could be decided without Health Connect already was, by
 * [com.myrunningapp.domain.health.WorkoutRecordBuilder] and [HealthSyncEngine].
 * What is left here is translation and error classification.
 */
@Singleton
class HealthConnectGatewayImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthConnectGateway {

    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthAvailability.AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    override fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.UPDATE_REQUIRED
            else -> HealthAvailability.NOT_INSTALLED
        }

    override suspend fun hasWritePermissions(): Boolean =
        granted().containsAll(HealthPermissions.WRITE)

    override suspend fun hasRoutePermission(): Boolean =
        granted().contains(HealthPermissions.ROUTE)

    private suspend fun granted(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    override suspend fun write(workout: HealthWorkout): HealthWriteResult {
        val client = client ?: return HealthWriteResult.PermissionMissing
        return runCatchingHealth {
            client.insertRecords(workout.toRecords())
        }
    }

    override suspend fun delete(clientRecordId: String): HealthWriteResult {
        val client = client ?: return HealthWriteResult.PermissionMissing
        return runCatchingHealth {
            for (type in DELETABLE) {
                client.deleteRecords(
                    recordType = type,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(clientRecordId),
                )
            }
        }
    }

    /**
     * Classifies what went wrong. The distinction that matters is between "try
     * again in a minute" and "this will never work": the first leaves the run
     * queued, the second marks it failed rather than retrying forever.
     */
    private inline fun runCatchingHealth(block: () -> Unit): HealthWriteResult = try {
        block()
        HealthWriteResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        HealthWriteResult.PermissionMissing
    } catch (e: IOException) {
        HealthWriteResult.Retryable
    } catch (e: IllegalStateException) {
        // Health Connect updating or otherwise not ready.
        HealthWriteResult.Retryable
    } catch (e: IllegalArgumentException) {
        HealthWriteResult.Rejected(e.message ?: "rejected by Health Connect")
    }

    private fun HealthWorkout.toRecords(): List<Record> {
        val zone: ZoneOffset = ZoneId.systemDefault().rules.getOffset(startedAt)
        val metadata = Metadata.activelyRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientRecordId,
        )
        val session = ExerciseSessionRecord(
            startTime = startedAt,
            startZoneOffset = zone,
            endTime = endedAt,
            endZoneOffset = zone,
            exerciseType = when (activityType) {
                ActivityType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                ActivityType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            },
            title = title,
            segments = segments.map {
                ExerciseSegment(
                    startTime = it.startedAt,
                    endTime = it.endedAt,
                    segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN,
                )
            },
            laps = laps.map {
                ExerciseLap(
                    startTime = it.startedAt,
                    endTime = it.endedAt,
                    length = Length.meters(it.distanceMeters),
                )
            },
            exerciseRoute = route.takeIf { it.isNotEmpty() }?.let { points ->
                ExerciseRoute(
                    points.map {
                        ExerciseRoute.Location(
                            time = it.time,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            altitude = Length.meters(it.altitudeMeters),
                            horizontalAccuracy = Length.meters(it.horizontalAccuracyMeters.toDouble()),
                        )
                    },
                )
            },
            metadata = metadata,
        )
        return listOf(
            session,
            DistanceRecord(
                startTime = startedAt, startZoneOffset = zone,
                endTime = endedAt, endZoneOffset = zone,
                distance = Length.meters(distanceMeters),
                metadata = metadata,
            ),
            ActiveCaloriesBurnedRecord(
                startTime = startedAt, startZoneOffset = zone,
                endTime = endedAt, endZoneOffset = zone,
                energy = Energy.kilocalories(activeCalories.toDouble()),
                metadata = metadata,
            ),
        )
    }

    private companion object {
        val DELETABLE = listOf(
            ExerciseSessionRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
        )
    }
}
```

- [ ] **Step 6: Compile and reconcile against the real API**

Run: `./gradlew :app:compileDebugKotlin`

If it fails, the resolved `connect-client` differs from what is written above.
Fix the impl, not the interface. The likely differences, in order of likelihood:

- **`Metadata`** — older versions use a constructor
  `Metadata(clientRecordId = ..., device = Device(type = Device.TYPE_PHONE))`
  instead of the `activelyRecorded` factory. Use whichever exists; the
  `clientRecordId` must be set either way, since the entire delete-and-update
  strategy depends on it.
- **`ExerciseSessionRecord`** — the route parameter may be named `exerciseRoute`
  or absent (route written separately). If absent, `connect-client` is too old
  for routes: raise the version rather than dropping the feature.
- **`ExerciseRoute.Location`** — `horizontalAccuracy`/`altitude` are optional;
  drop them if the signature differs.

To see the real signatures:
`find ~/.gradle/caches/modules-2 -name 'connect-client-*-sources.jar'` and unzip
the relevant file, or open the class in Android Studio.

Expected: compiles.

- [ ] **Step 7: Bind it with Hilt**

Create `app/src/main/java/com/myrunningapp/di/HealthModule.kt`:

```kotlin
package com.myrunningapp.di

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.health.HealthConnectGateway
import com.myrunningapp.data.health.HealthConnectGatewayImpl
import com.myrunningapp.data.health.HealthSyncEngine
import com.myrunningapp.data.prefs.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {

    @Binds
    @Singleton
    abstract fun bindHealthConnectGateway(impl: HealthConnectGatewayImpl): HealthConnectGateway

    companion object {

        @Provides
        @Singleton
        fun provideHealthSyncEngine(
            healthSyncDao: HealthSyncDao,
            runPointDao: RunPointDao,
            splitDao: SplitDao,
            gateway: HealthConnectGateway,
            preferences: PreferencesRepository,
        ): HealthSyncEngine = HealthSyncEngine(
            healthSyncDao = healthSyncDao,
            runPointDao = runPointDao,
            splitDao = splitDao,
            gateway = gateway,
            // Read fresh on every drain: the user may switch the feature off
            // while a backfill is in flight.
            syncEnabled = { preferences.preferences.map { it.healthSyncEnabled }.first() },
        )
    }
}
```

- [ ] **Step 8: Build and run the whole suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: builds and passes.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml \
        app/src/main/java/com/myrunningapp/data/health app/src/main/java/com/myrunningapp/di/HealthModule.kt \
        app/src/test/java/com/myrunningapp/data/health/HealthPermissionsTest.kt
git commit -m "Add the real Health Connect gateway

Records carry clientRecordId so a rewrite updates in place and a delete
needs no stored uid. Note the connect-client version actually resolved."
```

---

### Task 7: The worker and the enqueue points

What actually causes a drain to happen.

**Files:**
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthSyncWorker.kt`
- Create: `app/src/main/java/com/myrunningapp/data/health/HealthSyncScheduler.kt`
- Modify: `app/src/main/java/com/myrunningapp/MyRunningApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt`
- Test: `app/src/test/java/com/myrunningapp/data/health/HealthSyncWorkerTest.kt`

**Interfaces:**
- Consumes: `HealthSyncEngine`, `HealthSyncOutcome` (Task 5).
- Produces:
  - `class HealthSyncScheduler @Inject constructor(@ApplicationContext context: Context) { fun requestSync() }`
  - `HealthSyncWorker` (Hilt worker, unique work name `health-sync`)
  - `RunRepository` gains `healthSyncScheduler: HealthSyncScheduler` and calls `requestSync()` after each outbox change.

- [ ] **Step 1: Write the failing worker test**

Create `app/src/test/java/com/myrunningapp/data/health/HealthSyncWorkerTest.kt`:

```kotlin
package com.myrunningapp.data.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthSyncWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(outcome: HealthSyncOutcome): HealthSyncWorker {
        val engine = mockk<HealthSyncEngine>()
        coEvery { engine.sync() } returns outcome
        return TestListenableWorkerBuilder<HealthSyncWorker>(context)
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = HealthSyncWorker(appContext, workerParameters, engine)
                },
            )
            .build()
    }

    @Test
    fun `a completed drain succeeds`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(HealthSyncOutcome.COMPLETED).doWork())
    }

    @Test
    fun `a retryable drain asks WorkManager to try again`() = runTest {
        assertEquals(ListenableWorker.Result.retry(), worker(HealthSyncOutcome.RETRY_LATER).doWork())
    }

    @Test
    fun `the toggle being off is a success, not a retry`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(HealthSyncOutcome.DISABLED).doWork())
    }

    @Test
    fun `missing permission is a success so WorkManager stops backing off`() = runTest {
        // Nothing is lost: the queue is intact and granting permission enqueues again.
        assertEquals(
            ListenableWorker.Result.success(),
            worker(HealthSyncOutcome.PERMISSION_MISSING).doWork(),
        )
    }

    @Test
    fun `an absent Health Connect is a success`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(HealthSyncOutcome.UNAVAILABLE).doWork())
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncWorkerTest*'`
Expected: FAIL — unresolved reference `HealthSyncWorker`.

- [ ] **Step 3: Write the worker and the scheduler**

Create `app/src/main/java/com/myrunningapp/data/health/HealthSyncWorker.kt`:

```kotlin
package com.myrunningapp.data.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the outbox in the background.
 *
 * Only a genuinely transient problem is a retry. Everything else — the toggle
 * off, no Health Connect, permission revoked — is a success: the queue is intact
 * and something else (granting permission, switching the toggle on) will enqueue
 * again. Retrying those would only make WorkManager back off further and further
 * for a condition that no amount of waiting fixes.
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: HealthSyncEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (engine.sync()) {
        HealthSyncOutcome.RETRY_LATER -> Result.retry()
        HealthSyncOutcome.COMPLETED,
        HealthSyncOutcome.DISABLED,
        HealthSyncOutcome.UNAVAILABLE,
        HealthSyncOutcome.PERMISSION_MISSING,
        -> Result.success()
    }

    companion object {
        const val WORK_NAME = "health-sync"
    }
}
```

Create `app/src/main/java/com/myrunningapp/data/health/HealthSyncScheduler.kt`:

```kotlin
package com.myrunningapp.data.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks for a drain.
 *
 * The work is unique and replaces itself, so finishing a run, correcting three
 * activity types and deleting a fourth run collapse into one drain rather than
 * five. There are no constraints: Health Connect is local, so there is nothing
 * to wait for a network or a charger for.
 */
@Singleton
class HealthSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun requestSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HealthSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
```

- [ ] **Step 4: Wire Hilt's worker factory**

In `app/src/main/java/com/myrunningapp/MyRunningApp.kt`, add imports:

```kotlin
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import javax.inject.Inject
```

change the class declaration to implement `Configuration.Provider`:

```kotlin
class MyRunningApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
```

(keep `@HiltAndroidApp`, `onCreate` and `configureOsmdroid` exactly as they are).

In `app/src/main/AndroidManifest.xml`, inside `<application>`, disable
WorkManager's default initializer so the Hilt-provided configuration is used:

```xml
        <!-- WorkManager is configured in MyRunningApp so Hilt can inject workers. -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
```

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` element
if it is not already declared.

- [ ] **Step 5: Enqueue from the repository**

In `app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt`, add the
import `com.myrunningapp.data.health.HealthSyncScheduler` and a constructor
parameter after `healthSyncDao`:

```kotlin
    private val healthSyncScheduler: HealthSyncScheduler,
```

Add `healthSyncScheduler.requestSync()` immediately after each of the three
`healthSyncDao` calls added in Task 3 — the end of `finishRun`, the end of
`updateActivityType`, and inside the `if` branch of `deleteRun`.

Place it directly after `healthSyncDao` and before `clock`, so `clock` remains
the last parameter and keeps its default. Existing tests construct
`RunRepository` directly with named arguments; update those call sites to pass a
relaxed mock:

```kotlin
healthSyncScheduler = mockk(relaxed = true),
```

`io.mockk.mockk` is already a test dependency.

- [ ] **Step 6: Run everything**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: builds and passes, including the 5 new worker tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/myrunningapp/data/health app/src/main/java/com/myrunningapp/MyRunningApp.kt \
        app/src/main/AndroidManifest.xml app/src/main/java/com/myrunningapp/data/repository/RunRepository.kt \
        app/src/test/java/com/myrunningapp/data/health app/src/test/java/com/myrunningapp/data/repository
git commit -m "Drain the Health Connect outbox from a WorkManager worker

Only a transient problem is a retry; a revoked permission is a success,
because backing off exponentially would not make it come back."
```

---

### Task 8: The settings section and the permission flow

The only UI in this milestone.

**Files:**
- Modify: `app/src/main/java/com/myrunningapp/ui/profile/ProfileUiState.kt`
- Modify: `app/src/main/java/com/myrunningapp/ui/profile/ProfileViewModel.kt`
- Modify: `app/src/main/java/com/myrunningapp/ui/profile/ProfileScreen.kt`
- Create: `app/src/main/java/com/myrunningapp/ui/health/HealthSyncSection.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/myrunningapp/ui/health/HealthSyncSectionTest.kt`

**Interfaces:**
- Consumes: `HealthSyncUiState`, `HealthSyncStatus`, `HealthAvailability` (Task 4); `HealthConnectGateway` (Task 5); `HealthSyncScheduler` (Task 7); `HealthSyncDao.observeCounts` (Task 2).
- Produces:
  - `ProfileUiState.healthSync: HealthSyncUiState = HealthSyncUiState.Hidden`
  - `ProfileViewModel.setHealthSyncEnabled(enabled: Boolean)`, `ProfileViewModel.onHealthPermissionResult(granted: Set<String>)`, `ProfileViewModel.syncNow()`, `ProfileViewModel.healthPermissionsToRequest: Set<String>`
  - `@Composable fun HealthSyncSection(state: HealthSyncUiState, enabled: Boolean, onToggle: (Boolean) -> Unit, onRequestPermission: () -> Unit, onSyncNow: () -> Unit, onOpenHealthConnect: () -> Unit)`

- [ ] **Step 1: Write the failing UI test**

Create `app/src/test/java/com/myrunningapp/ui/health/HealthSyncSectionTest.kt`,
following the Compose test style already used under
`app/src/test/java/com/myrunningapp/ui/`:

```kotlin
package com.myrunningapp.ui.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myrunningapp.domain.health.HealthSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthSyncSectionTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `nothing is drawn when Health Connect is not installed`() {
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.Hidden, enabled = false,
                onToggle = {}, onRequestPermission = {}, onSyncNow = {}, onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText("Health Connect").assertDoesNotExist()
    }

    @Test
    fun `an up-to-date sync says how many runs are published`() {
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.UpToDate(synced = 42), enabled = true,
                onToggle = {}, onRequestPermission = {}, onSyncNow = {}, onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText("Synced 42 runs").assertIsDisplayed()
    }

    @Test
    fun `a draining queue says how much is left`() {
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.Working(pending = 3), enabled = true,
                onToggle = {}, onRequestPermission = {}, onSyncNow = {}, onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText("3 waiting").assertIsDisplayed()
    }

    @Test
    fun `missing permission offers to ask for it`() {
        var asked = false
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.NeedsPermission, enabled = true,
                onToggle = {}, onRequestPermission = { asked = true }, onSyncNow = {},
                onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText("Grant permission").performClick()

        assertTrue(asked)
    }

    @Test
    fun `switching the toggle reports the new value`() {
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.Off, enabled = false,
                onToggle = { toggles += it }, onRequestPermission = {}, onSyncNow = {},
                onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText("Sync workouts to Health Connect").performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun `turning it off explains that published data stays put`() {
        compose.setContent {
            HealthSyncSection(
                state = HealthSyncUiState.Off, enabled = false,
                onToggle = {}, onRequestPermission = {}, onSyncNow = {}, onOpenHealthConnect = {},
            )
        }

        compose.onNodeWithText(
            "Workouts already written stay in Health Connect. Remove them there.",
        ).assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncSectionTest*'`
Expected: FAIL — unresolved reference `HealthSyncSection`.

- [ ] **Step 3: Add the strings**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="health_title">Health Connect</string>
    <string name="health_toggle">Sync workouts to Health Connect</string>
    <string name="health_explainer">Publishes each finished run so other apps can read it. Nothing leaves your phone.</string>
    <string name="health_off_note">Workouts already written stay in Health Connect. Remove them there.</string>
    <string name="health_synced">Synced %1$d runs</string>
    <string name="health_waiting">%1$d waiting</string>
    <string name="health_needs_permission">Permission needed</string>
    <string name="health_grant">Grant permission</string>
    <string name="health_open_settings">Open Health Connect</string>
    <string name="health_update_required">Update Health Connect to sync workouts</string>
    <string name="health_failed">%1$d runs could not be written</string>
    <string name="health_sync_now">Sync now</string>
```

- [ ] **Step 4: Write the section**

Create `app/src/main/java/com/myrunningapp/ui/health/HealthSyncSection.kt`:

```kotlin
package com.myrunningapp.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myrunningapp.R
import com.myrunningapp.domain.health.HealthSyncUiState

/**
 * The Health Connect part of the settings screen.
 *
 * Draws nothing at all when Health Connect is absent: a toggle that cannot work
 * is worse than no toggle. Everything it shows comes from
 * [com.myrunningapp.domain.health.HealthSyncStatus], which is where the
 * decisions are tested.
 */
@Composable
fun HealthSyncSection(
    state: HealthSyncUiState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is HealthSyncUiState.Hidden) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.health_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        if (state is HealthSyncUiState.UpdateRequired) {
            Text(
                text = stringResource(R.string.health_update_required),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenHealthConnect) {
                Text(stringResource(R.string.health_open_settings))
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.health_toggle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = stringResource(R.string.health_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            is HealthSyncUiState.Off -> Text(
                text = stringResource(R.string.health_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            is HealthSyncUiState.NeedsPermission -> {
                Text(
                    text = stringResource(R.string.health_needs_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onRequestPermission) {
                    Text(stringResource(R.string.health_grant))
                }
            }

            is HealthSyncUiState.Working -> Text(
                text = stringResource(R.string.health_waiting, state.pending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            is HealthSyncUiState.UpToDate -> Text(
                text = stringResource(R.string.health_synced, state.synced),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            is HealthSyncUiState.Failed -> {
                Text(
                    text = stringResource(R.string.health_failed, state.failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onSyncNow) {
                    Text(stringResource(R.string.health_sync_now))
                }
            }

            HealthSyncUiState.Hidden, HealthSyncUiState.UpdateRequired -> Unit
        }
    }
}
```

- [ ] **Step 5: Run the UI tests**

Run: `./gradlew testDebugUnitTest --tests '*HealthSyncSectionTest*'`
Expected: PASS (6 tests).

- [ ] **Step 6: Wire the ViewModel**

In `ProfileUiState.kt`, add:

```kotlin
import com.myrunningapp.domain.health.HealthSyncUiState
```

and the field:

```kotlin
    val healthSync: HealthSyncUiState = HealthSyncUiState.Hidden,
```

In `ProfileViewModel.kt`, inject `HealthConnectGateway`, `HealthSyncDao`,
`HealthSyncScheduler` and the existing `PreferencesRepository`, and combine the
counts, preferences and gateway state into `healthSync` using
`HealthSyncStatus.of(...)`. Add:

```kotlin
    /** What to hand the Health Connect permission contract. */
    val healthPermissionsToRequest: Set<String> = HealthPermissions.ALL

    fun setHealthSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setHealthSyncEnabled(enabled)
        if (enabled) {
            // Switching it on is what queues the history; the migration deliberately did not.
            healthSyncDao.markAllPending()
            healthSyncScheduler.requestSync()
        }
    }

    fun onHealthPermissionResult(granted: Set<String>) = viewModelScope.launch {
        preferencesRepository.setHealthPermissionAsked(true)
        if (granted.containsAll(HealthPermissions.WRITE)) healthSyncScheduler.requestSync()
    }

    fun syncNow() = viewModelScope.launch {
        healthSyncDao.retryFailed()
        healthSyncScheduler.requestSync()
    }
```

Follow the file's existing `combine`/`stateIn` pattern for exposing `uiState`;
gateway permission reads are suspend calls, so fold them in with a `flow { }` that
re-reads on each emission of the preferences flow.

- [ ] **Step 7: Add the section to the screen**

In `ProfileScreen.kt`, add the permission launcher and render the section inside
the existing settings `Column`, below the preferences switches:

```kotlin
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> viewModel.onHealthPermissionResult(granted) }

    HealthSyncSection(
        state = state.healthSync,
        enabled = state.preferences.healthSyncEnabled,
        onToggle = { enabled ->
            viewModel.setHealthSyncEnabled(enabled)
            if (enabled && !state.preferences.healthPermissionAsked) {
                healthPermissionLauncher.launch(viewModel.healthPermissionsToRequest)
            }
        },
        onRequestPermission = {
            // Android stops showing the dialog after two declines; once the ask is
            // spent, Health Connect's own settings are the only way through.
            if (state.preferences.healthPermissionAsked) {
                context.startActivity(
                    Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
                )
            } else {
                healthPermissionLauncher.launch(viewModel.healthPermissionsToRequest)
            }
        },
        onSyncNow = viewModel::syncNow,
        onOpenHealthConnect = {
            context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        },
    )
```

with imports for `androidx.activity.compose.rememberLauncherForActivityResult`,
`android.content.Intent`, `androidx.compose.ui.platform.LocalContext`
(`val context = LocalContext.current`),
`androidx.health.connect.client.HealthConnectClient`,
`androidx.health.connect.client.PermissionController`, and
`com.myrunningapp.ui.health.HealthSyncSection`.

If `ACTION_HEALTH_CONNECT_SETTINGS` is not present in the resolved version, use
`"androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"` as the action string.

- [ ] **Step 8: Build and run everything**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: builds and passes.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/myrunningapp/ui app/src/main/res/values/strings.xml \
        app/src/test/java/com/myrunningapp/ui/health
git commit -m "Add the Health Connect settings section and permission flow

The ask is spent after two declines, so a second refusal routes into
Health Connect's own settings rather than firing a dead intent."
```

---

### Task 9: The Play declaration worksheet and the docs

The last piece of the spec, and the milestone's paperwork.

**Files:**
- Create: `docs/play-health-declaration.md`
- Modify: `docs/design.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the permission set from Task 6, the privacy policy already published at `https://jjbasken.github.io/MyRunningApp/`.
- Produces: no code.

- [ ] **Step 1: Write the worksheet**

Create `docs/play-health-declaration.md` with the answers for the Play Console
Health Apps declaration. It must cover, with the answer written out ready to
paste:

- **App category:** fitness and wellness tracking.
- **Permissions requested, with a one-line user-facing justification each:**
  `WRITE_EXERCISE` (publish finished runs so other apps can read them),
  `WRITE_DISTANCE` (the distance of each run),
  `WRITE_ACTIVE_CALORIES_BURNED` (the calorie estimate for each run),
  `WRITE_EXERCISE_ROUTE` (the GPS route of each run, so map-capable apps can draw it).
- **No read permissions are requested.** State this explicitly — it is unusual and
  reviewers check.
- **Data use:** not transferred off the device, not sold, not used for
  advertising, not shared with third parties, no analytics.
- **Privacy policy URL:** `https://jjbasken.github.io/MyRunningApp/`
- **Data deletion:** in-app per run, uninstall for everything, plus Health
  Connect's own controls.
- A note that none of this is needed for sideloading — it applies only when
  publishing to Play.

- [ ] **Step 2: Add milestone 7 to the design doc**

In `docs/design.md`, add milestone 7 to the "Build milestones" list, in the voice
of the existing entries — what was built, and the decision that shaped it. Cover:
the outbox and why deletions need their own table; `clientRecordId` as the reason
edits and retries are cheap; laps laid on the moving timeline; missing permission
treated as "stop" rather than "fail"; off by default. Link the spec and the
published privacy policy.

- [ ] **Step 3: Check the README against the design doc**

`README.md` has twice fallen a milestone behind. Read its milestone list against
`docs/design.md` and bring it up to date, including milestone 7.

- [ ] **Step 4: Final verification**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: builds and passes.

- [ ] **Step 5: Commit**

```bash
git add docs README.md
git commit -m "Add the Play health declaration worksheet and milestone 7 notes"
```

---

## Field-test checklist

Not automatable; run on a real device before calling the milestone done.

- [ ] With Health Connect installed and the toggle off, the app behaves exactly as before.
- [ ] Switch the toggle on, grant permission: existing history backfills, and the settings line settles on "Synced N runs".
- [ ] Complete a run: it appears in Health Connect within a minute, with distance, duration, calories, laps **and its route**.
- [ ] Open a reader app (Fitbit, Samsung Health, Strava) and confirm the run shows up there.
- [ ] Correct a run's activity type: the Health Connect entry changes in place — there is **one** workout, not two.
- [ ] Delete a run: it disappears from Health Connect.
- [ ] Revoke permission in Health Connect, finish a run: the app does not crash, does not nag, and the settings line reads "Permission needed". Re-grant: the queued run publishes.
- [ ] Decline the permission dialog twice, then tap "Grant permission": Health Connect's settings open rather than nothing happening.
- [ ] On a device with no Health Connect, the settings section is absent entirely.
