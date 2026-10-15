"""Minimal independent BDF reader (verifier). Standard library only.

read(path) -> dict with 'props' (header lines) and 'glyphs': {encoding: Glyph}
Glyph: name, enc, dwidth, bbx (w,h,xoff,yoff), rows (list of ints, MSB-left, width w),
       pix: frozenset of (x, y) in baseline coordinates (y up, baseline y=0).
"""

class Glyph:
    __slots__ = ('name', 'enc', 'dwidth', 'bbx', 'rows', 'pix')


def read(path):
    with open(path, 'rb') as f:
        lines = f.read().decode('latin-1').splitlines()
    header = []
    glyphs = {}
    i = 0
    n = len(lines)
    while i < n:
        ln = lines[i]
        if ln.startswith('STARTCHAR'):
            g = Glyph()
            g.name = ln[9:].strip()
            g.enc = None
            g.dwidth = None
            g.bbx = None
            i += 1
            while not lines[i].startswith('BITMAP'):
                t = lines[i].split()
                if t and t[0] == 'ENCODING':
                    g.enc = int(t[1])
                elif t and t[0] == 'DWIDTH':
                    g.dwidth = (int(t[1]), int(t[2]))
                elif t and t[0] == 'BBX':
                    g.bbx = tuple(int(v) for v in t[1:5])
                i += 1
            i += 1
            w, h, xo, yo = g.bbx
            rows = []
            while not lines[i].startswith('ENDCHAR'):
                s = lines[i].strip()
                if s:
                    v = int(s, 16)
                    nbits = len(s) * 4
                    rows.append(v >> (nbits - w) if nbits >= w else v << (w - nbits))
                i += 1
            g.rows = rows
            pix = set()
            for r, v in enumerate(rows):
                y = yo + h - 1 - r
                for c in range(w):
                    if v >> (w - 1 - c) & 1:
                        pix.add((xo + c, y))
            g.pix = frozenset(pix)
            if g.enc is not None and g.enc >= 0:
                glyphs[g.enc] = g
        else:
            if not glyphs:
                header.append(ln)
        i += 1
    return {'header': header, 'glyphs': glyphs}


def shift(p, dx, dy):
    return frozenset((x + dx, y + dy) for x, y in p)


def _sjis(code):
    j1, j2 = code >> 8, code & 0xFF
    s1 = ((j1 + 1) >> 1) + (0x70 if j1 <= 0x5E else 0xB0)
    if j1 & 1:
        s2 = j2 + (0x1F if j2 <= 0x5F else 0x20)
    else:
        s2 = j2 + 0x7E
    return bytes([s1, s2])


def jis_to_uni(code):
    """JIS X 0208 row/cell code (0x2121..) -> candidate Unicode chars
    (EUC-JP and EUC-JIS-2004 on EUC bytes; CP932 on proper Shift_JIS bytes)."""
    eb = bytes([(code >> 8) | 0x80, (code & 0xFF) | 0x80])
    out = []
    for b, c in ((eb, 'euc_jp'), (eb, 'euc_jis_2004'), (_sjis(code), 'cp932')):
        try:
            s = b.decode(c)
            if len(s) == 1 and s not in out:
                out.append(s)
        except Exception:
            pass
    return out


def jaccard(a, b):
    u = len(a | b)
    return 1.0 if u == 0 else len(a & b) / u


def best_match(a, b, rng=2):
    """Return (hamming, dx, dy) minimising symmetric difference of a and shift(b)."""
    best = None
    for dx in range(-rng, rng + 1):
        for dy in range(-rng, rng + 1):
            d = len(a ^ shift(b, dx, dy))
            if best is None or d < best[0] or (d == best[0] and abs(dx) + abs(dy) < abs(best[1]) + abs(best[2])):
                best = (d, dx, dy)
    return best
