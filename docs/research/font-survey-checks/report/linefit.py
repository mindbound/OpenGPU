"""How much text fits per tier: characters per line and lines per surface (own code, MIT).

Usage: python -I linefit.py LABEL=FONT.bdf ...
For each font and each tier (T1 160x100, T2 320x200, T3 640x400), at integer scale 1 and 2:
the number of characters of a fixed English sample whose advances fit in one line of the
surface's width (for a monospace face this is floor(W / (w*s))), and floor(H / (pitch*s)) lines,
where the pitch is ascent + descent, as tiermock.py stacks lines. A codepoint the font lacks
advances by the font's most common ASCII advance. Reads BDF as data only.
"""
import collections
import sys

SAMPLE = ("Text in OpenGPU is drawn at any pixel position; a label, a readout or a paragraph "
          "like this one only needs a legible face. Core 1532 K, coolant 98 %, pumps A ok. ") * 8
TIERS = (('T1', 160, 100), ('T2', 320, 200), ('T3', 640, 400))


def read_bdf(path):
    props, adv, cur = {}, {}, None
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w:
            continue
        if w[0] == 'STARTCHAR':
            cur = {}
        elif w[0] == 'ENDCHAR':
            if cur is not None and cur.get('enc', -1) >= 0:
                adv[cur['enc']] = cur.get('dw', 0)
            cur = None
        elif cur is None:
            if w[0] in ('FONT_ASCENT', 'FONT_DESCENT'):
                props[w[0]] = int(w[1])
        elif w[0] == 'ENCODING':
            cur['enc'] = int(w[1]) if len(w) == 2 else int(w[2])
        elif w[0] == 'DWIDTH':
            cur['dw'] = int(w[1])
    return props, adv


def fit(adv, default, width):
    x = n = 0
    for ch in SAMPLE:
        a = adv.get(ord(ch), default)
        if x + a > width:
            break
        x += a
        n += 1
    return n


print('label\tadvances\tpitch\t' + '\t'.join('%s s=%d' % (t, s) for s in (1, 2) for t, _, _ in TIERS))
for arg in sys.argv[1:]:
    label, path = arg.split('=', 1)
    props, adv = read_bdf(path)
    pitch = props['FONT_ASCENT'] + props['FONT_DESCENT']
    ascii_adv = [adv[c] for c in range(0x20, 0x7F) if c in adv]
    default = collections.Counter(ascii_adv).most_common(1)[0][0]
    kind = 'mono %d' % default if len(set(ascii_adv)) == 1 else 'prop %d-%d' % (min(ascii_adv), max(ascii_adv))
    cells = []
    for s in (1, 2):
        for _, W, H in TIERS:
            cells.append('%dx%d' % (fit(adv, default, W // s), H // (pitch * s)))
    print('%s\t%s\t%d\t%s' % (label, kind, pitch, '\t'.join(cells)))
