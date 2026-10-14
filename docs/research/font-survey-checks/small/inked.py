# usage: python -I inked.py [--hex-height N] FONTFILE... -- coverage counting only glyphs with at least one set pixel
import sys, importlib.util, os
sys.stdout.reconfigure(encoding='utf-8')
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
a = sys.argv[1:]; hh = 16
if a[0] == '--hex-height': hh = int(a[1]); a = a[2:]
BLANK_OK = {0x20, 0xA0, 0xAD, 0x2800}
for f in a:
    data = fm.opener(f).read()
    glyphs, props = fm.read_hex(data, hh) if f.endswith('.hex') else fm.read_bdf(data)
    inked = {c for c, g in glyphs.items() if any(r and int(r, 16) for r in g.get('bits', []))} | (set(glyphs) & BLANK_OK)
    empty = sorted(c for c in glyphs if c not in inked)
    print(os.path.basename(f), 'glyphs', len(glyphs), 'inked', len(inked), 'empty', len(empty), ''.join(chr(c) for c in empty if c >= 0x20)[:80])
    print('   ', ' '.join('%s=%d/%d' % (k, len(inked & v), len(v)) for k, v in fm.TARGETS.items() if k != 'cjkUnified'), 'cjk=%d' % len(inked & fm.TARGETS['cjkUnified']))
