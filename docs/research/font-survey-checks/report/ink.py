"""Independent check (main session, 2026-10-14; own code, MIT with the repo): inked coverage of OC351 and a few sets in BDF files, plus cell geometry.
Usage: python -I -X utf8 ink.py FILE.bdf ...  (reads data only)"""
import sys, os, collections

SETS = {
    'oc351': [(0x20, 0x7E), (0xA0, 0xFF), (0x2500, 0x259F)],
    'box': [(0x2500, 0x257F)], 'blocks': [(0x2580, 0x259F)],
    'greek': [(0x391, 0x3A1), (0x3A3, 0x3A9), (0x3B1, 0x3C9)], 'cyr': [(0x400, 0x45F)],
}
BLANK_OK = {0x20, 0xA0}

def parse(path):
    g = {}; cur = None; inb = False; props = {}
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w: continue
        if inb:
            if w[0] == 'ENDCHAR':
                inb = False
                if cur.get('enc', -1) >= 0: g[cur['enc']] = cur
                cur = None
            else:
                cur['ink'] = cur.get('ink', False) or int(w[0], 16) != 0
            continue
        if w[0] == 'STARTCHAR': cur = {}
        elif cur is not None and w[0] == 'ENCODING': cur['enc'] = int(w[-1]) if len(w) == 2 else int(w[2])
        elif cur is not None and w[0] == 'DWIDTH': cur['dw'] = int(w[1])
        elif cur is not None and w[0] == 'BITMAP': inb = True
        elif cur is None and w[0] in ('FONTBOUNDINGBOX', 'FONT_ASCENT', 'FONT_DESCENT', 'COPYRIGHT'):
            props[w[0]] = ' '.join(w[1:])
    return g, props

for p in sys.argv[1:]:
    g, props = parse(p)
    out = {}
    for k, rs in SETS.items():
        cps = [c for a, b in rs for c in range(a, b + 1)]
        inked = [c for c in cps if c in g and (g[c].get('ink') or c in BLANK_OK)]
        miss = [c for c in cps if c not in inked]
        out[k] = '%d/%d' % (len(inked), len(cps)) + ('' if not miss or len(miss) > 12 else ' miss ' + ' '.join('%04X' % c for c in miss))
    adv = collections.Counter(v.get('dw') for v in g.values())
    print(os.path.basename(p), 'glyphs', len(g), 'adv', dict(adv.most_common(3)), props, out)
