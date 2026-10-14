"""28 Q1 as answered on 2026-10-14: font 0 = misc-fixed 6x13 (8x13 the alternative).

Usage: python -I q1_font0.py 6x13.bdf [8x13.bdf ...]
Reads each BDF as data only. For each face: cell, glyph count, advances, whether every
glyph fits the cell, U+FFFD, inked coverage of 28's subset (U+0020-007E, U+00A0-00FF,
U+2500-259F) and of the Greek letters and Cyrillic block, atlas bytes, and line capacity per tier. Then 28 §7.1's synthetic CI table
at the face's cell: the same row rule masked to w columns, and a box missing glyph.
"""
import sys

SUBSET = [c for a, b in ((0x20, 0x7E), (0xA0, 0xFF), (0x2500, 0x259F)) for c in range(a, b + 1)]
GREEK = [c for a, b in ((0x391, 0x3A1), (0x3A3, 0x3A9), (0x3B1, 0x3C9)) for c in range(a, b + 1)]
CYRILLIC = list(range(0x400, 0x460))
TIERS = (('T1', 160, 100), ('T2', 320, 200), ('T3', 640, 400))


def read_bdf(path):
    props, glyphs, cur, rows = {}, {}, None, None
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w:
            continue
        if rows is not None:
            if w[0] == 'ENDCHAR':
                cur['rows'] = rows
                if cur.get('enc', -1) >= 0:
                    glyphs[cur['enc']] = cur
                cur, rows = None, None
            else:
                rows.append(int(w[0], 16))
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


def check(name, ok, info=''):
    print('%s %s%s' % ('PASS' if ok else 'FAIL', name, (' -- %s' % (info,)) if info else ''))
    return ok


def synthetic(w, h):
    mask = (0xFF << (8 - w)) & 0xFF
    box = bytes([mask] + [0x80 | (0x80 >> (w - 1))] * (h - 2) + [mask])
    def g(cp):
        if cp in (0x20, 0xA0):
            return bytes(h)
        if 0x21 <= cp <= 0x7E or 0xA1 <= cp <= 0xFF:
            return bytes(((7 * cp + 29 * r) & 0xFF) & mask for r in range(h))
        return box
    drawn = [g(cp) for cp in list(range(0x21, 0x7F)) + list(range(0xA1, 0x100))]
    print('synthetic %dx%d: mask 0x%02X, box rows 0 and %d = 0x%02X, rows 1-%d = 0x%02X'
          % (w, h, mask, h - 1, mask, h - 2, box[1]))
    check('  189 drawn, pairwise distinct, none equal to the box or the blank',
          len(drawn) == 189 and len(set(drawn)) == 189 and box not in drawn and bytes(h) not in drawn)
    check('  every one of the %d bit columns set somewhere' % w,
          all(any(row >> (7 - b) & 1 for gl in drawn for row in gl) for b in range(w)))
    check('  no bit set outside the %d columns' % w, all(row & ~mask & 0xFF == 0 for gl in drawn + [box] for row in gl))


for path in sys.argv[1:]:
    props, glyphs = read_bdf(path)
    cw, ch, cx, cy = props['FONTBOUNDINGBOX']
    asc, desc = props['FONT_ASCENT'][0], props['FONT_DESCENT'][0]
    print('== %s: cell %dx%d, ascent %d, descent %d, %d glyphs' % (path.replace('\\', '/').split('/')[-1], cw, ch, asc, desc, len(glyphs)))
    check('ascent + descent = cell height', asc + desc == ch)
    check('every advance = cell width', all(g.get('dw') == cw for g in glyphs.values()))
    outside = [c for c, g in glyphs.items()
               if not (g['bbx'][2] >= 0 and g['bbx'][2] + g['bbx'][0] <= cw and g['bbx'][3] >= -desc and g['bbx'][3] + g['bbx'][1] <= asc)]
    check('every glyph box inside the cell', not outside, ' '.join('%04X' % c for c in outside[:12]))
    check('U+FFFD present', 0xFFFD in glyphs)
    inked = [c for c in SUBSET if c in glyphs and (any(glyphs[c]['rows']) or c in (0x20, 0xA0))]
    check('28 subset inked', len(inked) == len(SUBSET), '%d/%d' % (len(inked), len(SUBSET)))
    for name, cps in (('Greek letters', GREEK), ('Cyrillic U+0400-045F', CYRILLIC)):
        n = sum(1 for c in cps if c in glyphs and any(glyphs[c]['rows']))
        check('%s inked' % name, n == len(cps), '%d/%d' % (n, len(cps)))
    print('atlas at 1 B per texel: whole face %d B, subset + U+FFFD %d B; slots in 1024x1024: %d'
          % (len(glyphs) * cw * ch, (len(SUBSET) + 1) * cw * ch, (1024 // cw) * (1024 // ch)))
    print('fill charge per codepoint: %d px' % (cw * ch))
    print('cells per line x lines: ' + ', '.join('%s %dx%d' % (t, W // cw, H // ch) for t, W, H in TIERS))
    synthetic(cw, ch)
