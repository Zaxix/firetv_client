# Hosting a disc menu pack

Sharkfin downloads a pack file by file from a static host you control (Settings → Disc menus →
source address). Do not put a pack that contains someone else's video, music or artwork in a public
place; use a private host.

```
python3 make_manifest.py path/to/<pack id> --catalog path/to/catalog.json
```

writes `<pack id>/manifest.json` (size, SHA-256 and stage of every file) and adds the pack to
`catalog.json`. Host the result as:

```
<source>/catalog.json
<source>/<pack id>/manifest.json
<source>/<pack id>/<every file of the pack>
```

For a private GitHub repository, the source address is
`https://raw.githubusercontent.com/<owner>/<repo>/<branch>` and the app is given a read-only
fine-grained access token (Contents: read) for that repository. The token is stored encrypted on
the device and is only sent to the source's own host. The folder must be named after the pack id.

## Optional pack fields for discs that differ from the first one

All are optional; a pack without them behaves as before (see `PackModel.kt` for the exact meaning).

- `video.transitions`: per target page id, its own transition video (`video.transition` is the default).
- `music.startsInIntroAtMs`: where in the intro the music file's first sample plays, when the intro's own sound already carries the music; at the hand-off the music continues from there instead of starting over.
- `timing.musicStopBeforeTransitionMs`: the music is cut this long before the transition (or Play Movie clip) starts instead of at the press.
- `timing.pageActivationShowsHighlight` (`false`): page buttons lose their highlight picture while their action runs.
- hotspot `cutDelayMs`: the delay between pressing a page button and its action, when it differs from `timing.subActivationToCutMs`.
- actions `audio:<en|es|fr>` and `subtitle:<en|es|fr|off>` pick the film's own tracks by language tag; a main-menu button whose action is `gate:<message>` shows the message and does nothing else.
