"""Atlas and jar cost of candidate glyph tables (own code, MIT with the repo).

Usage: python -I atlascost.py LABEL=PATH[@HEXHEIGHT] ...
For each font: glyph count; inked glyphs; R8 atlas bytes at 1 B per texel with each glyph
in its advance x line-height box (28 §7.5's layout); the same packed at 1 bit per texel;
and the same two figures for OC's 351-codepoint working set only.
Reads BDF or Unifont .hex, gzipped or not. Parses text only; executes nothing.
"""
import gzip
import sys

OC351 = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100)) | set(range(0x2500, 0x25A0))


def opentext(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="latin-1")
    return open(path, encoding="latin-1")


def read_bdf(path):
    glyphs = {}
    asc = desc = 0
    fbb_h = 0
    with opentext(path) as f:
        enc = None
        dw = 0
        bbx = (0, 0)
        inbm = False
        ink = False
        for line in f:
            t = line.split()
            if not t:
                continue
            k = t[0]
            if k == "FONT_ASCENT":
                asc = int(t[1])
            elif k == "FONT_DESCENT":
                desc = int(t[1])
            elif k == "FONTBOUNDINGBOX":
                fbb_h = int(t[2])
            elif k == "ENCODING":
                enc = int(t[1])
            elif k == "DWIDTH":
                dw = int(t[1])
            elif k == "BBX":
                bbx = (int(t[1]), int(t[2]))
            elif k == "BITMAP":
                inbm = True
                ink = False
            elif k == "ENDCHAR":
                if enc is not None and enc >= 0:
                    glyphs[enc] = (dw, bbx, ink)
                enc = None
                inbm = False
            elif inbm:
                try:
                    if int(k, 16):
                        ink = True
                except ValueError:
                    pass
    line_h = asc + desc if asc + desc > 0 else fbb_h
    return glyphs, line_h


def read_hex(path, height):
    glyphs = {}
    with opentext(path) as f:
        for line in f:
            line = line.strip()
            if not line or ":" not in line:
                continue
            cp, data = line.split(":", 1)
            w = len(data) * 4 // height
            glyphs[int(cp, 16)] = (w, (w, height), int(data, 16) != 0)
    return glyphs, height


def cost(glyphs, line_h, keys):
    n = r8 = 0
    for cp in keys:
        if cp not in glyphs:
            continue
        dw, (bw, bh), _ = glyphs[cp]
        w = max(dw, bw)
        n += 1
        r8 += w * line_h
    return n, r8, (r8 + 7) // 8


def main():
    print("label\tglyphs\tinked\tline\tR8_all\t1bpp_all\toc351_n\tR8_oc351\t1bpp_oc351")
    for arg in sys.argv[1:]:
        label, spec = arg.split("=", 1)
        if "@" in spec:
            path, h = spec.rsplit("@", 1)
            glyphs, line_h = read_hex(path, int(h))
        else:
            glyphs, line_h = read_bdf(spec)
        inked = sum(1 for g in glyphs.values() if g[2])
        n, r8, b1 = cost(glyphs, line_h, sorted(glyphs))
        on, or8, ob1 = cost(glyphs, line_h, sorted(OC351))
        print(f"{label}\t{n}\t{inked}\t{line_h}\t{r8}\t{b1}\t{on}\t{or8}\t{ob1}")


if __name__ == "__main__":
    main()
