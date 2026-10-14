# usage: python -I bdfcompare.py A.bdf B.bdf -- count printable-ASCII glyphs whose ink (baseline-aligned, left-trimmed) is identical
import sys, importlib.util, os
sys.stdout.reconfigure(encoding='utf-8')
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
def norm(g):
    w, h, xo, yo = g['bbx']; pts = set()
    for j, row in enumerate(g.get('bits', [])[:h]):
        v = int(row, 16) if row else 0; nb = len(row) * 4
        for i in range(w):
            if v >> (nb - 1 - i) & 1: pts.add((xo + i, yo + h - 1 - j))
    if not pts: return frozenset()
    mx = min(x for x, _ in pts)
    return frozenset((x - mx, y) for x, y in pts)
A = fm.read_bdf(fm.opener(sys.argv[1]).read())[0]; B = fm.read_bdf(fm.opener(sys.argv[2]).read())[0]
cs = [c for c in range(0x21, 0x7F) if c in A and c in B]
same = [c for c in cs if norm(A[c]) == norm(B[c])]
print('%s vs %s: %d/%d identical: %s' % (os.path.basename(sys.argv[1]), os.path.basename(sys.argv[2]), len(same), len(cs), ''.join(chr(c) for c in same)))
