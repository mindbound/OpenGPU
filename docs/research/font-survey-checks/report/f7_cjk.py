"""29 §8 F7: misc-fixed's CJK faces measured against the narrow faces they would serve (own code, MIT).

Usage: python -I f7_cjk.py NARROW.bdf=WIDE.bdf [...]
For each face of each pair: cell (FONTBOUNDINGBOX), ascent, descent, glyph count, the advances
in use, and glyph boxes outside the cell. For the wide face: inked glyphs in kana U+3040-30FF,
CJK URO U+4E00-9FFF and Hangul syllables U+AC00-D7A3, the advances of those, and inked OC351
(U+0020-007E, U+00A0-00FF, U+2500-259F; the two spaces count). For the pair: cell heights,
ascent difference, and whether the wide advance is twice the narrow cell. For the narrow face:
inked halfwidth katakana U+FF61-FF9F, which it draws in its own cell.
Reads BDF as data only; executes nothing from the files.
"""
import collections
import sys

BLOCKS = (('kana', 0x3040, 0x30FF), ('URO', 0x4E00, 0x9FFF), ('Hangul', 0xAC00, 0xD7A3))
OC351 = [c for a, b in ((0x20, 0x7E), (0xA0, 0xFF), (0x2500, 0x259F)) for c in range(a, b + 1)]


def read_bdf(path):
    props, glyphs, cur, rows = {}, {}, None, None
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w:
            continue
        if rows is not None:
            if w[0] == 'ENDCHAR':
                cur['ink'] = any(int(r, 16) for r in rows)
                if cur.get('enc', -1) >= 0:
                    glyphs[cur['enc']] = cur
                cur, rows = None, None
            else:
                rows.append(w[0])
            continue
        if w[0] == 'STARTCHAR':
            cur = {}
        elif cur is None:
            if w[0] in ('FONTBOUNDINGBOX', 'FONT_ASCENT', 'FONT_DESCENT'):
                props[w[0]] = [int(v) for v in w[1:]]
        elif w[0] == 'ENCODING':
            cur['enc'] = int(w[1]) if len(w) == 2 else int(w[2])
        elif w[0] == 'DWIDTH':
            cur['dw'] = int(w[1])
        elif w[0] == 'BBX':
            cur['bbx'] = [int(v) for v in w[1:5]]
        elif w[0] == 'BITMAP':
            rows = []
    return props, glyphs


def face(path):
    props, glyphs = read_bdf(path)
    cw, ch = props['FONTBOUNDINGBOX'][:2]
    asc, desc = props['FONT_ASCENT'][0], props['FONT_DESCENT'][0]
    adv = collections.Counter(g.get('dw') for g in glyphs.values())
    outside = [c for c, g in glyphs.items()
               if not (g['bbx'][2] >= 0 and g['bbx'][2] + g['bbx'][0] <= g.get('dw', cw)
                       and g['bbx'][3] >= -desc and g['bbx'][3] + g['bbx'][1] <= asc)]
    name = path.replace('\\', '/').split('/')[-1]
    print('%s: cell %dx%d, ascent %d, descent %d, %d glyphs, advances %s, boxes outside their advance x cell height: %d%s'
          % (name, cw, ch, asc, desc, len(glyphs), dict(sorted(adv.items())), len(outside),
             (' (' + ' '.join('%04X' % c for c in sorted(outside)[:8]) + ')') if outside else ''))
    return name, cw, ch, asc, glyphs


for arg in sys.argv[1:]:
    narrow, wide = arg.split('=', 1)
    nn, nw, nh, na, ng = face(narrow)
    hw = [c for c in range(0xFF61, 0xFFA0) if c in ng and ng[c]['ink']]
    print('  %s halfwidth katakana U+FF61-FF9F inked: %d/63' % (nn, len(hw)))
    wn, ww, wh, wa, wg = face(wide)
    for label, a, b in BLOCKS:
        cps = [c for c in range(a, b + 1) if c in wg]
        inked = [c for c in cps if wg[c]['ink']]
        adv = collections.Counter(wg[c].get('dw') for c in cps)
        print('  %s %s: %d encoded, %d inked of %d, advances %s' % (wn, label, len(cps), len(inked), b - a + 1, dict(sorted(adv.items()))))
    oc = [c for c in OC351 if c in wg and (wg[c]['ink'] or c in (0x20, 0xA0))]
    print('  %s OC351 inked: %d/%d' % (wn, len(oc), len(OC351)))
    print('  pair %s + %s: heights %d and %d, ascent difference %+d px, wide cell %d = 2 x %d: %s'
          % (nn, wn, nh, wh, wa - na, ww, nw, ww == 2 * nw))
