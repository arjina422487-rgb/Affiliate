# Remote file access changes

This change keeps Affiliate task/account logic and adds explicit, optional remote-file-access setup. Existing installations require fresh consent: a reward/redeem action is not consent to remote access. Settings disclose dashboard file access, show actual socket state, offer storage/folder selection and battery instructions, and provide Stop. The foreground notification names remote file access and includes Stop. Legacy screen/location/call-log commands are not enabled by the file-access consent.

## Connection

The foreground service represents communication with the user's paired remote device over a network (`connectedDevice`), rather than an indefinitely running cloud data-sync job. Manifest declares its foreground-service permission and network-state prerequisite. See https://developer.android.com/develop/background-work/services/fgs/service-types . This classification must continue to match the actual product usage when shipping; it is not a general workaround for cloud sync limits.

Service start failures are surfaced; the app never falls back to an unprotected remote socket. Permission/account setup does not automatically connect. Startup, resume, boot and periodic work only attempt the service after explicit consent and a persisted enabled setting. Foreground-service creation still obeys Android's background-start restrictions. WorkManager is a recovery opportunity, not a promise of an exact restart time.

For Xiaomi, enable Background autostart and No restrictions. For Infinix, enable the equivalent background/autostart power settings; names vary by model. Leave the persistent notification enabled, keep network available, and open the app after Force stop. Unlock once after reboot. No app can guarantee connection while powered off, disconnected, force-stopped or blocked by the manufacturer's OS.

## Storage and versions

Minimum API is now 29 (Android 10), with legacy external-storage opt-out for Android 10 and a version guard around the API-30 All-files API. Android 11+ uses user-granted All files access, or persisted read-only Storage Access Framework folder grants. All files access also satisfies the app's shared-media check. Folder-read failures report access denied instead of pretending the directory is empty.

This does not grant Linux root, another app's private `/data` storage, or protected `Android/data` access. `compileSdk`/`targetSdk` remain 34: raising the target to 37 and claiming Android 10–17 certification requires platform/OEM device testing and a separate SDK migration. No physical Xiaomi/Infinix or Android 17 test device was available for this change.

## Large downloads

The original phone loop could exceed OkHttp's 16 MiB WebSocket send buffer; it ignored send failures. The server also spawned independent queue writers on overflow, allowing chunk reordering and dropping the FILE_END marker. The new sender waits above a 1 MiB queue threshold and checks accepted sends. The server awaits bounded queue writes, validates chunk sequence and declared byte count, and releases blocked writes when the HTTP consumer disconnects. Interrupted socket generations cannot continue an old transfer on a new connection.

Downloads accept single `bytes=start-end` / `bytes=start-` requests, return 206 and Content-Range, and permit inline image/video/PDF responses. Unsupported suffix/multiple ranges return 416. Browser downloads between 8 and 128 MiB use 4 MiB requests and retry only the failed part, up to five attempts. Files are saved only after all parts match their expected size. This keeps each proxy request short without uploading phone files permanently to the backend. Larger or unknown-size files use native browser download; the range endpoint also supports compatible download managers. Partial browser downloads are held in memory and do not survive closing/reloading the page. Files should not be edited during download; total-size changes are rejected, but same-size content changes are not detected.

## New deployment configuration

The Android build no longer embeds the old Render origin. Supply `-PaffiliateBaseUrl=https://NEW-SERVICE.onrender.com` or environment variable `AFFILIATE_BASE_URL` when building. A blank development build shows that the dashboard server is not configured. The Render blueprint now asks for OWNERLINK_BASE_URL rather than embedding the old origin. Supply the new Upstash REST URL/token as backend environment secrets. The Upstash token must never be placed inside the APK or committed.

Deploy the backend and updated APK together for range transfers. Keep one backend worker/instance because live device routes remain process-local. Free-host cold starts, restarts and network throughput remain external latency limits.

## Validation

- Android Kotlin compilation succeeds.
- Backend tests cover 40 MiB byte-for-byte delivery through a two-item slow-consumer queue, cancellation, sequence errors, incomplete files, HTTP ranges and invalid ranges.
- JavaScript tests cover 35 MiB complete download with an incomplete-range retry, and rejecting an unauthorized download without saving a partial file.
- Existing backend suite: 57 passed, 3 failed. The same three task-auth/screen-copy failures were reproduced from an unchanged HEAD archive.
- Existing Android unit suite: 18 passed, 1 task coin-share validation test failed. Its test and implementation sources are unchanged by this work: the test expects sending 2.5 coins to succeed, but the existing validator requires a minimum of 1000.
- No signed release APK or deployment has been produced; the new production URLs and physical-device checks remain pending.

## Gallery albums

Gallery now returns exact counts from the permitted Android media index: total photos/videos and per-folder albums grouped by RELATIVE_PATH. Folder labels and full paths come from the device rather than a hardcoded list of apps. Opening an album filters by its exact path, so folders with matching names remain separate. Media that Android has not indexed or does not permit is not included.

The browser automatically loads all metadata pages (200 items per request) instead of stopping at 60 items until Load more is clicked. Total and per-album counts appear independently of how many thumbnails have loaded. Thumbnail requests start near the viewport and are limited to four concurrent loads. Album changes cancel stale metadata requests. A failed page exposes a retry button and preserves the loaded portion. Refresh reloads the current album and counts. Original images remain on-demand downloads.

Validation: Android Kotlin compilation succeeded; browser regression test covers automatic pagination, album selection/counts and no eager thumbnail loads; API test covers forwarding album filters, a bounded page size and preserving large totals. Physical-device media-provider behavior and hosting latency remain unmeasured.

## Remote file manager navigation

The Files dashboard now includes folder refresh, search within the current folder, folders-first sorting by name/date/size, item counts, breadcrumbs and previews for images, videos and PDFs. Stale folder responses are ignored when navigating quickly. Android 11+ storage discovery also checks StorageManager volumes for mounted shared storage. Saved folder-picker entries must still have a persisted read grant before listing or downloading; expired grants require choosing the folder again on the phone. Hidden files are included in direct folder listing. This provides browsing and downloads of permitted shared storage, not system-private data or remote delete/rename operations. Physical-device comparison with the installed file manager is still required.

## Automatic mixed review policy

Simple URL tasks without proof fields use persistent Redis assignments: each user has independently shuffled blocks containing exactly 65 automatic and 35 manual positions. Within a block the shuffle limits runs to eight automatic or five manual positions; block boundaries and random completion times can still create longer observed runs. Partial blocks need not have a 65% ratio. Each automatic assignment is due 900–1800 seconds after scheduling; a 30-second worker processes due jobs in batches of 50. Render sleeping or overloaded queues can delay completion. Manual decisions supersede pending automatic decisions. Existing reward ledger idempotency prevents double credit. Redis queue and assignment state survive restarts; a five-minute repair scan schedules pending simple URL submissions missing assignments, including existing pending submissions on activation. Forms and proof tasks remain manual. Selection is administrative and does not verify watch time. The Reviews/Submissions page explains this policy. No SQLite automatic scheduler is enabled. Python syntax compilation passed; functional tests and live deployment remain pending.
