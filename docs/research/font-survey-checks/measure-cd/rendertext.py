"""usage: python -I rendertext.py OUT.png SCALE TEXTFILE FONT1 [FONT2 ...] [--hex-height N]
Renders the UTF-8 lines of TEXTFILE with a fallback chain (first font that has a code point wins),
all on one shared baseline; the primary font is drawn in light grey, fallback glyphs in a light blue tint,
missing glyphs as red bars. Reads data only."""
import sys, os, importlib.util, zlib, struct, collections
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)


def load(f, hh):
    data = fm.opener(f).read(); base = f[:-3] if f.endswith('.gz') else f
    ext = os.path.splitext(base)[1].lower()
    g, p = fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, hh) if ext == '.hex' else fm.read_psf(data)
    hs = [x['bbx'][1] + x['bbx'][3] for x in g.values() if 'bbx' in x]
    asc = int(p.get('FONT_ASCENT', 0) or 0) or max(hs)
    desc = int(p.get('FONT_DESCENT', 0) or 0)
    if 'hexHeight' in p:
        asc, desc = hh - 2, 2
    return g, asc, desc


def main(argv):
    hh = 16
    if '--hex-height' in argv:
        i = argv.index('--hex-height'); hh = int(argv[i + 1]); del argv[i:i + 2]
    out, scale, textfile, fonts = argv[0], int(argv[1]), argv[2], argv[3:]
    lines = open(textfile, encoding='utf-8').read().splitlines()
    fs = [load(f, hh) for f in fonts]
    asc = max(f[1] for f in fs); desc = max(f[2] for f in fs); lh = asc + desc + 2
    adv0 = collections.Counter(fs[0][0][c].get('dw') for c in range(0x21, 0x7F) if c in fs[0][0]).most_common(1)
    adv0 = adv0[0][0] if adv0 else 8

    def pick(ch):
        for k, (g, _, _) in enumerate(fs):
            if ord(ch) in g:
                return k, g[ord(ch)]
        return None, None
    W = 4 + max(sum((pick(ch)[1] or {}).get('dw', adv0) for ch in line) for line in lines)
    H = lh * len(lines) + 4
    px = [[(24, 24, 32)] * W for _ in range(H)]
    for li, line in enumerate(lines):
        x = 2; base_y = 2 + li * lh + asc
        for ch in line:
            k, g = pick(ch)
            if g is None:
                for i in range(adv0 - 1):
                    for yy in (base_y - asc + 1, base_y + desc - 1):
                        if 0 <= yy < H and x + i < W:
                            px[yy][x + i] = (200, 60, 60)
                x += adv0; continue
            col = (230, 230, 210) if k == 0 else (150, 200, 255)
            w, h, xo, yo = g['bbx']
            for j, row in enumerate(g.get('bits', [])[:h]):
                v = int(row, 16) if row else 0; nb = len(row) * 4
                for i in range(w):
                    if v >> (nb - 1 - i) & 1:
                        yy = base_y - yo - h + j; xx = x + xo + i
                        if 0 <= yy < H and 0 <= xx < W:
                            px[yy][xx] = col
            x += g.get('dw', adv0)
    raw = b''.join(b'\x00' + b''.join(bytes(c) * scale for c in row) for row in px for _ in range(scale))

    def chunk(k, d):
        return struct.pack('>I', len(d)) + k + d + struct.pack('>I', zlib.crc32(k + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W * scale, H * scale, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    open(out, 'wb').write(png)
    print({'out': out, 'size': [W * scale, H * scale], 'lineHeight': lh, 'ascent': asc, 'descent': desc})


main(sys.argv[1:])
