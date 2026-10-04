# Atalaya Music Library

The standalone Angular frontend for browsing and playing the MP3 library managed by `youtube-service`.

## Run locally

Requires Node.js 20.19+ or 22.12+ and npm.

1. Start `youtube-service` on port `8081`.
2. In this directory, run `npm install`.
3. Run `npm start` and open `http://localhost:4200` on the PC. The dev server listens on all network
   interfaces; a phone on the same LAN can open `http://<PC-LAN-IP>:4200`.

The Angular development server proxies `/api/youtube` to `http://localhost:8081`, so the service does not
need a separate CORS configuration during development. Set the proxy target in `proxy.conf.json` if the
service uses a different address.

For phone access, connect the phone and PC to the same non-guest Wi-Fi/LAN, find the PC's IPv4 address with
`ipconfig`, and open `http://<IPv4-address>:4200` on the phone. If the page cannot be reached, allow inbound
TCP port `4200` for the Private Windows network profile (for example, create a Windows Defender Firewall
inbound rule for TCP local port `4200`). Guest Wi-Fi or router client isolation can prevent devices from
reaching one another even when they appear to use the same Wi-Fi. Do not expose this development server to
the public internet.

Run `npm run build` to produce the static app under `dist/music-library/browser`.

The initial view only fetches the top-level library entries. Folder contents are requested when a folder
is opened; audio is streamed from the existing `/api/youtube/music/track` endpoint through one persistent
player. Selecting a track also prepares previous/next navigation for its containing folder. Shuffle supports
the full library and a selected folder/depth. The music library, most-played ranking, and service toolbox are
switchable tabs; the persistent player stays mounted while switching views. The toolbox includes the theme
studio, YouTube settings, and forms for service endpoints. Themes are managed through
`/api/youtube/player-theme` and stored in `../.data/youtube/music-player-theme.json`; the studio edits interface colors,
backdrop, and visualizer style, palette, bar count, and sensitivity. Each successful new playback is counted
once (pause/resume is not recounted). On first use, choose an existing username or create one; the selected
username is remembered in browser local storage, and logging out lets another listener choose a profile.
User profiles, selected themes, and listening counts are stored in `../.data/youtube/music-player-users.json`. The
theme catalog remains shared, while the active theme and most-played list are per user. The username is
only a profile label, not authentication: there are no passwords or access controls, so do not expose this
app or its API to untrusted networks. Theme and listening endpoints accept the profile in the
`X-Atalaya-Username` header. The user profile API lists profiles at `GET /api/youtube/users` and selects or
creates one with `POST /api/youtube/users` and `{"username":"alex"}`. Player volume is remembered in
browser storage, and selecting the now-playing area opens the full-screen audio-reactive visualizer.

Older listen counts from `.data/music-player-listens.json` are not assigned to a profile automatically.
