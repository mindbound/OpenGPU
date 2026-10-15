# Per-class histogram of the (dx, dy) shift that makes 12x13ja identical to k12; and X.Org vs Kuhn 12x13ja glyph equality.
# usage: python -I -X utf8 shifts.py kuhn-12x13ja.bdf k12.bdf xorg-12x13ja.bdf
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf, best_match, jis_to_unicode_candidates, jis_class
_, U = read_bdf(sys.argv[1]); _, K = read_bdf(sys.argv[2]); _, X = read_bdf(sys.argv[3])
W = (-2, -1, 0, 1, 2)
hist = {}
nonident = {}
for code, g in K.items():
    u = [c for c in jis_to_unicode_candidates(code) if c in U][0]
    if not g['pix'] or not U[u]['pix']:
        continue
    h, j, dx, dy = best_match(U[u]['pix'], g['pix'], W, W)
    cls = jis_class(code)
    if h == 0:
        hist.setdefault(cls, {}).setdefault((dx, dy), 0)
        hist[cls][(dx, dy)] += 1
    else:
        nonident.setdefault(cls, []).append('%04X>U+%04X(%d)' % (code, u, h))
for cls in sorted(hist):
    print(cls, sorted(hist[cls].items(), key=lambda t: -t[1]))
for cls in sorted(nonident):
    print('non-identical', cls, len(nonident[cls]), ' '.join(nonident[cls][:60]))
same = sum(1 for u in U if u in X and U[u]['pix'] == X[u]['pix'] and U[u]['bbx'] == X[u]['bbx'])
print('Kuhn 12x13ja vs X.Org 12x13ja: code points', len(U), len(X), 'identical glyphs', same,
      'only-in-Kuhn', len(set(U) - set(X)), 'only-in-X.Org', len(set(X) - set(U)))
