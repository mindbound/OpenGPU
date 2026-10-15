# For 12x13ja CJK-area code points not mapped from k12, look for an identical k12 glyph (any code, small shift).
# usage: python -I -X utf8 extras.py 12x13ja.bdf k12.bdf
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf, shift
_, U = read_bdf(sys.argv[1]); _, K = read_bdf(sys.argv[2])
idx = {}
for code, g in K.items():
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            idx.setdefault(shift(g['pix'], dx, dy), []).append(code)
for u in [0x3099, 0x309A, 0x4E25, 0x9AD9, 0xFF02, 0xFF07, 0xFF0D, 0xFF5E, 0xFFE0, 0xFFE1, 0xFFE2, 0xFFE4, 0xFFE6]:
    hit = idx.get(U[u]['pix'], [])
    print('U+%04X %s  identical k12 glyph(s): %s' % (u, chr(u), ' '.join('%04X(%s)' % (c, bytes([(c >> 8) | 0x80, (c & 0xFF) | 0x80]).decode('euc_jp')) for c in hit) or '-'))
