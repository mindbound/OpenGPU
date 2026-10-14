# Critic: independent BDF parse of every PixelFonts BDF. Usage: python -I c1_bdf.py <bdfdir>
import sys, os, collections

def parse(path):
    f = {'props': {}, 'glyphs': {}, 'path': path}
    with open(path, 'r', encoding='ascii') as fh:
        lines = fh.read().split('\n')
    i = 0; inprops = False; g = None; inbmp = False
    while i < len(lines):
        ln = lines[i].rstrip('\r'); i += 1
        if not ln: continue
        parts = ln.split()
        k = parts[0]
        if inbmp:
            if k == 'ENDCHAR':
                inbmp = False
                f['glyphs'][g['enc']] = g; g = None
            else:
                g['rows'].append(int(k, 16)); g['rowhex'].append(k)
            continue
        if k == 'FONT': f['xlfd'] = ln[5:]
        elif k == 'FONTBOUNDINGBOX': f['fbb'] = tuple(map(int, parts[1:5]))
        elif k == 'STARTPROPERTIES': inprops = True; f['nprops'] = int(parts[1])
        elif k == 'ENDPROPERTIES': inprops = False
        elif inprops: f['props'][k] = ' '.join(parts[1:])
        elif k == 'CHARS': f['chars'] = int(parts[1])
        elif k == 'STARTCHAR': g = {'name': ' '.join(parts[1:]), 'rows': [], 'rowhex': []}
        elif k == 'ENCODING': g['enc'] = int(parts[1])
        elif k == 'DWIDTH': g['dw'] = (int(parts[1]), int(parts[2]))
        elif k == 'SWIDTH': g['sw'] = (int(parts[1]), int(parts[2]))
        elif k == 'BBX': g['bbx'] = tuple(map(int, parts[1:5]))
        elif k == 'BITMAP': inbmp = True
        elif k in ('STARTFONT', 'SIZE', 'ENDFONT', 'COMMENT'): f.setdefault('misc', []).append(ln)
        else: f.setdefault('unknown', []).append(ln)
    return f

def ink(g):
    """set of (x, y) with y measured upward from baseline (y=0 is first row above baseline)."""
    w, h, xo, yo = g['bbx']
    nbytes = (w + 7) // 8
    s = set(); beyond = 0
    for r, v in enumerate(g['rows']):
        width_bits = len(g['rowhex'][r]) * 4
        for b in range(width_bits):
            if v >> (width_bits - 1 - b) & 1:
                if b >= w: beyond += 1
                else: s.add((xo + b, yo + (h - 1 - r)))
    return s, beyond

def load_all(d):
    fonts = {}
    for n in sorted(os.listdir(d)):
        if n.endswith('.bdf'):
            fonts[n[:-4]] = parse(os.path.join(d, n))
    return fonts

if __name__ == '__main__':
    d = sys.argv[1]
    fonts = load_all(d)
    print('files', len(fonts))
    propkeys = collections.Counter(); spacing = collections.Counter(); defch = collections.Counter()
    unknown = 0; comments = 0
    encsets = collections.Counter()
    xoffs = collections.Counter(); yoffs_neg = 0; yoff_nonzero_fonts = collections.Counter()
    beyond_tot = 0; past_adv = 0; prop_fonts = 0; mono = []
    for name, f in fonts.items():
        propkeys[tuple(sorted(f['props']))] += 1
        spacing[f['xlfd'].split('-')[11]] += 1
        defch[f['props'].get('DEFAULT_CHAR')] += 1
        unknown += len(f.get('unknown', [])); comments += sum(1 for m in f.get('misc', []) if m.startswith('COMMENT'))
        encsets[len(f['glyphs'])] += 1
        assert f['chars'] == len(f['glyphs']), name
        dws = set()
        for e, g in f['glyphs'].items():
            w, h, xo, yo = g['bbx']
            xoffs[xo] += 1
            if yo < 0: yoffs_neg += 1
            if yo != 0 and e != 32: yoff_nonzero_fonts[name] += 1
            assert len(g['rows']) == h, (name, e)
            s, bey = ink(g); beyond_tot += bey
            if s and max(x for x, y in s) >= g['dw'][0]: past_adv += 1
            dws.add(g['dw'][0])
        if len(dws) > 1: prop_fonts += 1
        else: mono.append(name)
    print('prop key sets', propkeys)
    print('XLFD spacing', spacing, 'DEFAULT_CHAR', defch, 'unknown lines', unknown, 'COMMENT lines', comments)
    print('glyph counts', encsets)
    print('BBX x offsets', dict(xoffs), 'neg y offsets', yoffs_neg)
    print('fonts with non-zero y offset on a non-space glyph:', len(yoff_nonzero_fonts))
    for n, c in sorted(yoff_nonzero_fonts.items()): print('   ', n, c)
    print('bits beyond BBX w', beyond_tot, 'glyphs with ink at or past advance', past_adv)
    print('proportional fonts', prop_fonts, 'monospace', mono)
    # metrics table
    print('\nname | asc desc | fbb | dw min..max | glyphs')
    for name, f in fonts.items():
        dw = [g['dw'][0] for g in f['glyphs'].values()]
        print(name, f['props']['FONT_ASCENT'], f['props']['FONT_DESCENT'], f['fbb'], min(dw), max(dw), len(dw))
