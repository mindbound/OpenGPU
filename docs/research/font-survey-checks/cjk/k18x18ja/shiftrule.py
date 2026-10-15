"""Check what distinguishes the 18x18ja Hangul glyphs that were moved 1 px
left from hanglg16 from those copied in place: left/right margins of the
source glyph.

Usage: python -I -X utf8 shiftrule.py <18x18ja.bdf> <hanglg16.bdf>
"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdflib  # noqa: E402
from compare18ja import gl_to_unicode  # noqa: E402


def margins(rows):
    ink = 0
    for r in rows:
        ink |= r
    if not ink:
        return None
    left = 0
    while not (ink >> (15 - left)) & 1:
        left += 1
    right = 0
    while not (ink >> right) & 1:
        right += 1
    return left, right


def main():
    _, _, t, _ = bdflib.read_bdf(sys.argv[1])
    _, _, h, _ = bdflib.read_bdf(sys.argv[2])
    stats = collections.defaultdict(collections.Counter)
    for enc, rows in h.items():
        u = gl_to_unicode(enc, 'euc_kr')
        if u is None or not (0xAC00 <= u <= 0xD7A3) or u not in t:
            continue
        inner, _ = bdflib.inner16_of_18(t[u])
        if inner == rows:
            kind = 'in place'
        elif bdflib.shift(rows, -1, 0) == inner:
            kind = 'moved 1px left'
        else:
            kind = 'other'
        stats[kind][margins(rows)] += 1
    for k, c in stats.items():
        print(k, sum(c.values()), 'source (left,right) margins:', dict(c.most_common(8)))


if __name__ == '__main__':
    main()
