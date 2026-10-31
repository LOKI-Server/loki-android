# LOKI for Android

Keeps folders on an Android device — emulator saves on an AYN Thor, Retroid, or
phone — in step with a [LOKI](https://github.com/Aspenini/LOKI) server's
**Save sync**. Every earlier version stays on your server, so a bad save or a
clash between devices can always be undone from the LOKI dashboard.

Kotlin with Jetpack Compose (Material 3). The app talks to LOKI's documented
`/sync/v1` HTTP API using only the platform's HTTP client and JSON.

## Using it

1. In the LOKI dashboard, the owner grants **Save sync** to you.
2. Open the app, enter your server address, and choose **Pair with a code**.
   Approve the code in the dashboard under **Save sync → Pair a device** (or
   sign in with a password instead).
3. For each server folder, choose **Choose a folder on this device** and pick
   the local directory, for example `RetroArch/saves`.

While the app is open it syncs as soon as anything changes. It holds one
request open to the server, which answers the moment another device changes a
folder, and checks the mapped directories every few seconds for saves that
have stopped changing. Turn on **Live sync in background** (in the menu) to
keep doing that after the app closes. It runs as a foreground service with a
notification, which you can turn off from the notification. Otherwise the
app syncs every 15 minutes in the background while online (WorkManager).

## How syncing works

The engine (`sync/SyncEngine.kt`) runs in three steps:

- **Scan.** Only files whose size or modification time changed are hashed
  (SHA-256). Files changed in the last 3 seconds are left for the next pass,
  since emulators write saves in bursts.
- **Pull.** The server's change feed is applied. Downloads go to the app cache,
  are checked against their hash, and only then replace the local file.
- **Push.** New content is uploaded, then committed against the version this
  device last saw.
  - When both sides changed a file this device has synced before, the device's
    edit wins. The server keeps the other version as a conflict you can restore.
  - A file this device has never synced is *stashed* instead, so a stale
    directory can never replace newer saves.

Local folders are reached through the Storage Access Framework, so no broad
storage permission is needed. Account details and the per-folder index live in
app-private storage.

## Building

Requires the Android SDK (API 36) and a JDK 17 or newer.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. During development, an
emulator can reach a LOKI server on the host with `adb reverse tcp:7070 tcp:7070`
and the address `http://127.0.0.1:7070`.
