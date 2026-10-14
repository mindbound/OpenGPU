# usage: python -I dump.py [--hex-height N] FONTFILE CHARS -- print glyphs side by side as #/. art (baseline-aligned)
import sys, importlib.util, os
sys.stdout.reconfigure(encoding='utf-8')
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
a = sys.argv[1:]; hh = 16
if a[0] == '--hex-height': hh = int(a[1]); a = a[2:]
data = fm.opener(a[0]).read()
glyphs, props = fm.read_hex(data, hh) if a[0].endswith('.hex') else fm.read_bdf(data)
asc = int(props.get('FONT_ASCENT', 0) or 0) or hh; desc = int(props.get('FONT_DESCENT', 0) or 0)
cols = []
for ch in a[1]:
    g = glyphs.get(ord(ch))
    if not g: cols.append((ch, ['?'] * (asc + desc), 1)); continue
    w, h, xo, yo = g['bbx']; dw = g.get('dw', w); W = max(dw, w + max(xo, 0))
    grid = [['.'] * W for _ in range(asc + desc)]
    for j, row in enumerate(g.get('bits', [])[:h]):
        v = int(row, 16) if row else 0; nb = len(row) * 4
        for i in range(w):
            if v >> (nb - 1 - i) & 1:
                y = asc - yo - h + j; x = xo + i
                if 0 <= y < asc + desc and 0 <= x < W: grid[y][x] = '#'
    cols.append((ch, [''.join(r) for r in grid], W))
print('  '.join(c[0].ljust(c[2]) for c in cols))
for y in range(asc + desc):
    print('  '.join(c[1][y] for c in cols) + ('   <- baseline' if y == asc - 1 else ''))
