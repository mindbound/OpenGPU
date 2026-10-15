"""Minimal BDF / Unifont-hex reader for glyph comparisons (stdlib only).

Every glyph is returned as a tuple of row integers rendered into the font's
FONTBOUNDINGBOX canvas, leftmost pixel = most significant bit of a W-bit row.
"""
import gzip


def _open(path):
    if path.endswith('.gz'):
        return gzip.open(path, 'rt', encoding='latin-1', newline='')
    return open(path, 'r', encoding='latin-1', newline='')


def read_bdf(path, canvas_from_bbx=True):
    """Return (canvas, props, glyphs, comments).

    canvas = (W, H, xoff, yoff), by default the most common glyph BBX;
    glyphs = {encoding: (rows tuple)} rendered into that canvas (H rows of W
    bits).
    """
    raw = {}
    glyphs = {}
    props = {}
    comments = []
    fbb = None
    with _open(path) as f:
        in_props = False
        cur = None
        for line in f:
            line = line.rstrip('\r\n')
            if cur is None:
                if line.startswith('COMMENT'):
                    comments.append(line[7:].strip())
                elif line.startswith('FONTBOUNDINGBOX'):
                    p = line.split()
                    fbb = tuple(int(x) for x in p[1:5])
                elif line.startswith('STARTPROPERTIES'):
                    in_props = True
                elif line.startswith('ENDPROPERTIES'):
                    in_props = False
                elif in_props:
                    k, _, v = line.partition(' ')
                    props[k] = v.strip()
                elif line.startswith('STARTCHAR'):
                    cur = {'name': line[9:].strip(), 'enc': None, 'bbx': None,
                           'rows': [], 'bitmap': False}
            else:
                if line.startswith('ENCODING'):
                    p = line.split()
                    cur['enc'] = int(p[1]) if len(p) < 3 or int(p[1]) >= 0 else int(p[2])
                elif line.startswith('BBX'):
                    cur['bbx'] = tuple(int(x) for x in line.split()[1:5])
                elif line.startswith('BITMAP'):
                    cur['bitmap'] = True
                elif line.startswith('ENDCHAR'):
                    if cur['enc'] is not None and cur['enc'] >= 0:
                        raw[cur['enc']] = (cur['bbx'], cur['rows'])
                    cur = None
                elif cur['bitmap']:
                    s = line.strip()
                    if s:
                        cur['rows'].append(s)
    # Canvas: the most common glyph BBX (some fonts, e.g. jiskan16, declare a
    # FONTBOUNDINGBOX whose y offset disagrees with every glyph's BBX).
    import collections
    common = collections.Counter(b for b, _ in raw.values()).most_common(1)[0][0]
    canvas = common if canvas_from_bbx else fbb
    for enc, (bbx, rows) in raw.items():
        glyphs[enc] = _render(canvas, bbx, rows)
    return canvas, props, glyphs, comments


def _render(fbb, bbx, rows):
    W, H, fx, fy = fbb
    w, h, x, y = bbx
    canvas = [0] * H
    for i, hx in enumerate(rows[:h]):
        nbits = len(hx) * 4
        v = int(hx, 16) >> (nbits - w) if nbits >= w else int(hx, 16) << (w - nbits)
        # row i of glyph is at canvas row: top of glyph = y + h - 1 (baseline coords)
        # canvas top row corresponds to fy + H - 1
        crow = (fy + H - 1) - (y + h - 1) + i
        if 0 <= crow < H:
            shift = (W - (x - fx) - w)
            if shift >= 0:
                canvas[crow] |= v << shift
            else:
                canvas[crow] |= v >> (-shift)
    return tuple(canvas)


def read_hex(path, wide_only=True):
    """Unifont .hex reader: returns {codepoint: rows tuple} for 16x16 glyphs
    (and 8x16 when wide_only is False, as 16-row tuples of 8-bit values)."""
    out = {}
    with _open(path) as f:
        for line in f:
            line = line.strip()
            if not line or ':' not in line:
                continue
            cp, _, data = line.partition(':')
            n = len(data)
            if n == 64:
                out[int(cp, 16)] = tuple(int(data[i:i + 4], 16) for i in range(0, 64, 4))
            elif n == 32 and not wide_only:
                out[int(cp, 16)] = tuple(int(data[i:i + 2], 16) for i in range(0, 32, 2))
    return out


def inner16_of_18(rows):
    """18x18 canvas -> (inner 16x16 rows, border_is_blank)."""
    border = rows[0] | rows[17] | 0
    blank = rows[0] == 0 and rows[17] == 0
    for r in rows:
        if r & (1 << 17) or r & 1:
            blank = False
    inner = tuple((r >> 1) & 0xFFFF for r in rows[1:17])
    return inner, blank


def popcount(x):
    return bin(x).count('1')


def hamming(a, b):
    return sum(popcount(x ^ y) for x, y in zip(a, b))


def shift(rows, dx, dy, W=16):
    """Translate a WxH glyph by dx (right +) and dy (down +); pixels shifted
    out are lost."""
    H = len(rows)
    mask = (1 << W) - 1
    out = [0] * H
    for i, r in enumerate(rows):
        j = i + dy
        if 0 <= j < H:
            out[j] = ((r >> dx) if dx >= 0 else (r << -dx)) & mask
    return tuple(out)


def best_shift_hamming(a, b, rng=2):
    best = None
    for dx in range(-rng, rng + 1):
        for dy in range(-rng, rng + 1):
            d = hamming(shift(a, dx, dy), b)
            if best is None or d < best[0]:
                best = (d, dx, dy)
    return best
