# Jamendo Music add-on

Streams free music from independent artists through Jamendo's **official API v3.0**
(<https://developer.jamendo.com/v3.0>). It appears in **Add-ons → Add an add-on → Jamendo Music**
(or install `dist/jamendo.zip`), next to Internet Archive, as its own add-on.

## 1. Get a client ID (free)

1. Create an account at <https://devportal.jamendo.com> and add an application.
2. Copy the application's **Client ID**.

Each app should use its own client ID, and the ID is personal — don't share or publish it.

## 2. Give it to Beatraxus (pick one)

* **Users enter it in the app (default).** Tap *Jamendo Music* → paste the client ID. It is checked with
  Jamendo, then stored encrypted on the device (`AddonCredentialStore`). Nothing is committed to source control.
* **Built into your own build.** Add one line to `local.properties` (this file is git-ignored):

  ```
  JAMENDO_CLIENT_ID=your_client_id
  ```

  For the GitHub release workflow, add a repository secret named `JAMENDO_CLIENT_ID`.
  A built-in ID ships inside the APK, so treat it as *extractable*, not secret: use a client ID you are
  happy to have exposed, and watch its usage on devportal.jamendo.com. If you don't want that, leave it empty
  and let users enter their own.

The client ID is never written to logs or shown in error messages.

## 3. What it does

| Feature | API used |
| --- | --- |
| Search by anything / song title / artist | `tracks` with `search` / `namesearch`; artists are resolved with `artists?namesearch` and then `tracks?artist_id` |
| Sort: Top, Trending, Newest, Featured | `order` (`popularity_month`, `popularity_week`, `releasedate_desc`) and `featured=1` |
| Stream quality: MP3 96k / MP3 192k / OGG / FLAC | `audioformat` (`mp31`, `mp32`, `ogg`, `flac`); the `audio` URL is what plays |
| Title, artist, album, artwork, duration | `name`, `artist_name`, `album_name`, `image`, `duration` |
| Paging (30 per page, loads as you scroll) | `limit` + `offset` |
| Fresh stream URL before every play, "track removed" detection | `tracks?id=` |

Not offered because the API doesn't provide it for this use: no album or artist *detail pages* (only names),
and Jamendo's sign-in / playlists (OAuth2) are not used.

Errors are mapped from Jamendo's documented response codes (5 invalid client ID, 6 rate limit,
11 suspended app, …) and HTTP failures to plain messages; tracks without a stream URL are not listed.

## 4. Licensing and terms — read before publishing

* **Commercial use needs a licence from Jamendo.** Jamendo's API terms allow free use for *non-commercial*
  apps; ads, affiliate links and other commercial advantage count as commercial use and require a separate
  agreement (<licensing@jamendo.com>). Distributing on Google Play does not change that: if Beatraxus has
  ads, paid features or in-app purchases, get Jamendo's agreement first or keep the add-on out of that build.
* **Attribution is built in:** every track shows its artist and album, the Jamendo provider line, and an info
  button with the track's Jamendo page and Creative Commons licence link.
* **Streaming only.** The terms forbid apps "specifically designed to cache the content" or offer offline access.
  The add-on only plays the official `audio` URL; it never uses `audiodownload`, and Beatraxus's existing
  offline/cache/export features must not be pointed at Jamendo tracks.
* **Each track has its own Creative Commons licence** (the `license_ccurl` link). Some are non-commercial or
  no-derivatives. Beatraxus's DSP/EQ is playback-time processing; if you add anything that creates or shares
  modified copies of a track, check the track's licence first.
* **Don't imitate Jamendo.** Naming the provider inside the add-on is fine, but don't put "Jamendo" in the app's
  own name, domain or logo, don't imply Jamendo endorses Beatraxus (ATTRIBUTION.md says it doesn't), and keep the
  look clearly different from Jamendo's own experience. Use Jamendo's official logo only if you add it, unmodified.
* Keep a privacy policy that covers third-party services, and respect the rate limits (the app pages lazily and
  cancels superseded searches to stay well under them). Rate limits are set by Jamendo and can change.
