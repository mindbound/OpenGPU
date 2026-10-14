"""usage: python -I stack.py OUT.png SCALE SPECFILE
SPECFILE: UTF-8 lines 'FONTPATH<TAB>TEXT'; each line is drawn in its own font, stacked vertically (a style-mix mock-up).
Missing glyphs are drawn as red bars. Reads data only."""
import sys, os, importlib.util, zlib, struct, collections
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)


def load(f):
    data = fm.opener(f).read(); base = f[:-3] if f.endswith('.gz') else f
    ext = os.path.splitext(base)[1].lower()
    g, p = fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, 16)
    hs = [x['bbx'][1] + x['bbx'][3] for x in g.values() if 'bbx' in x]
    asc = int(p.get('FONT_ASCENT', 0) or 0) or max(hs); desc = int(p.get('FONT_DESCENT', 0) or 0)
    if 'hexHeight' in p:
        asc, desc = 14, 2
    adv = collections.Counter(g[c].get('dw') for c in range(0x21, 0x7F) if c in g).most_common(1)[0][0]
    return g, asc, desc, adv


out, scale, specfile = sys.argv[1], int(sys.argv[2]), sys.argv[3]
rows = [l.split('\t', 1) for l in open(specfile, encoding='utf-8').read().splitlines() if '\t' in l]
cache = {}
lines = []
for path, text in rows:
    if path not in cache:
        cache[path] = load(path)
    lines.append((cache[path], text))
W = 4 + max(sum(g[ord(c)].get('dw', adv) if ord(c) in g else adv for c in t) for (g, a, d, adv), t in lines)
H = 4 + sum(a + d + 3 for (g, a, d, adv), t in lines)
px = [[(24, 24, 32)] * W for _ in range(H)]
y0 = 2
for (g, asc, desc, adv), text in lines:
    x = 2; base_y = y0 + asc
    for ch in text:
        gl = g.get(ord(ch))
        if gl is None:
            for i in range(adv - 1):
                yy = base_y - 1
                if 0 <= yy < H and x + i < W:
                    px[yy][x + i] = (200, 60, 60)
            x += adv; continue
        w, h, xo, yo = gl['bbx']
        for j, row in enumerate(gl.get('bits', [])[:h]):
            v = int(row, 16) if row else 0; nb = len(row) * 4
            for i in range(w):
                if v >> (nb - 1 - i) & 1:
                    yy = base_y - yo - h + j; xx = x + xo + i
                    if 0 <= yy < H and 0 <= xx < W:
                        px[yy][xx] = (230, 230, 210)
        x += gl.get('dw', adv)
    y0 += asc + desc + 3
raw = b''.join(b'\x00' + b''.join(bytes(c) * scale for c in row) for row in px for _ in range(scale))


def chunk(k, d):
    return struct.pack('>I', len(d)) + k + d + struct.pack('>I', zlib.crc32(k + d) & 0xffffffff)


open(out, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W * scale, H * scale, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
print({'out': out, 'size': [W * scale, H * scale]})
