"""Glyph boxes against a BDF's own ascent and descent (own code, MIT).

Usage: python -I boxcheck.py FONT.bdf ...
Prints FONT_ASCENT, FONT_DESCENT, the highest inked row above the baseline and the lowest
below it over all glyphs, and how many inked glyphs reach above the ascent or below the
descent, with the first few codepoints. A line pitch of ascent + descent clips those glyphs.
Reads BDF as data only; executes nothing from the files.
"""
import sys


def glyphs(path):
    props, cur, rows = {}, None, None
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w:
            continue
        if rows is not None:
            if w[0] == 'ENDCHAR':
                if cur.get('enc', -1) >= 0:
                    cur['rows'] = rows
                    yield props, cur
                cur, rows = None, None
            else:
                rows.append(int(w[0], 16))
            continue
        if w[0] == 'STARTCHAR':
            cur = {}
        elif cur is None:
            if w[0] in ('FONT_ASCENT', 'FONT_DESCENT'):
                props[w[0]] = int(w[1])
        elif w[0] == 'ENCODING':
            cur['enc'] = int(w[1]) if len(w) == 2 else int(w[2])
        elif w[0] == 'BBX':
            cur['bbx'] = [int(v) for v in w[1:5]]
        elif w[0] == 'BITMAP':
            rows = []


for path in sys.argv[1:]:
    top = bottom = None
    above, below = [], []
    props = {}
    for props, g in glyphs(path):
        bw, bh, bx, by = g['bbx']
        inked = [i for i, r in enumerate(g['rows'][:bh]) if r]
        if not inked:
            continue
        hi = by + bh - inked[0]            # rows above the baseline, up to the top inked row
        lo = -(by + bh - 1 - inked[-1])    # rows below the baseline, down to the bottom inked row
        top = hi if top is None else max(top, hi)
        bottom = lo if bottom is None else max(bottom, lo)
        if hi > props['FONT_ASCENT']:
            above.append(g['enc'])
        if lo > props['FONT_DESCENT']:
            below.append(g['enc'])
    name = path.replace('\\', '/').split('/')[-1]
    print('%s: ascent %d, descent %d; inked rows reach %d above and %d below the baseline; '
          '%d glyphs above the ascent (%s), %d below the descent (%s)'
          % (name, props['FONT_ASCENT'], props['FONT_DESCENT'], top, bottom,
             len(above), ' '.join('%04X' % c for c in sorted(above)[:6]),
             len(below), ' '.join('%04X' % c for c in sorted(below)[:6])))
