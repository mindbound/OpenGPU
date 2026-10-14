"""Classify each glyph of OC's font.hex by where an identical bitmap exists: viznut's unscii-16.hex, GNU Unifont, both, or neither.
Usage: python -I ocprov.py OC.hex UNSCII16.hex UNIFONT_ALL.hex[.gz]   (data only)"""
import sys, gzip, collections, json

def load(p):
    f = gzip.open(p, 'rt', encoding='ascii', errors='replace') if p.endswith('.gz') else open(p, encoding='ascii', errors='replace')
    d = {}
    for line in f:
        line = line.strip()
        if ':' not in line: continue
        a, b = line.split(':', 1)
        try: d[int(a, 16)] = b.strip().upper()
        except ValueError: pass
    return d

oc, un, uf = (load(p) for p in sys.argv[1:4])
SETS = {'ascii': range(0x20, 0x7F), 'latin1': range(0xA0, 0x100), 'latinExtA': range(0x100, 0x180),
        'greek': range(0x370, 0x400), 'cyrillic': range(0x400, 0x460), 'arrows': range(0x2190, 0x2200),
        'box': range(0x2500, 0x2580), 'blocks': range(0x2580, 0x25A0), 'geometric': range(0x25A0, 0x2600),
        'braille': range(0x2800, 0x2900), 'cjk': range(0x4E00, 0xA000), 'kana': range(0x3040, 0x3100)}
SETS['oc351'] = list(SETS['ascii']) + list(SETS['latin1']) + list(range(0x2500, 0x25A0))

def cls(c):
    g = oc[c]; a = un.get(c) == g; b = uf.get(c) == g
    return 'both' if a and b else 'unscii' if a else 'unifont' if b else 'other'

out = {}
for width, name in ((32, 'narrow'), (64, 'wide')):
    cps = [c for c, g in oc.items() if len(g) == width]
    out[name] = dict(collections.Counter(cls(c) for c in cps)); out[name]['total'] = len(cps)
for k, r in SETS.items():
    cps = [c for c in r if c in oc]
    out[k] = dict(collections.Counter(cls(c) for c in cps)); out[k]['present'] = len(cps)
# narrow glyphs that match neither, by Unicode block (first 16 bits >> 7 coarse)
other_narrow = [c for c, g in oc.items() if len(g) == 32 and cls(c) == 'other']
blocks = collections.Counter('%04X' % (c & ~0xFF) for c in other_narrow)
out['otherNarrowByPage'] = dict(blocks.most_common(25))
out['oc351OtherList'] = ' '.join('%04X' % c for c in SETS['oc351'] if c in oc and cls(c) == 'other')
out['oc351UnifontList'] = ' '.join('%04X' % c for c in SETS['oc351'] if c in oc and cls(c) == 'unifont')
# unscii-16 glyphs that OC changed
changed = [c for c in un if c in oc and oc[c] != un[c]]
out['unscii16Glyphs'] = len(un); out['unscii16ChangedInOC'] = len(changed)
out['unscii16ChangedInOC_oc351'] = ' '.join('%04X' % c for c in SETS['oc351'] if c in changed)
print(json.dumps(out, indent=1))
