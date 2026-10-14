"""Mean best-shift pixel IoU of ASCII glyphs (0x21-0x7E) between a reference 8x16 font and candidates. Usage: python -I lookalike.py REF CAND... (data only)"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
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


def pix(rows, w=8):
    return {(x, y) for y, r in enumerate(rows) for x in range(w) if r >> (w - 1 - x) & 1}
ref = cells(sys.argv[1])
for cand in sys.argv[2:]:
    c = cells(cand); tot = 0; n = 0
    for cp in range(0x21, 0x7F):
        if cp not in ref or cp not in c: continue
        a = pix(ref[cp]); b = pix(c[cp]); best = 0
        for dx in (-1, 0, 1):
            for dy in (-2, -1, 0, 1, 2):
                bb = {(x + dx, y + dy) for x, y in b}
                u = len(a | bb); best = max(best, len(a & bb) / u if u else 1)
        tot += best; n += 1
    print('%-22s meanIoU vs ref %.3f over %d glyphs' % (os.path.basename(cand), tot / n, n))
