# Privacy Policy — MyRunningApp

**Effective date:** 9 September 2026

MyRunningApp is a personal running tracker for Android. It has no accounts, no
servers, and no analytics. This policy describes the little that it does with
your data, and is deliberately specific rather than reassuring.

## What the app collects

**Location.** While — and only while — you have a run in progress, the app
records your GPS position roughly once a second, including latitude, longitude,
altitude and accuracy. This is the route map and the distance; the app cannot do
its job without it. Recording starts when you start a run (or its countdown) and
stops when you finish it.

**Body metrics you enter.** Weight, height, age and sex, on the profile screen.
These are used only to estimate calories burned. You may leave them unset; the
calorie estimate is then less accurate, and nothing else changes.

**Run records.** Distance, duration, pace, mile splits, calorie estimate and
activity type for each completed run.

The app collects nothing else. There is no advertising identifier, no device
fingerprint, no contact list, no usage or crash analytics.

## Where it is stored

On your device, in the app's private storage, and nowhere else. There is no
account to create, no server to sync with, and no copy held by the developer or
anyone else. Uninstalling the app deletes all of it.

## What leaves your device

Three things, each of which you control:

**Map tiles.** To draw a map, the app downloads map images from the
OpenStreetMap tile servers. Those servers necessarily see requests for the areas
you are looking at, which reveals your approximate location to them, along with
your IP address. This is how any map that is not shipped inside the app works.
Tiles are cached on the device, so a route you have viewed before draws without
new requests. OpenStreetMap's own privacy policy governs what they do with those
requests.

**Health Connect (optional, off by default).** If you switch on Health Connect
sync and grant permission, the app writes your completed workouts — activity
type, start and end time, distance, duration, mile laps, estimated active
calories, and, if you grant the additional route permission, the GPS route — into
Health Connect on your device. Health Connect is Google software, and data you
put into it is then governed by Google's terms and by whatever permissions you
have granted to other apps that read it. The app never reads your Health Connect
data; it only writes its own runs. Turning the setting off stops further writing;
data already written stays until you remove it in Health Connect.

**Files you export.** When you tap export, the app writes GPX or JSON files to a
location you pick. Where those files go next is up to you.

## What is never done

Your data is never sold, never used for advertising, never shared with any
analytics or tracking service, and never transmitted to the developer.

## Permissions and why

| Permission | Why |
|---|---|
| Location (precise) | Records the route and measures distance during a run. |
| Background location (optional) | Keeps recording when the screen is off or the app is not in front. Declining it means tracking works only with the app open. |
| Notifications | Shows the ongoing-run notification with live stats and controls. |
| Health Connect write permissions (optional) | Writes finished workouts, and optionally their routes, into Health Connect. |

Every optional permission can be declined, and the app keeps working without it.

## Deleting your data

- **One run:** delete it from the history list or the run detail screen. Its route
  and splits go with it. If the run had been written to Health Connect, that copy
  is deleted too.
- **Everything:** uninstall the app.
- **Health Connect data:** manage or delete it in the Health Connect app, which
  can remove data written by this app independently of the app itself.

## Children

The app is not directed at children and collects nothing that identifies anyone.

## Changes to this policy

Changes will be published on this page with a new effective date. The history of
every change is public in the app's Git repository.

## Contact

Questions, or a privacy problem to report: open an issue at
<https://github.com/jjbasken/MyRunningApp/issues>.
