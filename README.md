# CrackPrank

Non-root Android prank app. Detects when the phone is dropped (free-fall → impact
accelerometer signature) and instantly overlays a fake shattered-screen crack,
a small cluster of "dead pixels", and a couple of glitched scanline bars at a
random spot on screen, on top of whatever app is open. Hold **both volume
buttons for 3+ seconds** to clear it.

No root, no KernelSU, no unlocked bootloader — it uses the standard Android
`SYSTEM_ALERT_WINDOW` ("draw over other apps") permission plus a foreground
service, which is exactly what legitimate overlay apps (Messenger chat heads,
Facebook, screen recorders, etc.) use.

## How it works

- `DropDetectionService` — foreground service, listens to `TYPE_ACCELEROMETER`.
  Flags a drop using the standard threshold method: acceleration magnitude
  falls near 0 m/s² for a short sustained window (free fall), then spikes
  above an impact threshold within ~1.2s after (the landing — tuned low
  enough to catch a soft landing on a couch, not just a hard floor).
- `CrackOverlayView` — a `TYPE_APPLICATION_OVERLAY` window, focusable (so it
  can catch the volume-key combo) but non-touchable (so the phone stays
  fully usable underneath). Draws the crack/dead-pixel/glitch graphics
  procedurally with Canvas, at a random position each time, no image assets
  needed.
- `MainActivity` — one-screen control panel: grant overlay permission,
  exempt from battery optimization, start/stop the service, and a "test"
  button to preview the effect without actually dropping the phone.

## Building locally

```
./gradlew assembleDebug   # if you add the Gradle wrapper jar yourself, or
gradle assembleDebug      # with a system Gradle 8.7+ install
```
APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Building via GitHub Actions

Push this repo to GitHub and the included workflow
(`.github/workflows/build.yml`) builds the debug APK on every push to
`main`/`master`, on pull requests, and on manual trigger
(Actions tab → **Build APK** → **Run workflow**).

The finished APK is attached to the run as a downloadable artifact named
**CrackPrank-debug-apk** (Actions tab → pick the run → Artifacts section).

No wrapper jar is committed — the workflow installs Gradle 8.7 itself via
`gradle/actions/setup-gradle`, so there's nothing extra to set up on GitHub's
side; just push.

## Installing on your phone

1. Download `app-debug.apk` from the Actions artifact (or build locally).
2. Enable "install unknown apps" for whatever you use to open the file, and
   install it.
3. Open the app, tap through the three setup buttons (overlay permission →
   battery optimization → start tracking).
4. Drop the phone on something soft to test, or use the in-app "test crack"
   button.

## Tuning sensitivity

If it triggers too easily or not easily enough, adjust these constants in
`DropDetectionService.kt`:

- `FREE_FALL_THRESHOLD` / `FREE_FALL_MIN_DURATION_MS` — how "weightless" and
  for how long counts as a fall.
- `IMPACT_THRESHOLD` — how hard the landing spike needs to be. Lower =
  triggers on gentler drops (onto a bed, a couch) too; higher = only hard
  landings.
- `IMPACT_WINDOW_MS` — how long after the fall ends an impact still counts
  as belonging to that drop.
- `COOLDOWN_MS` — minimum time between two triggers.
