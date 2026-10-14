"""usage: python -I scalecheck.py BASE.bdf SCALED.bdf K -- counts glyphs of SCALED that are exact KxK pixel copies of BASE."""
import sys, os, importlib.util
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
def pix(g):
    w, h, xo, yo = g['bbx']; s = set()
    for j, row in enumerate(g.get('bits', [])[:h]):
        v = int(row, 16) if row else 0; nb = len(row) * 4
        for i in range(w):
            if v >> (nb - 1 - i) & 1: s.add((xo + i, yo + h - 1 - j))
    return s
a, _ = fm.read_bdf(open(sys.argv[1], 'rb').read()); b, _ = fm.read_bdf(open(sys.argv[2], 'rb').read()); k = int(sys.argv[3])
same = diff = 0; ex = []
for c in b:
    if c not in a: continue
    pa = {(x * k + dx, y * k + dy) for x, y in pix(a[c]) for dx in range(k) for dy in range(k)}
    if pa == pix(b[c]) and b[c].get('dw') == k * a[c].get('dw'): same += 1
    else: diff += 1; ex.append('%04X' % c)
print({'exactScaled': same, 'different': diff, 'examples': ex[:10]})
