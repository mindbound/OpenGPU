"""Classify OC font.hex glyphs against unscii-16 and two Unifont versions. Usage: python -I ocprov2.py OC UNSCII UF_A UF_B (data only)"""
import sys, gzip, collections
def load(p):
    f = gzip.open(p, 'rt', encoding='ascii', errors='replace') if p.endswith('.gz') else open(p, encoding='ascii', errors='replace')
    d = {}
    for line in f:
        if ':' in line:
            a, b = line.strip().split(':', 1)
            try: d[int(a, 16)] = b.strip().upper()
            except ValueError: pass
    return d
oc, un, ua, ub = (load(p) for p in sys.argv[1:5])
def cls(c):
    g = oc[c]; t = []
    if un.get(c) == g: t.append('unscii')
    if ua.get(c) == g: t.append('ufA')
    if ub.get(c) == g: t.append('ufB')
    return '+'.join(t) or 'other'
for name, w in (('narrow', 32), ('wide', 64)):
    cps = [c for c, g in oc.items() if len(g) == w]
    cnt = collections.Counter(cls(c) for c in cps)
    print(name, len(cps), dict(cnt.most_common()))
    oth = [c for c in cps if cls(c) == 'other']
    print('  other by page', dict(collections.Counter('%04X' % (c & ~0xFF) for c in oth).most_common(20)))
cjk = [c for c in range(0x4E00, 0xA000) if c in oc]
print('cjk', dict(collections.Counter(cls(c) for c in cjk).most_common()))
