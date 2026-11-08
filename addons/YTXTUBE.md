# YTXTube add-on

Adds [YTXTube](https://github.com/Koustubh12345/YTXTube) (a YouTube / YouTube Music client with a Liquid Glass UI,
Shorts, synced lyrics and downloads) to the Add-ons screen.

## How it works

This add-on is a **launcher**. Beatraxus does not contain any YTXTube code: the card opens the separate YTXTube app, so
you get that app exactly as it is, with its own player, Shorts, lyrics, downloads and settings.

- **Install the add-on:** Add-ons → Add an add-on → pick **YTXTube** (one tap), or **Install from ZIP** → `dist/ytxtube.zip`.
- **Install the app:** if YTXTube is not on the phone, the card shows *App not installed* and its button (**GET APP**)
  opens the download page: https://github.com/Koustubh12345/YTXTube/releases/latest
- **Use it:** once YTXTube is installed, the card shows *Connected* and the button (**OPEN**) starts the app.
  Tapping the card re-checks, so there is no need to restart Beatraxus after installing YTXTube.

If something hands the add-on a YouTube link (watch, Shorts, youtu.be, `vnd.youtube:ID`), YTXTube opens that video
directly. YTXTube has no text-search entry point, so a plain search phrase can only open the app.

## Licence

YTXTube is GPL-3.0 and belongs to its authors. Because this add-on only starts the installed app, none of its code is
copied into or linked with Beatraxus.
