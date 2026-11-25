#!/usr/bin/env python3
"""Packs a folder of small files into one .bundle so the app downloads one file instead of dozens.

  python3 make_bundle.py PACK_FOLDER SUBFOLDER      e.g.  make_bundle.py hp1-uk-disc1 page_sprites

Writes PACK_FOLDER/SUBFOLDER.bundle holding every file under PACK_FOLDER/SUBFOLDER, each under its path relative to
PACK_FOLDER (so pack.json keeps saying "page_sprites/ss1/tag_1.webp"), and moves SUBFOLDER out of the pack to
PACK_FOLDER/../_bundled/SUBFOLDER. Then run make_manifest.py, which records what each bundle contains.
Format: "DMB1", 4-byte big-endian header length, JSON header {"files":[{"p":path,"o":offset,"l":length}]}, data.
"""
import json, os, shutil, struct, sys


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root, sub = os.path.abspath(sys.argv[1]), sys.argv[2].strip('/')
    src = os.path.join(root, sub)
    names = sorted(os.path.relpath(os.path.join(d, n), root).replace(os.sep, '/') for d, _, ns in os.walk(src) for n in ns)
    if not names:
        sys.exit(f'{src} has no files')
    members, blobs, off = [], [], 0
    for rel in names:
        data = open(os.path.join(root, rel), 'rb').read()
        members.append({'p': rel, 'o': off, 'l': len(data)}); blobs.append(data); off += len(data)
    header = json.dumps({'files': members}, separators=(',', ':')).encode()
    out = os.path.join(root, sub + '.bundle')
    with open(out, 'wb') as f:
        f.write(b'DMB1' + struct.pack('>I', len(header)) + header)
        for b in blobs:
            f.write(b)
    kept = os.path.join(os.path.dirname(root), '_bundled', os.path.basename(root))
    os.makedirs(kept, exist_ok=True)
    shutil.move(src, os.path.join(kept, sub))
    print(f'{out}: {len(names)} files, {os.path.getsize(out)} bytes (originals moved to {kept}/{sub})')


if __name__ == '__main__':
    main()
