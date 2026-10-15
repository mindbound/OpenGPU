"""Pairwise glyph comparison of two GL-encoded BDFs, and per-block match of
18x18ja against one source.
usage: python -I -X utf8 vpair.py <a.bdf> <b.bdf> [<18x18ja.bdf> <codec> [prefix8f]]
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf  # noqa: E402

a, _ = vbdf.read_bdf(sys.argv[1])
b, _ = vbdf.read_bdf(sys.argv[2])
common = set(a) & set(b)
same = sum(1 for k in common if a[k] == b[k])
print('A glyphs', len(a), 'B glyphs', len(b), 'common codes', len(common), 'identical', same)
kanji = [k for k in common if (k >> 8) >= 0x30]
nonk = [k for k in common if (k >> 8) < 0x30]
ks = sum(1 for k in kanji if a[k] == b[k])
ns = sum(1 for k in nonk if a[k] == b[k])
small = sum(1 for k in kanji if a[k] != b[k] and len(a[k] ^ b[k]) < 10)
print('rows>=0x30: %d/%d identical; of the rest %d differ by <10 px' % (ks, len(kanji), small))
print('rows<0x30: %d/%d identical' % (ns, len(nonk)))
if len(sys.argv) > 3:
    t, _ = vbdf.read_bdf(sys.argv[3])
    codec = sys.argv[4]
    pre = b'\x8f' if len(sys.argv) > 5 else b''
    for lab, g in (('A', a), ('B', b)):
        u, _ = vbdf.to_unicode(g, codec, pre)
        c = collections.Counter()
        for cp, s in u.items():
            if cp not in t or not s:
                continue
            blk = 'CJK' if 0x4E00 <= cp <= 0x9FFF else 'other'
            ink = t[cp]
            if frozenset((x + 1, y) for x, y in s) == ink:
                c[(blk, 'exact')] += 1
            elif vbdf.norm(s)[0] == vbdf.norm(ink)[0]:
                c[(blk, 'shift')] += 1
            else:
                c[(blk, 'diff')] += 1
        print('18x18ja vs', lab, dict(sorted(c.items())))
