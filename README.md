# Atalaya Personal Media

The standalone Angular personal-media workspace. Switch between the MP3 player and the series tracker from
the top navigation; both remain isolated features with their own API and data.

## Run locally

Requires Node.js 20.19+ or 22.12+ and npm.

1. Start `preferences-service` on port `8083` for profiles/preferences; start `youtube-service` on port `8081`
   for music and `series-service` on port `8082` for the series tracker.
2. In `music-library/`, run `npm install`.
3. From `music-library/`, run `npm start` and open `http://localhost:4200` on the PC. The dev server listens on all network
   interfaces; a phone on the same LAN can open `http://<PC-LAN-IP>:4200`.

The Angular development server proxies `/api/youtube` to `http://localhost:8081`, `/api/series` to
`http://localhost:8082`, and `/api/preferences` to `http://localhost:8083`, so the services do not need
separate CORS configuration during development. Set the targets in `music-library/proxy.conf.json` if the services use
different addresses.

On Windows, `.\data_example\start-toolbox.ps1 -Start Series` starts the Series API; use `-Start All` for all
services, the gateway, and Angular. This is a standalone tracked launcher and does not depend on the
machine-local, Git-ignored `start-atalaya.ps1`.

For phone access, connect the phone and PC to the same non-guest Wi-Fi/LAN, find the PC's IPv4 address with
`ipconfig`, and open `http://<IPv4-address>:4200` on the phone. If the page cannot be reached, allow inbound
TCP port `4200` for the Private Windows network profile (for example, create a Windows Defender Firewall
inbound rule for TCP local port `4200`). Guest Wi-Fi or router client isolation can prevent devices from
reaching one another even when they appear to use the same Wi-Fi. Do not expose this development server to
the public internet.

Run `npm run build` from `music-library/` to produce the static app under `music-library/dist/music-library/browser`.

The Music workspace's initial view only fetches the top-level library entries. Folder contents are requested when a folder
is opened; audio is streamed from the existing `/api/youtube/music/track` endpoint through one persistent
player. Selecting a track also prepares previous/next navigation for its containing folder. Shuffle supports
the full library and a selected folder/depth. The music library, most-played ranking, and service toolbox are
switchable tabs; the persistent player stays mounted while switching views. The toolbox includes the theme
studio, YouTube settings, and forms for service endpoints. Themes are managed through
`/api/preferences/player-theme` and stored in `.data/preferences/themes.json`; the studio edits interface colors,
backdrop, and visualizer style, palette, bar count, and sensitivity. Each successful new playback is counted
once, including every repeat (pause/resume is not recounted). The repeat control in the player dock and visualizer
cycles through **Off → Once → ∞**: Once replays the current song one additional time before continuing,
and ∞ keeps replaying until disabled. Manual Next/Previous still skip songs; Once clears when a different
song starts. Repeat takes priority over shuffle without moving its queue. On first use, choose an existing username or create one; the selected
username is remembered in browser local storage, and logging out lets another listener choose a profile.
User profiles, selected themes, player volume, and listening counts are stored in
`.data/preferences/users.json`, owned by the standalone `preferences-service` and migrated on first startup
from the legacy YouTube JSON files. The theme catalog remains shared, while active themes and most-played lists
are per user. The profile gate and Series workspace do not depend on YouTube being available; the music library
and YouTube toolbox remain unavailable until `youtube-service` is back. The username is only a profile label, not authentication: there
are no passwords or access controls, so do not expose this app or its API to untrusted networks. Theme and
listening endpoints accept the profile in the `X-Atalaya-Username` header. The user profile API lists
profiles at `GET /api/preferences/users` and selects or creates one with `POST /api/preferences/users` and
`{"username":"alex"}`. The selected username is remembered in browser storage, and selecting the now-playing area
opens the full-screen audio-reactive visualizer.

Legacy profiles and themes migrate from `.data/music` and `.data/music-player-users.json` when
`preferences-service` first starts; migration leaves the source JSON unchanged. Services use dedicated
directories under the repository's parent `.data/`: music in `.data/music`, series and covers in
`.data/series`, and profiles/preferences in `.data/preferences`.

## Panic backup and restore

Use **Panic backup** beside the username to download the entire `.data` folder as a dated ZIP. The adjacent
restore button opens the upload flow. Both controls are also available on the profile chooser, including
on a fresh installation. Backup is provided by `preferences-service` at `/api/preferences/backups`,
independently of YouTube. The root defaults to the parent of the configured preferences directory;
`toolbox.backup.data-directory` can explicitly point at the shared `.data` folder. Every service must keep
its persistent files under that root to be included; there is no service whitelist, so future services work
without modifying the backup code. The ZIP contains `.data/` and a versioned `atalaya-backup.json` manifest
with SHA-256 checksums, preserving hidden files, binary assets, and empty directories. Links and special
files are rejected. Only archives generated in this format can be restored.

Pause downloads and finish edits before creating a backup. Export checks the entire file inventory before
and after copying, rejecting detected additions, deletions, replacements, and changes. This is a best-effort
filesystem snapshot, not a cross-service transaction; stop other writers for the strongest consistency.
ZIP preparation does not hold the preferences lock. Downloads stream through the browser and gateway rather
than buffering the archive in browser memory. Check browser Downloads for completion or transfer errors;
**Download this ZIP again** retries the prepared archive without recreating it. For restore, select/drop the ZIP,
wait for upload and verification, and review
the creation date, file count, and included folders. **Stop YouTube, Series, and any other services that
write data before confirming replacement**, keeping preferences-service and the UI/gateway running.
Preferences reload immediately; restart the other services afterward and use **Reopen Atalaya**.
Future services with in-memory caches also need restarting.

Restore validates paths, duplicate entries, sizes, and checksums in a staging directory before changing
live files. It replaces the whole root, keeping the previous folder at `.data-before-restore-<timestamp>-<id>`
alongside `.data`. If replacement or preference loading fails, it rolls back. The retained directory is
shown after success and can be used for manual recovery with services stopped; delete it when no longer
needed. Ensure space for the uploaded archive, its expanded contents, and the retained previous data.
Temporary download/restore sessions expire after one hour of inactivity. Active transfers and restores are
protected from expiry, and interrupted downloads remain available for retry. Only one export, verification,
or restore runs at a time; competing requests receive HTTP 409 instead of waiting behind a long operation.
Preferences and themes share one storage lock, held during replacement and cache reload. Cleanup failures
are logged and retried without concealing the original error or reporting a committed restore as failed.
An incomplete rollback preserves both recovery and staging folders for manual recovery.
Uploads default to 20 GB; adjust
`spring.servlet.multipart.max-file-size` and `max-request-size` for larger libraries. Expanded archives
default to a 1 TiB limit (`toolbox.backup.max-expanded-bytes`) and 100,000 entries. Backups include all users'
data, following the toolbox's existing trusted local/LAN profile model.

The API creates a download with `POST /api/preferences/backups`, streams it with `GET /api/preferences/backups/{id}`,
previews a multipart `file` upload with `POST /api/preferences/backups/restore/preview`, and commits that preview with
`POST /api/preferences/backups/restore/{id}`. `DELETE /api/preferences/backups/restore/{id}` discards a preview.
Preferences-service logs operation/session IDs, starts, periodic file/byte progress, durations, verification,
streaming, cache reload, rollback, recovery locations, expiry, and cleanup failures. Gateway logs show
request method/path, response status/bytes, duration, and interrupted transfers. Browser console messages use
the `[panic-backup]` prefix. No file contents are logged.
Run `npm test` in `music-library` for playback regression checks, and Maven tests in each backend for backup,
restore, streaming, and playback policy validation.

## Series tracker

The Series tracker workspace lives at `/#/series` (and is reachable through the workspace navigation) and has
its own Angular component and API service. It includes a weekly release calendar, searchable library and
timeline views, editable status/schedule/platform/genre/date/rating/notes metadata, per-episode watch logs,
and JPEG/PNG/WebP cover uploads up to 5 MB. Progress is calculated from the optional season and episode
counts; specials can be logged with season `0`, and episode history is preserved when a profile is edited.

`series-service` stores each profile as `.data/series/{username}.json` and generated cover images in
`.data/series/covers/`. Existing profiles under the deprecated `.data/series/users/` directory are
migrated to the new location the first time each profile is accessed. The app reuses the selected profile username as a
profile label; it is not authentication, matching the rest of this local toolbox. The API is available at
`/api/series/shows` and `/api/series/shows/{id}/episodes`; cover images are served from
`/api/series/covers/{fileName}`. The gateway exposes the series API
under `/api/series` and includes its OpenAPI document.
