# Changelog

## 0.1.0 (unreleased)

First release of LOKI Client for Android.

- Two-way **Save sync** of server folders with directories on the device, chosen
  through the system folder picker (no broad storage permission).
- Pair with a short code approved in the LOKI dashboard, or sign in with a
  password and optional two-factor code.
- Syncs as soon as anything changes while the app is open; optional
  **Live sync in background** foreground service; otherwise every 15 minutes
  while online.
- Conflicts never lose data: the server keeps every displaced version, and files
  this device never synced are stashed rather than overwriting newer saves.
- **Browse files**: browse file shares and download files with resume and
  SHA-256 verification, using the same account sign-in.
- Warns before signing in to a plain-HTTP server address.
- Requires LOKI Server 0.6.0 or newer (shared `/auth/v1` sign-in).
