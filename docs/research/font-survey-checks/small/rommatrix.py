# usage: python -I rommatrix.py UNSCII_HEX ROMTXT... -- pairwise count of identical printable-ASCII glyphs, plus union/unique for the hex font
import sys, os, importlib.util
sys.stdout.reconfigure(encoding='utf-8')
spec = importlib.util.spec_from_file_location('rc', os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), 'romcompare_lib.py'))
rc = importlib.util.module_from_spec(spec); spec.loader.exec_module(rc)
fonts = [('unscii-8', rc.hexfont(sys.argv[1]))] + [(os.path.basename(p)[5:-4], rc.txtfont(p)) for p in sys.argv[2:]]
asc = range(0x21, 0x7F)
print('%-10s' % '' + ''.join('%9s' % n[:8] for n, _ in fonts))
for n1, f1 in fonts:
    row = []
    for n2, f2 in fonts:
        row.append('%9s' % ('-' if n1 == n2 else '%d/%d' % (sum(1 for c in asc if c in f1 and c in f2 and f1[c] == f2[c]), sum(1 for c in asc if c in f1 and c in f2))))
    print('%-10s' % n1[:10] + ''.join(row))
u = fonts[0][1]; roms = fonts[1:]
anym = [c for c in asc if c in u and any(c in f and f[c] == u[c] for _, f in roms)]
print('unscii-8 printable ASCII identical to at least one reference: %d/94; unique: %s' % (len(anym), ''.join(chr(c) for c in asc if c in u and c not in anym)))
