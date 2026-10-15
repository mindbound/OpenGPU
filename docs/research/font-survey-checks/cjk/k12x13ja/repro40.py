# Count k12 glyphs bit-identical to a k14 file reduced 12/14 by area coverage at 40%, per JIS class.
# usage: python -I -X utf8 repro40.py k12.bdf k14.bdf
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf, to_grid, from_grid, bdfresize_grid, jis_class
_, K12 = read_bdf(sys.argv[1]); _, K14 = read_bdf(sys.argv[2])
tot = {}; ok = {}
for code, g in K14.items():
    w, h, xo, yo = g['bbx']
    grid, _, _ = bdfresize_grid(to_grid(g['pix'], xo, yo + h - 1, w, h), 12, 12, 40, 100)
    s = from_grid(grid, 0, 10)
    c = jis_class(code)
    if not K12[code]['pix']:
        continue
    tot[c] = tot.get(c, 0) + 1
    ok[c] = ok.get(c, 0) + (s == K12[code]['pix'])
for c in sorted(tot):
    print('%-15s %4d / %4d identical' % (c, ok[c], tot[c]))
print('all', sum(ok.values()), '/', sum(tot.values()))
