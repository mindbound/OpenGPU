"""Jar size of BDF fonts as cell tables at 1 bit per pixel, raw and deflated (own code, MIT).

Usage: python -I bdfpack.py LABEL=FONT.bdf ...
The BDF counterpart of hexpack.py, in the same layout: each glyph is drawn into a box of
max(advance, box width) x line height (ascent + descent, as atlascost.py counts it), with the
rows padded to whole bytes as .hex and BDF store them, plus a 3-byte codepoint and a 1-byte
width per glyph. Prints raw and zlib level-9 sizes per font and their sums; a jar deflates
each entry on its own, so the sum of the per-font figures is the jar cost. The last column
counts set source bits that fall outside the box (0 means every inked pixel was kept).
Reads BDF as data only; executes nothing from the files.
"""
import sys
import zlib


def read_bdf(path):
    props, glyphs, cur, rows = {}, [], None, None
    for line in open(path, 'rb').read().decode('latin-1').splitlines():
        w = line.split()
        if not w:
            continue
        if rows is not None:
            if w[0] == 'ENDCHAR':
                cur['rows'] = rows
                if cur.get('enc', -1) >= 0:
                    glyphs.append(cur)
                cur, rows = None, None
            else:
                rows.append(w[0])
            continue
        if w[0] == 'STARTCHAR':
            cur = {}
        elif cur is None:
            if w[0] in ('FONT_ASCENT', 'FONT_DESCENT', 'FONTBOUNDINGBOX'):
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


def pack(path):
    props, glyphs = read_bdf(path)
    asc = props.get('FONT_ASCENT', [0])[0]
    desc = props.get('FONT_DESCENT', [0])[0]
    line_h = asc + desc if asc + desc > 0 else props['FONTBOUNDINGBOX'][1]
    idx, bm = bytearray(), bytearray()
    lost = 0
    for g in sorted(glyphs, key=lambda g: g['enc']):
        bw, bh, bx, by = g['bbx']
        w = max(g.get('dw', bw), bw)
        rb = (w + 7) // 8
        cell = [0] * line_h
        top = asc - (by + bh)              # cell row of the glyph box's top row
        x0 = max(0, bx)
        for i, hexrow in enumerate(g['rows'][:bh]):
            r = top + i
            bits = int(hexrow, 16)
            nbits = len(hexrow) * 4
            for b in range(bw):
                if bits >> (nbits - 1 - b) & 1:
                    if 0 <= r < line_h and x0 + b < w:
                        cell[r] |= 1 << (rb * 8 - 1 - (x0 + b))
                    else:
                        lost += 1
        idx += g['enc'].to_bytes(3, 'little') + bytes([min(w, 255)])
        for r in cell:
            bm += r.to_bytes(rb, 'big')
    blob = bytes(idx + bm)
    return len(glyphs), len(idx), len(bm), len(blob), len(zlib.compress(blob, 9)), lost


def main():
    print('label\tglyphs\tindex\tbitmaps\traw\tdeflate9\tclipped_bits')
    tot_raw = tot_def = 0
    for arg in sys.argv[1:]:
        label, path = arg.split('=', 1)
        n, ni, nb, raw, dfl, lost = pack(path)
        tot_raw += raw
        tot_def += dfl
        print('%s\t%d\t%d\t%d\t%d\t%d\t%d' % (label, n, ni, nb, raw, dfl, lost))
    print('sum\t\t\t\t%d\t%d' % (tot_raw, tot_def))


if __name__ == '__main__':
    main()
