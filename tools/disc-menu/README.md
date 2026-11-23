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
