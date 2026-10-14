# usage: python -I selfdup.py [--hex-height N] FONT... -- printable ASCII + Latin-1 letters whose ink is pixel-identical within one font, and near-identical (1 px apart) confusable pairs
import sys, importlib.util, os, itertools
sys.stdout.reconfigure(encoding='utf-8')
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
a = sys.argv[1:]; hh = 16
if a[0] == '--hex-height': hh = int(a[1]); a = a[2:]
def norm(g):
    w, h, xo, yo = g['bbx']; pts = set()
    for j, row in enumerate(g.get('bits', [])[:h]):
        v = int(row, 16) if row else 0; nb = len(row) * 4
        for i in range(w):
            if v >> (nb - 1 - i) & 1: pts.add((xo + i, yo + h - 1 - j))
    return frozenset(pts)
WATCH = ['0O', '0o', 'Oo', '1l', '1I', 'lI', 'l|', 'I|', 'il', '5S', '2Z', '8B', '6b', '9g', 'gq', 'mw', 'mM', 'wW', 'nh', 'uv', 'vy', 'ce', 'ao', 'DO', 'CG', 'HN', 'MN', 'MW', 'HM', ',.', ';:', 'xX', 'oc']
for f in a:
    data = fm.opener(f).read()
    g = fm.read_hex(data, hh)[0] if f.endswith('.hex') else fm.read_bdf(data)[0]
    cs = [c for c in range(0x21, 0x7F) if c in g]
    N = {c: norm(g[c]) for c in cs}
    groups = {}
    for c in cs: groups.setdefault(N[c], []).append(chr(c))
    dups = [''.join(v) for v in groups.values() if len(v) > 1]
    near = []
    for p in WATCH:
        x, y = ord(p[0]), ord(p[1])
        if x in N and y in N:
            d = len(N[x] ^ N[y])
            if d <= 2: near.append('%s:%d' % (p, d))
    print('%-22s identical: %-16s  pairs differing by <=2 px: %s' % (os.path.basename(f), ' '.join(dups) or '-', ' '.join(near) or '-'))
