"""Compare two fixed-cell fonts glyph by glyph after normalising each glyph to its full cell bitmap.
Usage: python -I cellcmp.py A B   (BDF or 8x16 .hex; data only). Prints identical counts for ASCII, Latin-1, box+blocks."""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared'))
import fontmeasure as fm

def cells(path):
    data = fm.opener(path).read()
    base = path[:-3] if path.endswith('.gz') else path
    if base.endswith('.hex'):
        g, p = fm.read_hex(data, 16); asc, desc = 14, 2  # .hex rows are already full cells
        return {c: tuple(int(r, 16) for r in v['bits']) for c, v in g.items() if v['dw'] == 8}
    g, p = fm.read_bdf(data)
    asc = int(p.get('FONT_ASCENT', 0)); desc = int(p.get('FONT_DESCENT', 0)); H = asc + desc
    out = {}
    for c, v in g.items():
        w, h, xo, yo = v['bbx']; cw = v.get('dw', w)
        rows = [0] * H
        for j, r in enumerate(v.get('bits', [])[:h]):
            val = int(r, 16) if r else 0; nb = len(r) * 4
            y = asc - yo - h + j
            for i in range(w):
                if val >> (nb - 1 - i) & 1:
                    x = xo + i
                    if 0 <= y < H and 0 <= x < cw: rows[y] |= 1 << (cw - 1 - x)
        out[c] = tuple(rows)
    return out

def norm(rows):
    rows = list(rows)
    while rows and rows[0] == 0: rows.pop(0)
    while rows and rows[-1] == 0: rows.pop()
    m = 0
    for r in rows: m |= r
    while m and not m & 1: m >>= 1; rows = [r >> 1 for r in rows]
    return tuple(rows)

sys.stdout.reconfigure(encoding='utf-8')
a, b = cells(sys.argv[1]), cells(sys.argv[2])
for name, r in (('ascii', range(0x21, 0x7F)), ('latin1', range(0xA1, 0x100)), ('box+blocks', range(0x2500, 0x25A0))):
    both = [c for c in r if c in a and c in b]
    same = [c for c in both if a[c] == b[c] and any(a[c])]
    shape = [c for c in both if any(a[c]) and norm(a[c]) == norm(b[c])]
    print('%-10s compared %3d identical %3d shape-identical %3d  %s' % (name, len(both), len(same), len(shape), ''.join(chr(c) for c in shape)))
