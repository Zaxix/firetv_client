#!/usr/bin/env python3
"""Writes manifest.json (and optionally catalog.json) for a disc menu pack so Sharkfin can download it file by file.

  python3 make_manifest.py PACK_FOLDER [--catalog CATALOG_JSON]

Layout to host (any static web server, or the raw files of a private GitHub repository):
  <source>/catalog.json
  <source>/<pack id>/manifest.json
  <source>/<pack id>/<every file of the pack>

Stages decide what is fetched first and what the menu may do before the rest has arrived:
  0 pack.json and the intro      1 loop, music, sparkle frames      2 everything else (transition, page art)      3 the letter
"""
import argparse, hashlib, json, os, re, struct, sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def bundle_paths(path):
    """The member paths of a .bundle file (format: DMB1, 4-byte big-endian header length, JSON header, data)."""
    with open(path, 'rb') as f:
        head = f.read(8)
        if head[:4] != b'DMB1':
            sys.exit(f'{path} is not a bundle')
        n = struct.unpack('>I', head[4:])[0]
        hdr = json.loads(f.read(n))
    return [m['p'] for m in hdr['files']]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('pack')
    ap.add_argument('--catalog', help='write or update this catalog.json with the pack')
    a = ap.parse_args()
    root = os.path.abspath(a.pack)
    pack = json.load(open(os.path.join(root, 'pack.json'), encoding='utf-8'))
    pid = pack['id']
    if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', pid):
        sys.exit(f"pack id '{pid}' must be lowercase letters, digits, dot, dash or underscore")
    if os.path.basename(root) != pid:
        sys.exit(f"the folder must be named after the pack id ('{pid}'), it is '{os.path.basename(root)}'")

    video, music, sparkles = pack['video'], pack['music'], pack['sparkles']
    stage = {'pack.json': 0}
    if video.get('intro'): stage[video['intro']['file']] = 0
    stage[video['loop']['file']] = 1
    stage[music['file']] = 1
    stage[sparkles['index']] = 1
    stage[sparkles['data']] = 1
    if video.get('letter'): stage[video['letter']['file']] = 3

    files = []
    for dirpath, _, names in os.walk(root):
        for n in sorted(names):
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, root).replace(os.sep, '/')
            if rel == 'manifest.json' or n.startswith('.') or n.endswith('.part'):
                continue
            entry = {'path': rel, 'size': os.path.getsize(full), 'sha256': sha256(full), 'stage': stage.get(rel, 2)}
            if rel.endswith('.bundle'):
                entry['contains'] = bundle_paths(full)
            files.append(entry)
    files.sort(key=lambda f: (f['stage'], f['path']))
    missing = [p for p in stage if not any(f['path'] == p for f in files)]
    if missing:
        sys.exit(f'pack.json refers to files that are not in the folder: {missing}')
    json.dump({'schema': 1, 'id': pid, 'files': files}, open(os.path.join(root, 'manifest.json'), 'w'), indent=1)
    total = sum(f['size'] for f in files)
    print(f'{pid}: {len(files)} files, {total / 1e6:.1f} MB; stage 0 {sum(f["size"] for f in files if f["stage"] == 0) / 1e6:.1f} MB')

    if a.catalog:
        match = pack.get('match', {})
        entry = {'id': pid, 'title': pack.get('title', pid), 'match': {k: v for k, v in match.items() if v}, 'manifest': f'{pid}/manifest.json'}
        if not entry['match']:
            sys.exit('pack.json has no "match" (tmdb, imdb or titleRegex), so a film could not be matched to it')
        cat = json.load(open(a.catalog)) if os.path.exists(a.catalog) else {'schema': 1, 'packs': []}
        cat['packs'] = [p for p in cat['packs'] if p['id'] != pid] + [entry]
        json.dump(cat, open(a.catalog, 'w'), indent=1)
        print(f'catalog updated: {a.catalog}')


if __name__ == '__main__':
    main()
