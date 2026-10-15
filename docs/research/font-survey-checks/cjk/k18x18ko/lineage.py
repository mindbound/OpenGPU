"""Compare glyph bitmaps of BDF fonts by ENCODING (same raster size).

Usage: python -I -X utf8 lineage.py <lo> <hi> <labelA=a.bdf> <labelB=b.bdf> [...]
Compares every font against the first one over encodings lo..hi inclusive.
Also checks, for an 18x18 font given with label prefix 'ko:', that
Hangul syllables have no ink outside the inner 16x16 at (1,1).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf  # noqa: E402


def main():
    lo, hi = int(sys.argv[1], 0), int(sys.argv[2], 0)
    fonts = []
    for arg in sys.argv[3:]:
        lab, p = arg.split('=', 1)
        if lab.startswith('ko:'):
            fbb, g = bdf.read_bdf(p)
            out = 0
            for cp in range(0xAC00, 0xD7A4):
                r = g[cp]
                if bdf.popcount(r) != bdf.popcount(bdf.crop(r, fbb[0], 1, 1, 16, 16)):
                    out += 1
            print(f'{lab}: syllables with ink outside inner 16x16 at (1,1): {out}')
            continue
        fbb, g = bdf.read_bdf(p)
        fonts.append((lab, fbb, g))
        enc = [e for e in g if lo <= e <= hi]
        inked = sum(1 for e in enc if bdf.popcount(g[e]) > 0)
        print(f'{lab}: fbb {fbb}; glyphs {len(g)}; in range {len(enc)}; inked {inked}; '
              f'max enc {max(g) if g else None}')
    if not fonts:
        return
    lab0, fbb0, g0 = fonts[0]
    for lab, fbb, g in fonts[1:]:
        keys = [e for e in range(lo, hi + 1) if e in g0 and e in g]
        same = sum(1 for e in keys if g0[e] == g[e])
        inked_same = sum(1 for e in keys if g0[e] == g[e] and bdf.popcount(g[e]) > 0)
        print(f'{lab0} vs {lab} [{lo}..{hi}]: common {len(keys)}; identical {same} '
              f'(inked identical {inked_same})')


if __name__ == '__main__':
    main()
