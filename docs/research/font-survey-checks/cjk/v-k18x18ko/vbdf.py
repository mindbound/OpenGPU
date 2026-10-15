"""Minimal independent BDF / hex reader (verifier v-k18x18ko). Standard library only.
Glyphs are returned as frozensets of (x, y) pixel coordinates, x rightward from the
glyph origin, y upward from the baseline (y = 0 is the first row above the baseline).
"""
import gzip


def read_bdf(path):
    opener = gzip.open if path.endswith('.gz') else open
    glyphs = {}
    props = {}
    with opener(path, 'rb') as f:
        lines = f.read().decode('latin-1').splitlines()
    i = 0
    n = len(lines)
    enc = None
    bbx = None
    while i < n:
        ln = lines[i]
        if ln.startswith('FONT '):
            props['FONT'] = ln[5:].strip()
        elif ln.startswith('FONTBOUNDINGBOX'):
            props['FONTBOUNDINGBOX'] = tuple(int(v) for v in ln.split()[1:5])
        elif ln.startswith('ENCODING'):
            parts = ln.split()
            enc = int(parts[1])
            if enc == -1 and len(parts) > 2:
                enc = ('nonstd', int(parts[2]))
        elif ln.startswith('BBX'):
            bbx = tuple(int(v) for v in ln.split()[1:5])
        elif ln.startswith('BITMAP'):
            w, h, xo, yo = bbx
            pix = set()
            for r in range(h):
                i += 1
                row = lines[i].strip()
                val = int(row, 16) if row else 0
                nbits = len(row) * 4
                for c in range(w):
                    if val >> (nbits - 1 - c) & 1:
                        pix.add((xo + c, yo + h - 1 - r))
            glyphs[enc] = frozenset(pix)
        i += 1
    return props, glyphs


def read_hex(path, top_y=13):
    """Unifont .hex: 'XXXX:hexbits'. 32 hex digits = 8x16, 64 = 16x16. Row 0 at y=top_y."""
    opener = gzip.open if path.endswith('.gz') else open
    glyphs = {}
    with opener(path, 'rb') as f:
        for raw in f:
            ln = raw.decode('ascii', 'replace').strip()
            if ':' not in ln:
                continue
            cp, bits = ln.split(':', 1)
            cp = int(cp, 16)
            nd = len(bits)
            w = 16 if nd == 64 else 8 if nd == 32 else nd * 4 // 16
            per = w // 4
            pix = set()
            for r in range(16):
                chunk = bits[r * per:(r + 1) * per]
                if not chunk:
                    continue
                val = int(chunk, 16)
                for c in range(w):
                    if val >> (w - 1 - c) & 1:
                        pix.add((c, top_y - r))
            glyphs[cp] = frozenset(pix)
    return glyphs


def shift(p, dx, dy):
    return frozenset((x + dx, y + dy) for (x, y) in p)
