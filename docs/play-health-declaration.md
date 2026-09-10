# Play Console Health Apps declaration — worksheet

This is not needed to sideload the app. It exists only for the Play Console's
Health Apps declaration form, which is required when (and only when) this app is
published to the Play Store. The answers below are written to be pasted into
that form as-is.

## App category

Fitness and wellness tracking.

## Permissions requested

Four Health Connect write permissions, and nothing else. Each line is the
justification to paste next to that permission in the form.

| Permission | Justification |
|---|---|
| `WRITE_EXERCISE` | Publishes each finished run as a workout, so other apps the user has granted access to can read it. |
| `WRITE_DISTANCE` | Records the distance of each run alongside the workout. |
| `WRITE_ACTIVE_CALORIES_BURNED` | Records the app's calorie estimate for each run. |
| `WRITE_EXERCISE_ROUTE` | Records the GPS route of each run, so map-capable apps can draw it. |

## No read permissions are requested

The app requests **write-only** access to Health Connect. It does not request
`READ_EXERCISE`, `READ_DISTANCE`, `READ_ACTIVE_CALORIES_BURNED`,
`READ_EXERCISE_ROUTE`, or any other Health Connect read permission, and it never
imports or reads back workouts recorded by other apps. This is a deliberate
design decision, not an oversight — call it out explicitly on the form, since
reviewers check for it.

## Data use

- **Not transferred off the device.** Health Connect is a local, on-device
  store; the app never sends this data to a server, because the app has no
  server.
- **Not sold.**
- **Not used for advertising.**
- **Not shared with third parties**, beyond the ordinary Health Connect model
  where the user grants other apps their own read access.
- **No analytics** are attached to this data or any other app data.

## Privacy policy URL

<https://jjbasken.github.io/MyRunningApp/>

(Source: `docs/privacy-policy.md`, published via the `gh-pages` branch.)

## Data deletion

- **One run:** delete it from History or the run detail screen. If that run had
  been written to Health Connect, the Health Connect copy is deleted in the same
  action.
- **Everything the app wrote:** uninstall the app. This removes the app's local
  data; it does not reach into Health Connect (see below).
- **Health Connect's own controls:** Health Connect lets the user view, manage,
  and delete data by source app independently of this app, at any time,
  regardless of whether the app is still installed.

## Note on scope

None of the above is required to build, install, or sideload the app — the
Health Connect feature works the same way whether or not the app is ever
published to Play. This worksheet exists solely to answer the Play Console's
Health Apps declaration form at the point of Play publication.
