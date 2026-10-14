# usage: python -I extents.py [--hex-height N] FONTFILE... -- ink extents, missing OC351 glyphs, text advance, T1/T2 density
import sys, importlib.util, json, os
sys.stdout.reconfigure(encoding='utf-8')
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
a = sys.argv[1:]; hh = 16
if a[0] == '--hex-height': hh = int(a[1]); a = a[2:]
TEXT = ("The quick brown fox jumps over the lazy dog. Energy stored: 1,234,567 RF of 2,000,000 RF. "
        "Status: running at 19.8 TPS, 64 items in buffer, next update in 5 s.")
def ink(g):
    w, h, xo, yo = g['bbx']; top = None; bot = None
    for j, row in enumerate(g.get('bits', [])[:h]):
        if row and int(row, 16):
            y = yo + h - 1 - j  # row's height above baseline (0 = row sitting on baseline)
            top = y if top is None else max(top, y); bot = y if bot is None else min(bot, y)
    return top, bot
for f in a:
    data = fm.opener(f).read()
    glyphs, props = fm.read_hex(data, hh) if f.endswith('.hex') else fm.read_bdf(data)
    asc = int(props.get('FONT_ASCENT', 0) or 0); desc = int(props.get('FONT_DESCENT', 0) or 0)
    res = {'file': __import__('os').path.basename(f), 'fontAscent': asc, 'fontDescent': desc}
    for name, rng in (('ascii', range(0x21, 0x7F)), ('latin1', range(0xA1, 0x100)), ('oc351', sorted(fm.TARGETS['oc351']))):
        tops = []; bots = []
        for c in rng:
            if c in glyphs:
                t, b = ink(glyphs[c])
                if t is not None: tops.append(t); bots.append(b)
        if tops: res[name + 'Ink'] = 'rows %d..%d (=%d px incl. baseline row)' % (min(bots), max(tops), max(tops) - min(bots) + 1)
    miss = [c for c in sorted(fm.TARGETS['oc351']) if c not in glyphs]
    res['oc351Missing'] = len(miss)
    res['oc351MissingSample'] = ''.join(chr(c) for c in miss if c < 0x2500)[:60] + (' + %d box/block' % sum(1 for c in miss if c >= 0x2500))
    adv = [glyphs[ord(ch)].get('dw', 0) if ord(ch) in glyphs else None for ch in TEXT]
    known = [x for x in adv if x is not None]
    res['textAvgAdvance'] = round(sum(known) / len(known), 2)
    res['textMissing'] = sum(1 for x in adv if x is None)
    print(json.dumps(res, ensure_ascii=False))
