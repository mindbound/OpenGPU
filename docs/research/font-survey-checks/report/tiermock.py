"""Tier mock-up: text at native pixels on a fixed W x H surface (own code, MIT with the repo).

Usage: python -I tiermock.py OUT.png SCALE W H SPECFILE
SPECFILE: UTF-8 lines 'FONTPATH<TAB>TEXT'. Lines stack from the top at each font's own
line pitch (ascent + descent, no extra leading); everything is clipped to W x H, as TEXT clips.
A codepoint missing from a line's font is looked up in the FALLBACK font given by a line
'#fallback<TAB>FONTPATH' (cell-top aligned, blue), else drawn as a red bar.
Reads font data only, through the shared fontmeasure reader.
"""
import collections
import importlib.util
import os
import struct
import sys
import zlib

_p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "fontmeasure.py")
_spec = importlib.util.spec_from_file_location("fm", _p)
fm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fm)


def load(path):
    data = fm.opener(path).read()
    base = path[:-3] if path.endswith(".gz") else path
    if os.path.splitext(base)[1].lower() == ".bdf":
        g, p = fm.read_bdf(data)
        asc = int(p.get("FONT_ASCENT", 0) or 0)
        desc = int(p.get("FONT_DESCENT", 0) or 0)
    else:
        g, p = fm.read_hex(data, 16)
        asc, desc = 14, 2
    adv = collections.Counter(g[c].get("dw") for c in range(0x21, 0x7F) if c in g).most_common(1)[0][0]
    return g, asc, desc, adv


def main():
    out, scale, W, H, specfile = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
    cache = {}
    fallback = None
    rows = []
    for line in open(specfile, encoding="utf-8").read().splitlines():
        if "\t" not in line:
            continue
        a, b = line.split("\t", 1)
        if a == "#fallback":
            fallback = b
            continue
        rows.append((a, b))
    for path in {r[0] for r in rows} | ({fallback} if fallback else set()):
        cache[path] = load(path)
    BG, FG, FB, MISS = (16, 18, 28), (232, 230, 210), (120, 170, 255), (210, 60, 60)
    px = [[BG] * W for _ in range(H)]

    def put(x, y, c):
        if 0 <= x < W and 0 <= y < H:
            px[y][x] = c

    def draw(gl, x, top, asc, colour):
        w, h, xo, yo = gl["bbx"]
        for j, row in enumerate(gl.get("bits", [])[:h]):
            v = int(row, 16) if row else 0
            nb = len(row) * 4
            for i in range(w):
                if v >> (nb - 1 - i) & 1:
                    put(x + xo + i, top + asc - yo - h + j, colour)

    y = 0
    for path, text in rows:
        g, asc, desc, adv = cache[path]
        x = 0
        for ch in text:
            cp = ord(ch)
            if cp in g:
                draw(g[cp], x, y, asc, FG)
                x += g[cp].get("dw", adv)
            elif fallback and cp in cache[fallback][0]:
                fg_, fasc, fdesc, fadv = cache[fallback]
                gl = fg_[cp]
                draw(gl, x, y, fasc, FB)
                x += gl.get("dw", fadv)
            else:
                for i in range(max(1, adv - 1)):
                    put(x + i, y + asc - 1, MISS)
                x += adv
        y += asc + desc
    raw = b"".join(b"\x00" + b"".join(bytes(c) * scale for c in row) for row in px for _ in range(scale))

    def chunk(k, d):
        return struct.pack(">I", len(d)) + k + d + struct.pack(">I", zlib.crc32(k + d) & 0xFFFFFFFF)

    with open(out, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W * scale, H * scale, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    print({"out": out, "size": [W * scale, H * scale], "lines": len(rows), "used_height": y})


if __name__ == "__main__":
    main()
