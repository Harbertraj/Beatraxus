# Podcasts and audiobooks — Phase 4 foundation

This phase adds a bounded RSS 2.0 podcast-feed parser and reusable audiobook chapter/bookmark
models. It intentionally does not add a second playback engine.

## Podcast parser

`PodcastFeedParser.parse(ByteArray)` supports RSS 2.0 channel metadata, podcast image, episode
title/GUID/description/date, audio enclosure URL/MIME type, and common duration formats
(`seconds`, `MM:SS`, `HH:MM:SS`).

Safety limits:
- 2 MiB maximum XML input.
- 500 maximum RSS items.
- DTD and external entity processing disabled.
- Enclosure and artwork URLs limited to HTTP(S), with embedded URL credentials rejected.
- Only audio enclosures are returned when the MIME type is supplied.

The caller still needs to fetch feeds on a worker dispatcher, apply network timeouts, persist
subscriptions/episode history, and send selected enclosure URLs to the existing player.

## Audiobook foundation

`AudiobookChapter` stores chapter boundaries; `AudiobookProgress` stores a book-specific resume
bookmark. `AudiobookChapterIndex` validates chapter ordering, finds the chapter at a playback
position, and calculates a clamped progress fraction.

This patch does not yet scan folders, parse embedded MP4/M4B chapter atoms, provide a bookshelf UI,
or connect bookmarks to the playback service. Those require integration with the app's media
database and queue lifecycle.

## Android Auto

Android Auto is deliberately not enabled by this foundation patch. The current music service uses
a legacy `MediaSessionCompat` session and a custom audio engine. A safe Android Auto implementation
needs a dedicated Media3 `MediaLibraryService`/`MediaLibrarySession` migration or a carefully
designed bridge, including browse-tree permissions, queue mapping, playback-state synchronization,
and Automotive emulator/device testing. Merely adding a manifest intent filter would not make the
app Android Auto compatible.
