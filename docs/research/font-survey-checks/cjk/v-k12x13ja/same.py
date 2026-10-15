"""Count glyphs with identical pixel sets (by encoding) between two BDFs.
usage: same.py <a.bdf> <b.bdf>"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf
a = vbdf.read(sys.argv[1])['glyphs']
b = vbdf.read(sys.argv[2])['glyphs']
common = set(a) & set(b)
diff = sorted(c for c in common if a[c].pix != b[c].pix)
print('a', len(a), 'b', len(b), 'common', len(common), 'identical', len(common) - len(diff))
print('only a', len(set(a) - set(b)), 'only b', len(set(b) - set(a)))
for c in diff[:20]:
    print(hex(c), 'symdiff', len(a[c].pix ^ b[c].pix), a[c].bbx, b[c].bbx)
