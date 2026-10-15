"""List 12x13ja glyphs in CJK-ish ranges whose code point is not the Unicode image of any k12 JIS code.
usage: extras.py <12x13ja.bdf> <k12.bdf>"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf
u = vbdf.read(sys.argv[1])['glyphs']
k = vbdf.read(sys.argv[2])['glyphs']
mapped = {}
for code in k:
    for s in vbdf.jis_to_uni(code):
        mapped.setdefault(ord(s), code)
ranges = [(0x3000, 0x30FF), (0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0xFF00, 0xFFEF)]
extra = [cp for cp in sorted(u) if any(a <= cp <= b for a, b in ranges) and cp not in mapped and u[cp].bbx[0] >= 7]
print('CJK-area code points in 12x13ja (width>=7):', sum(1 for cp in u if any(a <= cp <= b for a, b in ranges) and u[cp].bbx[0] >= 7))
print('not mapped from k12:', len(extra), ' '.join('U+%04X' % c for c in extra))
# for each extra, is the bitmap identical (with shift) to some k12 glyph?
kp = {c: g.pix for c, g in k.items()}
for cp in extra:
    hits = [hex(c) for c, p in kp.items() if vbdf.best_match(u[cp].pix, p, 1)[0] == 0]
    print('U+%04X' % cp, 'bbx', u[cp].bbx, 'identical to k12', hits[:5])
