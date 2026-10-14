"""Measure bitmap font files (BDF, Unifont-style .hex, PSF1/PSF2; optionally gzipped) for OpenGPU's font survey.
Usage: python -I fontmeasure.py [--hex-height N] FILE... ; prints one JSON object per file.
       python -I fontmeasure.py [--hex-height N] --render OUT.png [--scale K] FILE ; draws a fixed sample text (missing glyphs as red bars).
Reads data only; never executes anything from the files."""
import sys, os, gzip, json, collections

SETS = {
    'ascii': [(0x20, 0x7E)], 'latin1': [(0xA0, 0xFF)], 'latinExtA': [(0x100, 0x17F)],
    'greekLetters': [(0x391, 0x3A1), (0x3A3, 0x3A9), (0x3B1, 0x3C9)], 'cyrillic': [(0x400, 0x45F)],
    'genPunct': [(0x2010, 0x2027)], 'arrows': [(0x2190, 0x21FF)], 'boxDrawing': [(0x2500, 0x257F)],
    'blocks': [(0x2580, 0x259F)], 'geometric': [(0x25A0, 0x25FF)], 'braille': [(0x2800, 0x28FF)],
    'oc351': [(0x20, 0x7E), (0xA0, 0xFF), (0x2500, 0x259F)], 'cjkUnified': [(0x4E00, 0x9FFF)],
}

def expand(ranges):
    return {c for a, b in ranges for c in range(a, b + 1)}

TARGETS = {k: expand(v) for k, v in SETS.items()}

def opener(path):
    return gzip.open(path, 'rb') if path.endswith('.gz') else open(path, 'rb')

def read_bdf(data):
    glyphs = {}; props = {}; cur = None; in_bitmap = False; rows = []
    for raw in data.decode('latin-1').splitlines():
        w = raw.split()
        if not w: continue
        k = w[0]
        if in_bitmap:
            if k == 'ENDCHAR':
                cur['rows'] = len(rows); in_bitmap = False
                if cur.get('enc', -1) >= 0: glyphs[cur['enc']] = cur
                cur = None
            else:
                rows.append(k)
                cur.setdefault('bits', []).append(k)
            continue
        if k == 'STARTCHAR': cur = {}
        elif k == 'ENCODING' and cur is not None: cur['enc'] = int(w[1]) if len(w) == 2 else (int(w[2]) if len(w) > 2 else -1)
        elif k == 'DWIDTH' and cur is not None: cur['dw'] = int(w[1])
        elif k == 'BBX' and cur is not None: cur['bbx'] = tuple(int(x) for x in w[1:5])
        elif k == 'BITMAP' and cur is not None: in_bitmap = True; rows = []
        elif cur is None and k in ('FONTBOUNDINGBOX', 'SPACING', 'PIXEL_SIZE', 'FONT_ASCENT', 'FONT_DESCENT', 'COPYRIGHT', 'NOTICE', 'FONT', 'FAMILY_NAME', 'DEFAULT_CHAR', 'CHARSET_REGISTRY', 'FOUNDRY', 'WEIGHT_NAME', 'SLANT'):
            props[k] = ' '.join(w[1:])[:200]
    return glyphs, props

def read_hex(data, height):
    glyphs = {}
    for line in data.decode('ascii', 'replace').splitlines():
        line = line.strip()
        if not line or ':' not in line: continue
        cp, bits = line.split(':', 1)
        try: c = int(cp, 16)
        except ValueError: continue
        nbytes = len(bits) // 2
        width = nbytes * 8 // height
        rb = width // 8 * 2
        glyphs[c] = {'dw': width, 'bbx': (width, height, 0, 0), 'bits': [bits[i * rb:(i + 1) * rb] for i in range(height)]}
    return glyphs, {'hexHeight': str(height)}

def read_psf(data):
    if data[:2] == b'\x36\x04':
        mode, charsize = data[2], data[3]; n = 512 if mode & 1 else 256; width, height = 8, charsize
        off_glyphs = 4; off = 4 + n * charsize; table = {}
        if mode & 2:
            g = 0; p = off
            while g < n and p + 1 < len(data):
                u = data[p] | data[p + 1] << 8; p += 2
                if u == 0xFFFF: g += 1
                elif u == 0xFFFE: pass
                else: table.setdefault(u, g)
        cps = table.keys() if table else range(n)
    elif data[:4] == b'\x72\xb5\x4a\x86':
        import struct
        _, _, hsize, flags, n, charsize, height, width = struct.unpack('<8I', data[:32])
        off_glyphs = hsize; off = hsize + n * charsize; table = {}
        if flags & 1:
            g = 0; p = off; seq = False
            while g < n and p < len(data):
                b = data[p]
                if b == 0xFF: g += 1; p += 1; seq = False; continue
                if b == 0xFE: seq = True; p += 1; continue
                l = 1 if b < 0x80 else 2 if b < 0xE0 else 3 if b < 0xF0 else 4
                ch = data[p:p + l].decode('utf-8', 'replace'); p += l
                if not seq and len(ch) == 1: table.setdefault(ord(ch), g)
        cps = table.keys() if table else range(n)
    else:
        raise ValueError('not a PSF file')
    rb = (width + 7) // 8
    def bits_of(g):
        s = off_glyphs + g * charsize
        return [data[s + r * rb:s + (r + 1) * rb].hex() for r in range(height)]
    glyphs = {c: {'dw': width, 'bbx': (width, height, 0, 0), 'bits': bits_of(table[c] if table else c)} for c in cps}
    return glyphs, {'psfCell': '%dx%d' % (width, height)}

def measure(path, hex_height):
    data = opener(path).read()
    base = path[:-3] if path.endswith('.gz') else path
    ext = os.path.splitext(base)[1].lower()
    if ext == '.bdf': fmt = 'bdf'; glyphs, props = read_bdf(data)
    elif ext == '.hex': fmt = 'hex'; glyphs, props = read_hex(data, hex_height)
    elif ext in ('.psf', '.psfu'): fmt = 'psf'; glyphs, props = read_psf(data)
    else: raise ValueError('unsupported format: ' + ext)
    cps = set(glyphs)
    dws = collections.Counter(g.get('dw') for g in glyphs.values())
    ascii_dw = collections.Counter(glyphs[c].get('dw') for c in range(0x21, 0x7F) if c in glyphs)
    common = ascii_dw.most_common(1)[0][0] if ascii_dw else None
    mono = bool(common) and all(g.get('dw') in (common, 2 * common) for g in glyphs.values())
    heights = collections.Counter(g['bbx'][1] for g in glyphs.values() if 'bbx' in g)
    over = [c for c, g in glyphs.items() if common and 'bbx' in g and mono and g['bbx'][0] + max(g['bbx'][2], 0) > g.get('dw', 0)]
    cov = {k: '%d/%d' % (len(cps & v), len(v)) for k, v in TARGETS.items()}
    asc = int(props.get('FONT_ASCENT', 0) or 0); desc = int(props.get('FONT_DESCENT', 0) or 0)
    return {'file': os.path.basename(path), 'format': fmt, 'glyphs': len(cps), 'monospace': mono,
            'commonAdvance': common, 'advances': dict(dws.most_common(8)), 'asciiAdvanceRange': [min(ascii_dw), max(ascii_dw)] if ascii_dw else None,
            'lineHeight': (asc + desc) or (max(heights) if heights else None), 'bbxHeights': dict(heights.most_common(4)),
            'glyphsWiderThanAdvance': len(over), 'hasFFFD': 0xFFFD in cps, 'coverage': cov,
            'props': {k: v for k, v in props.items()}}

SAMPLE = ["The quick brown fox jumps over the lazy dog 0123456789",
          "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
          "äöüß éèê ñ ÆØÅ ¿¡ «»",
          "┌──┬──┐ │ ░▒▓█ ▀▄▌▐ ←↑→↓ ●■▲",
          "└──┴──┘ ΑΒΓΔ αβγδ АБВГ абвг ⠿⠇",
          "日本語 中文 한국어"]

def render(path, out, scale, hex_height):
    import zlib, struct
    data = opener(path).read()
    base = path[:-3] if path.endswith('.gz') else path
    ext = os.path.splitext(base)[1].lower()
    glyphs, props = read_bdf(data) if ext == '.bdf' else read_hex(data, hex_height) if ext == '.hex' else read_psf(data)
    adv = collections.Counter(glyphs[c].get('dw') for c in range(0x21, 0x7F) if c in glyphs).most_common(1)[0][0]
    hs = [g['bbx'][1] + g['bbx'][3] for g in glyphs.values() if 'bbx' in g]
    asc = int(props.get('FONT_ASCENT', 0) or 0) or max(hs); desc = int(props.get('FONT_DESCENT', 0) or 0)
    lh = asc + desc + 2
    W = max(sum((glyphs[ord(ch)].get('dw', adv) if ord(ch) in glyphs else adv) for ch in line) for line in SAMPLE) + 4
    H = lh * len(SAMPLE) + 4
    px = [[(24, 24, 32)] * W for _ in range(H)]
    for li, line in enumerate(SAMPLE):
        x = 2; base_y = 2 + li * lh + asc
        for ch in line:
            g = glyphs.get(ord(ch))
            if g is None:
                for i in range(adv - 1):
                    for yy in (base_y - asc + 1, base_y + desc - 1):
                        if 0 <= yy < H and x + i < W: px[yy][x + i] = (200, 60, 60)
                x += adv; continue
            w, h, xo, yo = g['bbx']; bits = g.get('bits', [])
            for j, row in enumerate(bits[:h]):
                v = int(row, 16) if row else 0; nb = len(row) * 4
                for i in range(w):
                    if v >> (nb - 1 - i) & 1:
                        yy = base_y - yo - h + j; xx = x + xo + i
                        if 0 <= yy < H and 0 <= xx < W: px[yy][xx] = (230, 230, 210)
            x += g.get('dw', adv)
    raw = b''.join(b'\x00' + b''.join(bytes(c) * scale for c in row) for row in px for _ in range(scale))
    def chunk(k, d): return struct.pack('>I', len(d)) + k + d + struct.pack('>I', zlib.crc32(k + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W * scale, H * scale, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    open(out, 'wb').write(png)
    return {'file': os.path.basename(path), 'preview': out, 'size': [W * scale, H * scale]}

def main(argv):
    hh = 16; files = []; out = None; scale = 2
    it = iter(argv)
    for a in it:
        if a == '--hex-height': hh = int(next(it))
        elif a == '--render': out = next(it)
        elif a == '--scale': scale = int(next(it))
        else: files.append(a)
    if out:
        print(json.dumps(render(files[0], out, scale, hh))); return
    for f in files:
        try: print(json.dumps(measure(f, hh), ensure_ascii=False))
        except Exception as e: print(json.dumps({'file': os.path.basename(f), 'error': repr(e)}))

if __name__ == '__main__':
    main(sys.argv[1:])
