# Show one JIS code in k14 (R4), k14 (X.Org), both scaled at 40%, and k12.
# usage: python -I -X utf8 one.py k14r4.bdf k14xorg.bdf k12.bdf HEXCODE
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf, to_grid, from_grid, bdfresize_grid
_, R4 = read_bdf(sys.argv[1]); _, XO = read_bdf(sys.argv[2]); _, K12 = read_bdf(sys.argv[3])
code = int(sys.argv[4], 16)
print('char', bytes([(code >> 8) | 0x80, (code & 0xFF) | 0x80]).decode('euc_jp'))
def sc(F):
    g = F[code]; w, h, xo, yo = g['bbx']
    grid, _, _ = bdfresize_grid(to_grid(g['pix'], xo, yo + h - 1, w, h), 12, 12, 40, 100)
    return from_grid(grid, 0, 10)
for name, F in (('R4', R4), ('X.Org', XO)):
    g = F[code]
    print(name, '14x14')
    for row in to_grid(g['pix'], 0, 11, 14, 14):
        print('   ', ''.join('#' if v else '.' for v in row))
    s = sc(F)
    print(name, 'scaled 40%: equals k12?', s == K12[code]['pix'], ' |diff| =', len(s ^ K12[code]['pix']))
print('k12')
for row in to_grid(K12[code]['pix'], 0, 10, 12, 12):
    print('   ', ''.join('#' if v else '.' for v in row))
