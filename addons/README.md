# Beatraxus add-ons

Ready-to-install add-on packages. In the app: **Add-ons → Add an add-on → Install from ZIP**, then pick a file from `dist/`.
(The same add-ons are also available without a ZIP in the same sheet.)

| ZIP | Protocol | Sign-in |
| --- | --- | --- |
| `navidrome-subsonic.zip` | Subsonic API (Navidrome, Airsonic, Gonic) | server address + username + password |
| `jellyfin.zip` | Jellyfin | server address + username + password |
| `emby.zip` | Emby | server address + username + password |
| `stremio-catalog.zip` | Stremio add-on protocol | paste a manifest URL (direct-link streams only) |
| `internet-archive.zip` | Internet Archive | none |
| `jamendo.zip` | Jamendo (official API v3.0) | free Jamendo client ID — see [JAMENDO.md](JAMENDO.md) |

Plex is intentionally not included: its add-on can't sign in yet.

Each ZIP holds one file, `manifest.json`, at its root. To make your own, copy a folder here, change `id` and
`displayName` (the `id` may only use letters, digits and `_`), and run:

    python3 addons/build_zips.py

The script rebuilds every ZIP and checks it against the app's real install rules.
