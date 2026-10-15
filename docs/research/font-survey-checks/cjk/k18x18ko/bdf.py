"""Minimal BDF / Unifont .hex reader shared by the k18x18ko scripts.

Glyphs are rendered into the font's FONTBOUNDINGBOX raster, returned as a
tuple of row integers (bit (w-1-x) set = pixel at column x), top row first.
"""
import gzip


def _open(path):
    if path.endswith('.gz'):
        return gzip.open(path, 'rt', encoding='latin-1')
    return open(path, 'r', encoding='latin-1')


def read_bdf(path):
    """Return (fbb, glyphs) where fbb = (w, h, xoff, yoff) and
    glyphs = {encoding: rows} rendered into the font bounding box."""
    fbb = None
    glyphs = {}
    enc = None
    bbx = None
    rows = None
    inbm = False
    with _open(path) as f:
        for line in f:
            line = line.rstrip('\r\n')
            if inbm:
                if line.startswith('ENDCHAR'):
                    inbm = False
                    if enc is not None and enc >= 0:
                        glyphs[enc] = _place(fbb, bbx, rows)
                    enc = None
                    continue
                rows.append(line.strip())
                continue
            if line.startswith('FONTBOUNDINGBOX'):
                p = line.split()
                fbb = tuple(int(x) for x in p[1:5])
            elif line.startswith('STARTCHAR'):
                enc = None
                bbx = None
            elif line.startswith('ENCODING'):
                p = line.split()
                enc = int(p[1])
                if enc < 0 and len(p) > 2:
                    enc = int(p[2])
            elif line.startswith('BBX'):
                bbx = tuple(int(x) for x in line.split()[1:5])
            elif line.startswith('BITMAP'):
                inbm = True
                rows = []
    return fbb, glyphs


def _place(fbb, bbx, hexrows):
    fw, fh, fx, fy = fbb
    if bbx is None:
        bbx = fbb
    bw, bh, bx, by = bbx
    out = [0] * fh
    top = (fy + fh) - (by + bh)
    left = bx - fx
    for i, hr in enumerate(hexrows[:bh]):
        if not hr:
            continue
        nbits = len(hr) * 4
        v = int(hr, 16) >> (nbits - bw) if nbits >= bw else int(hr, 16) << (bw - nbits)
        r = top + i
        if 0 <= r < fh:
            sh = fw - left - bw
            if sh >= 0:
                out[r] |= (v << sh) & ((1 << fw) - 1)
            else:
                out[r] |= (v >> (-sh)) & ((1 << fw) - 1)
    return tuple(out)


def read_hex(path):
    """Unifont .hex -> {cp: (width, rows16)}; rows as ints of width bits."""
    res = {}
    with _open(path) as f:
        for line in f:
            line = line.strip()
            if not line or ':' not in line:
                continue
            cp, data = line.split(':', 1)
            cp = int(cp, 16)
            n = len(data)
            if n == 32:
                w = 8
            elif n == 64:
                w = 16
            else:
                continue
            step = w // 4
            rows = tuple(int(data[i:i + step], 16) for i in range(0, n, step))
            res[cp] = (w, rows)
    return res


def crop(rows, fw, x, y, w, h):
    """Crop a w x h window at (x, y) from a raster of width fw."""
    out = []
    for r in rows[y:y + h]:
        out.append((r >> (fw - x - w)) & ((1 << w) - 1))
    while len(out) < h:
        out.append(0)
    return tuple(out)


def popcount(rows):
    return sum(bin(r).count('1') for r in rows)


def hamming(a, b):
    return sum(bin(x ^ y).count('1') for x, y in zip(a, b))
