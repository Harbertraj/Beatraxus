# BeatRaxus hybrid plugin repositories (schema v1)

BeatRaxus repositories are **declarative JSON catalogues**. They may describe configuration for adapters already compiled into BeatRaxus. They must never contain DEX, JAR, SO, native libraries, scripts, bytecode, or instructions to evaluate downloaded code.

## Add a repository

In BeatRaxus, open **Add-ons → Add an add-on → Plugin repository** and paste either:

- `https://github.com/OWNER/REPOSITORY` (the app checks `main` and `master` for `repository.json`), or
- a direct HTTPS URL ending in `repository.json`.

The repository is user-added and should be treated as trusted only if you trust its maintainer. HTTPS protects transport but does not prove publisher identity. Signed repository metadata and key pinning are planned follow-up work; this first implementation does not claim cryptographic publisher verification.

## `repository.json`

```json
{
  "schemaVersion": 1,
  "name": "Example BeatRaxus Providers",
  "description": "Example repository",
  "plugins": [
    {
      "id": "jamendo_community",
      "name": "Jamendo Community",
      "version": "1.0.0",
      "description": "Uses the built-in Jamendo adapter",
      "category": "music_source",
      "adapterId": "jamendo",
      "manifestUrl": "plugins/jamendo.json"
    }
  ]
}
```

`manifestUrl` may be relative to the index URL or an absolute HTTPS URL. Each repository may list up to 100 providers. IDs use letters, digits and underscores; versions use `major.minor.patch` with an optional prerelease/build suffix.

## Plugin manifest

```json
{
  "schemaVersion": 1,
  "id": "jamendo_community",
  "name": "Jamendo Community",
  "version": "1.0.0",
  "category": "music_source",
  "adapterId": "jamendo",
  "minAppVersionCode": 8,
  "configuration": {
    "authType": "api_key"
  }
}
```

Supported category values:

- `music_source`
- `metadata`
- `lyrics`
- `artwork`
- `playlist`
- `scrobbling`
- `video_source`

The `adapterId` is not executable code. It must identify a reviewed implementation shipped inside BeatRaxus. Unknown adapter IDs remain visible in the catalogue but cannot be installed until an app update ships that adapter.

## Current implementation boundary

- Music and video source manifests can activate existing in-app add-on adapters through `AddonManager`.
- The Kotlin contracts for metadata, lyrics, artwork, playlists and scrobbling are included as the next adapter interfaces.
- In this patch, only music/video source adapters already wired to `AddonManager` are installable: `jamendo`, `archive_org`, `subsonic`, `jellyfin`, `emby`, `stremio` and `ytxtube`.
- Metadata, lyrics, artwork, playlists and scrobbling entries can appear in a repository catalogue, but are marked **Needs app update** until their reviewed in-app adapters are implemented and integrated. They cannot be installed as working providers by this patch.
- No executable plugin code is downloaded or dynamically loaded.

## Limits and safety checks

- HTTPS only; embedded URL credentials are rejected.
- GitHub repository URLs and direct JSON URLs are supported.
- Redirects are not followed.
- Repository index: maximum 512 KiB and 100 entries.
- Plugin manifest: maximum 128 KiB; configuration object maximum 32 KiB.
- ZIP add-ons retain the existing protocol allowlist and gain per-file, aggregate-size, entry-count, duplicate-entry, path-traversal and staged-replacement checks.

A repository maintainer must only publish providers that comply with the source service's terms, copyright and streaming licences. A repository URL does not grant music distribution rights.
