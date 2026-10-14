"""Sums of atlascost.py rows for named font sets (own code, MIT).

Usage: python -I setcost.py ATLASCOST.out NAME=LABEL,LABEL,... ...
Prints, per set, the glyph count and the R8 and 1-bit-per-texel atlas bytes of its fonts
(each font whole), and the R8 total in 1 024 x 1 024 pages. Reads atlascost.py's output only.
"""
import sys

rows = {}
for line in open(sys.argv[1], encoding='utf-8'):
    f = line.rstrip('\n').split('\t')
    if f[0] != 'label':
        rows[f[0]] = f
print('set\tfonts\tglyphs\tR8_all\t1bpp_all\tR8_pages')
for arg in sys.argv[2:]:
    name, labels = arg.split('=', 1)
    labels = labels.split(',')
    g = sum(int(rows[k][1]) for k in labels)
    r8 = sum(int(rows[k][4]) for k in labels)
    b1 = sum(int(rows[k][5]) for k in labels)
    print('%s\t%d\t%d\t%d\t%d\t%.2f' % (name, len(labels), g, r8, b1, r8 / 1048576))
