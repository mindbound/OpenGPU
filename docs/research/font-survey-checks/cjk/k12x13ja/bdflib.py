# Minimal BDF reader and glyph helpers (standard library only).
# A glyph is stored as a frozenset of (x, y) pixels in baseline coordinates:
# x to the right of the origin, y upward from the baseline (y = 0 is the row just above the baseline).


def read_bdf(path):
    """Return (props, glyphs) where glyphs maps ENCODING -> dict(bbx, dwidth, pix)."""
    props = {}
    glyphs = {}
    with open(path, 'rb') as f:
        lines = f.read().decode('latin-1').splitlines()
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        if line.startswith('STARTCHAR'):
            enc = None
            bbx = None
            dw = None
            i += 1
            while not lines[i].startswith('BITMAP'):
                t = lines[i].split()
                if t and t[0] == 'ENCODING':
                    enc = int(t[1])
                    if enc == -1 and len(t) > 2:
                        enc = -1 - int(t[2])  # keep non-standard separately
                elif t and t[0] == 'BBX':
                    bbx = tuple(int(v) for v in t[1:5])
                elif t and t[0] == 'DWIDTH':
                    dw = int(t[1])
                i += 1
            i += 1
            rows = []
            while not lines[i].startswith('ENDCHAR'):
                rows.append(lines[i].strip())
                i += 1
            w, h, xo, yo = bbx
            pix = set()
            for r, hexrow in enumerate(rows[:h]):
                if not hexrow:
                    continue
                val = int(hexrow, 16)
                nbits = len(hexrow) * 4
                for c in range(w):
                    if val >> (nbits - 1 - c) & 1:
                        # row r counted from the top of the bbox; top row y = yo + h - 1
                        pix.add((xo + c, yo + h - 1 - r))
            if enc is not None and enc >= 0:
                glyphs[enc] = {'bbx': bbx, 'dwidth': dw, 'pix': frozenset(pix)}
        else:
            t = line.split(None, 1)
            if t and t[0] in ('FONT_ASCENT', 'FONT_DESCENT', 'PIXEL_SIZE', 'DEFAULT_CHAR'):
                props[t[0]] = int(t[1])
            elif t and t[0] in ('FONT', 'FONTBOUNDINGBOX', 'CHARSET_REGISTRY', 'COPYRIGHT'):
                props[t[0]] = t[1] if len(t) > 1 else ''
        i += 1
    return props, glyphs


def to_grid(pix, x0, y_top, w, h):
    """Rasterise pixel set into a w x h tuple-of-tuples grid; column x0.., rows from y_top downward."""
    return tuple(tuple(1 if (x0 + c, y_top - r) in pix else 0 for c in range(w)) for r in range(h))


def from_grid(grid, x0, y_top):
    s = set()
    for r, row in enumerate(grid):
        for c, v in enumerate(row):
            if v:
                s.add((x0 + c, y_top - r))
    return frozenset(s)


def shift(pix, dx, dy):
    return frozenset((x + dx, y + dy) for x, y in pix)


def hamming(a, b):
    return len(a ^ b)


def jaccard(a, b):
    u = len(a | b)
    return 1.0 if u == 0 else len(a & b) / u


def best_match(a, b, dxs=(0,), dys=(0,)):
    """Min Hamming distance between a and b shifted by (dx, dy); returns (ham, jac, dx, dy)."""
    best = None
    for dx in dxs:
        for dy in dys:
            bb = shift(b, dx, dy)
            h = hamming(a, bb)
            if best is None or h < best[0]:
                best = (h, jaccard(a, bb), dx, dy)
    return best


def bdfresize_grid(src, newbbw, newbbh, threshold_num, threshold_den):
    """Area-coverage resize as in Kagotani's bdfresize charresize.c.
    src is a grid (rows of 0/1) of size bbh x bbw. Destination pixel is set when its gray
    score >= graylevel, graylevel = bbw*bbh*threshold_num // threshold_den (integer)."""
    bbh = len(src)
    bbw = len(src[0]) if bbh else 0
    gray = [[0] * newbbw for _ in range(newbbh)]
    for y in range(bbh):
        for x in range(bbw):
            if not src[y][x]:
                continue
            X = x * newbbw
            Y = y * newbbh
            X1 = X + newbbw
            Y1 = Y + newbbh
            nxb, nyb = X // bbw, Y // bbh
            nxe, nye = -(-X1 // bbw), -(-Y1 // bbh)
            for ny in range(nyb, nye):
                for nx in range(nxb, nxe):
                    nX, nY = nx * bbw, ny * bbh
                    gray[ny][nx] += (min(X1, nX + bbw) - max(X, nX)) * (min(Y1, nY + bbh) - max(Y, nY))
    graylevel = bbw * bbh * threshold_num // threshold_den
    return tuple(tuple(1 if gray[r][c] >= graylevel else 0 for c in range(newbbw)) for r in range(newbbh)), gray, graylevel


def nearest_grid(src, neww, newh):
    h = len(src)
    w = len(src[0])
    return tuple(tuple(src[min(h - 1, int((r + 0.5) * h / newh))][min(w - 1, int((c + 0.5) * w / neww))]
                       for c in range(neww)) for r in range(newh))


def jis_to_unicode_candidates(code):
    """JIS X 0208 code (0x2121..0x7E7E) -> list of candidate Unicode code points."""
    hi, lo = code >> 8, code & 0xFF
    b = bytes([hi | 0x80, lo | 0x80])
    out = []
    for codec in ('euc_jp', 'euc_jis_2004', 'cp932_via_sjis'):
        try:
            if codec == 'cp932_via_sjis':
                # convert JIS to Shift_JIS
                j1, j2 = hi, lo
                s1 = ((j1 + 1) >> 1) + (0x70 if j1 <= 0x5E else 0xB0)
                s2 = j2 + (0x1F if j1 & 1 else 0x7E)
                if j1 & 1 and s2 >= 0x7F:
                    s2 += 1
                ch = bytes([s1, s2]).decode('cp932')
            else:
                ch = b.decode(codec)
        except Exception:
            continue
        if len(ch) == 1 and ord(ch) not in out:
            out.append(ord(ch))
    return out


def jis_class(code):
    row = (code >> 8) - 0x20
    if row in (1, 2):
        return 'symbols'
    if row == 3:
        return 'alnum'
    if row == 4:
        return 'hiragana'
    if row == 5:
        return 'katakana'
    if row in (6, 7):
        return 'greek_cyrillic'
    if row == 8:
        return 'box'
    if 16 <= row <= 47:
        return 'kanji1'
    if 48 <= row <= 84:
        return 'kanji2'
    return 'other'


def ascii_art(pix, x0, y_top, w, h):
    g = to_grid(pix, x0, y_top, w, h)
    return ['.'.join('') + ''.join('#' if v else '.' for v in row) for row in g]
