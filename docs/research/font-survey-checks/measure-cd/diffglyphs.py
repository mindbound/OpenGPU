"""usage: python -I diffglyphs.py A B [--hex-height N] -- count code points whose bitmaps differ between two fonts, per block (normalised to a full cell)."""
import sys, os, collections, importlib.util
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
args = sys.argv[1:]; hh = 16
if '--hex-height' in args: i = args.index('--hex-height'); hh = int(args[i + 1]); del args[i:i + 2]
def load(f):
    data = fm.opener(f).read(); base = f[:-3] if f.endswith('.gz') else f; ext = os.path.splitext(base)[1].lower()
    return (fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, hh))[0]
def pix(g):
    w, h, xo, yo = g['bbx']; s = set()
    for j, row in enumerate(g.get('bits', [])[:h]):
        v = int(row, 16) if row else 0; nb = len(row) * 4
        for i in range(w):
            if v >> (nb - 1 - i) & 1: s.add((xo + i, yo + h - 1 - j))
    return frozenset(s)
a, b = load(args[0]), load(args[1])
both = set(a) & set(b); diff = collections.Counter(); tot = collections.Counter()
def blk(c):
    for name, lo, hi in (('cjkURO', 0x4E00, 0x9FFF), ('cjkExtA', 0x3400, 0x4DBF), ('kana', 0x3040, 0x30FF), ('hangul', 0xAC00, 0xD7A3), ('fullwidth', 0xFF00, 0xFFEF), ('ascii+latin1', 0x20, 0xFF)):
        if lo <= c <= hi: return name
    return 'other'
for c in both:
    k = blk(c); tot[k] += 1
    if pix(a[c]) != pix(b[c]) or a[c].get('dw') != b[c].get('dw'): diff[k] += 1
print({'common': len(both), 'onlyA': len(set(a) - set(b)), 'onlyB': len(set(b) - set(a)), 'differPerBlock': {k: '%d/%d' % (diff[k], tot[k]) for k in tot}})
