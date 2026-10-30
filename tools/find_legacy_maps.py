#!/usr/bin/env python3
"""
Read-only scan for HuskSync legacy locked maps in player data.

For every world folder given, indexes all data/map_*.dat files, then scans
playerdata/*.dat (inventory + ender chest) for items carrying legacy
`canvas_data`. Each canvas is compared, in both pixel orientations, against
the map files of ALL given worlds. A match proves which orientation is right
and identifies maps the plugin will treat as EXISTING.

Usage:
  python3 find_legacy_maps.py <world_dir> [<world_dir> ...]
  e.g. python3 find_legacy_maps.py /var/lib/pterodactyl/volumes/<uuid>/world ...
"""
import gzip
import hashlib
import struct
import sys
import zlib
from pathlib import Path

CANVAS_KEY = b'\x07\x00\x0bcanvas_data'
COLORS_KEY = b'\x07\x00\x06colors'
LOCKED_KEY = b'\x01\x00\x06locked'
MAP_ID_KEY = b'\x03\x00\x10minecraft:map_id'
SIZE = 128 * 128


def colors_of(nbt):
    i = nbt.find(COLORS_KEY)
    if i < 0:
        return None
    n = struct.unpack('>i', nbt[i + 9:i + 13])[0]
    c = nbt[i + 13:i + 13 + n]
    return c if n == SIZE and len(c) == SIZE else None


def transpose(c):
    out = bytearray(SIZE)
    for x in range(128):
        for y in range(128):
            out[x + y * 128] = c[x * 128 + y]
    return bytes(out)


def md5(b):
    return hashlib.md5(b).hexdigest()


def index_maps(world):
    idx = {}
    data = world / 'data'
    for f in data.glob('map_*.dat'):
        try:
            raw = f.read_bytes()
            if not raw:
                continue
            nbt = zlib.decompressobj(31).decompress(raw)
        except Exception:
            continue
        c = colors_of(nbt)
        if c is None or not any(c):
            continue
        li = nbt.find(LOCKED_KEY)
        locked = li >= 0 and nbt[li + 9] != 0
        idx.setdefault(md5(c), []).append((world.parent.name[:8] + '/' + world.name, f.stem, locked))
    return idx


def main():
    worlds = [Path(p).resolve() for p in sys.argv[1:]]
    if not worlds:
        print(__doc__)
        sys.exit(1)

    index = {}
    for w in worlds:
        part = index_maps(w)
        for k, v in part.items():
            index.setdefault(k, []).extend(v)
        print(f'indexed {sum(len(v) for v in part.values())} non-blank maps in {w}')

    total = matched = 0
    votes = {'transposed': 0, 'as-stored': 0}
    for w in worlds:
        pdir = w / 'playerdata'
        for pf in sorted(pdir.glob('*.dat')):
            try:
                d = gzip.open(pf).read()
            except Exception:
                continue
            i = 0
            while (i := d.find(CANVAS_KEY, i)) != -1:
                n = struct.unpack('>i', d[i + 14:i + 18])[0]
                canvas = d[i + 18:i + 18 + n]
                m = d.find(MAP_ID_KEY, i)
                map_id = struct.unpack('>i', d[m + 19:m + 23])[0] if m != -1 else None
                ender = d.rfind(b'EnderItems', 0, i) != -1
                i += 1
                total += 1
                try:
                    c = colors_of(zlib.decompressobj(31).decompress(canvas))
                except Exception:
                    c = None
                where = f'{w.parent.name[:8]}/{w.name} {pf.stem} map_id={map_id} {"ender" if ender else "inv"}'
                if c is None:
                    print(f'[unreadable] {where}')
                    continue
                if not any(c):
                    print(f'[blank]      {where}')
                    continue
                hits = []
                for name, cand in (('transposed', transpose(c)), ('as-stored', c)):
                    for hit in index.get(md5(cand), []):
                        hits.append((name, hit))
                if hits:
                    matched += 1
                    for name in {h[0] for h in hits}:
                        votes[name] += 1
                    desc = ', '.join(f'{name}->{loc}:{stem}{"" if locked else "(unlocked)"}'
                                     for name, (loc, stem, locked) in hits)
                    print(f'[EXISTING]   {where}  {desc}')
                else:
                    print(f'[no match]   {where}  canvas={md5(c)[:12]}')

    print(f'\nlegacy maps found: {total}, matched an existing map: {matched}')
    print(f'orientation votes: {votes}')


if __name__ == '__main__':
    main()
