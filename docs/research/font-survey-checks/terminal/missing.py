"""List code points of the OC351 set (and optional extra ranges) missing from a font. Usage: python -I missing.py [--hex-height N] FILE... (data only)"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared'))
import fontmeasure as fm
sys.stdout.reconfigure(encoding="utf-8")
hh = 16; files = []
it = iter(sys.argv[1:])
for a in it:
    if a == '--hex-height': hh = int(next(it))
    else: files.append(a)
for f in files:
    data = fm.opener(f).read()
    base = f[:-3] if f.endswith('.gz') else f
    ext = os.path.splitext(base)[1].lower()
    g, _ = fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, hh) if ext == '.hex' else fm.read_psf(data)
    miss = sorted(fm.TARGETS['oc351'] - set(g))
    print(os.path.basename(f), len(miss), ' '.join('%04X%s' % (c, '(' + chr(c) + ')' if c > 0x20 else '') for c in miss))
