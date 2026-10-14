# usage: python -I conv.py monogram BITMAP_JSON OUT_BDF | fbhex HEX OUT_BDF
# Converts glyph data (data only, nothing executed) into a plain BDF so the shared fontmeasure.py can read it.
import sys, json, collections
def write_bdf(out, name, glyphs, asc, desc, fbb, spacing, note):
    L = ['STARTFONT 2.1', 'FONT ' + name, 'SIZE %d 75 75' % (asc + desc), 'FONTBOUNDINGBOX %d %d %d %d' % fbb,
         'STARTPROPERTIES 4', 'FONT_ASCENT %d' % asc, 'FONT_DESCENT %d' % desc, 'SPACING "%s"' % spacing,
         'COPYRIGHT "%s"' % note, 'ENDPROPERTIES', 'CHARS %d' % len(glyphs)]
    for cp in sorted(glyphs):
        w, h, xo, yo, dw, rows = glyphs[cp]
        L += ['STARTCHAR U+%04X' % cp, 'ENCODING %d' % cp, 'SWIDTH 500 0', 'DWIDTH %d 0' % dw,
              'BBX %d %d %d %d' % (w, h, xo, yo), 'BITMAP'] + ['%02X' % r for r in rows] + ['ENDCHAR']
    L.append('ENDFONT')
    open(out, 'w', encoding='ascii').write('\n'.join(L) + '\n')
mode = sys.argv[1]
if mode == 'monogram':
    d = json.load(open(sys.argv[2], encoding='utf-8'))
    g = {}; bad = []; maxbit = 0; used = collections.Counter()
    for k, rows in d.items():
        if len(k) != 1: bad.append(k); continue
        for i, r in enumerate(rows):
            if r: used[i] += 1; maxbit = max(maxbit, r.bit_length())
        # LSB is the leftmost pixel; BDF wants MSB-left in a byte
        conv = [int('{:08b}'.format(r)[::-1], 2) for r in rows]
        g[ord(k)] = (max(5, max(r.bit_length() for r in rows)), len(rows), 0, -2, 6, conv)
    print('glyphs', len(g), 'multi-char keys', bad[:10], 'max bit width', maxbit, 'rows used', sorted(used.items()))
    write_bdf(sys.argv[3], 'monogram-from-bitmap-json', g, 10, 2, (5, 12, 0, -2), 'C', 'CC0 per credits.txt')
elif mode == 'fbhex':
    w = h = None; g = {}
    for line in open(sys.argv[2], encoding='ascii'):
        line = line.strip()
        if line.startswith('# Width:'): w = int(line.split(':')[1])
        elif line.startswith('# Height:'): h = int(line.split(':')[1])
        elif line and not line.startswith('#') and ':' in line:
            cp, bits = line.split(':'); rb = (w + 7) // 8 * 2
            rows = [int(bits[i * rb:(i + 1) * rb], 16) for i in range(h)]
            g[int(cp, 16)] = (w, h, 0, -1, w, rows)
    print('glyphs', len(g), 'cell', w, h)
    write_bdf(sys.argv[3], 'tom-thumb-freebsd', g, h - 1, 1, (w, h, 0, -1), 'C', 'CC0 per FreeBSD header')
