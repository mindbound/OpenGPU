"""Count glyphs of A identical to B, for chosen ranges. Usage: python -I fsexcmp.py A.hex B.hex (data only)"""
import sys
def load(p):
    d = {}
    for line in open(p, encoding='ascii', errors='replace'):
        if ':' in line:
            a, b = line.strip().split(':', 1)
            try: d[int(a, 16)] = b.strip().upper()
            except ValueError: pass
    return d
a, b = load(sys.argv[1]), load(sys.argv[2])
R = {'ascii': range(0x20, 0x7F), 'latin1': range(0xA0, 0x100), 'box+blocks': range(0x2500, 0x25A0), 'all': sorted(a)}
for k, r in R.items():
    cps = [c for c in r if c in a]
    same = [c for c in cps if b.get(c) == a[c]]
    print(k, 'inA', len(cps), 'inB', sum(1 for c in cps if c in b), 'identical', len(same), ' '.join('%04X' % c for c in same[:40]))
