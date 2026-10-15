"""Minimal independent BDF / Unifont-hex reader (stdlib only).

Each glyph becomes a frozenset of ink pixels (x, y) in baseline coordinates:
x = column from the glyph origin, y = rows ABOVE the baseline (y=0 is the
row just above the baseline, negative values are descenders).
This avoids depending on FONTBOUNDINGBOX, which is wrong in some fonts.
"""
import gzip


def _open(path):
    if path.endswith('.gz'):
        return gzip.open(path, 'rb')
    return open(path, 'rb')


def read_bdf(path):
    glyphs = {}   # encoding -> frozenset of (x, y)
    meta = {}
    with _open(path) as f:
        lines = f.read().decode('latin-1').splitlines()
    i = 0
    n = len(lines)
    while i < n:
        ln = lines[i]
        if ln.startswith('FONTBOUNDINGBOX'):
            meta['fbb'] = tuple(int(v) for v in ln.split()[1:5])
        if ln.startswith('STARTCHAR'):
            enc = None
            bbx = None
            i += 1
            while not lines[i].startswith('BITMAP'):
                p = lines[i].split()
                if p and p[0] == 'ENCODING':
                    enc = int(p[1])
                elif p and p[0] == 'BBX':
                    bbx = tuple(int(v) for v in p[1:5])
                i += 1
            i += 1
            rows = []
            while not lines[i].startswith('ENDCHAR'):
                s = lines[i].strip()
                if s:
                    rows.append(s)
                i += 1
            w, h, xo, yo = bbx
            ink = set()
            for r, s in enumerate(rows):
                v = int(s, 16)
                nbits = len(s) * 4
                for c in range(w):
                    if v >> (nbits - 1 - c) & 1:
                        # row r counts from the top of the BBX
                        y = yo + h - 1 - r
                        ink.add((xo + c, y))
            if enc is not None and enc >= 0:
                glyphs[enc] = frozenset(ink)
        i += 1
    return glyphs, meta


def read_hex(path, ascent=14):
    """Unifont .hex: 16 rows; 8 or 16 px wide; baseline 2 rows from bottom."""
    glyphs = {}
    with _open(path) as f:
        for raw in f:
            s = raw.decode('ascii').strip()
            if not s or ':' not in s:
                continue
            cp, data = s.split(':', 1)
            cp = int(cp, 16)
            nrows = 16
            w = len(data) * 4 // nrows
            if w not in (8, 16):
                continue
            step = w // 4
            ink = set()
            for r in range(nrows):
                v = int(data[r * step:(r + 1) * step], 16)
                for c in range(w):
                    if v >> (w - 1 - c) & 1:
                        ink.add((c, 13 - r))
            glyphs[cp] = frozenset(ink)
    return glyphs


def norm(ink):
    """Translation-normalised shape plus the offset that was removed."""
    if not ink:
        return frozenset(), None
    mx = min(x for x, _ in ink)
    my = min(y for _, y in ink)
    return frozenset((x - mx, y - my) for x, y in ink), (mx, my)


def gl_to_uni(code, codec, prefix=b''):
    hi, lo = code >> 8, code & 0xFF
    if not (0x21 <= hi <= 0x7E and 0x21 <= lo <= 0x7E):
        return None
    try:
        s = (prefix + bytes([hi | 0x80, lo | 0x80])).decode(codec)
    except UnicodeDecodeError:
        return None
    if len(s) != 1:
        return None
    return ord(s)


def to_unicode(glyphs, codec, prefix=b''):
    out = {}
    unm = 0
    for code, ink in glyphs.items():
        u = gl_to_uni(code, codec, prefix)
        if u is None:
            unm += 1
            continue
        out[u] = ink
    return out, unm
