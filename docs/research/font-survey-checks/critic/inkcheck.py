"""Encoded vs inked coverage of the OC351 working set and a few blocks (data only).

Usage: python -I inkcheck.py FILE[:rows] ...   (.bdf, .hex[.gz] with rows, default 16)
A glyph counts as inked if its bitmap has at least one set pixel. U+0020 and U+00A0 (spaces) count as
inked when encoded, because they are blank by design.
"""
import sys
import gzip

SETS = {
    "ascii": list(range(0x20, 0x7F)),
    "latin1": list(range(0xA0, 0x100)),
    "latinExtA": list(range(0x100, 0x180)),
    "greekLetters": [c for c in range(0x391, 0x3CA) if c != 0x3A2],
    "cyrillic": list(range(0x400, 0x460)),
    "boxDrawing": list(range(0x2500, 0x2580)),
    "blocks": list(range(0x2580, 0x25A0)),
    "braille": list(range(0x2800, 0x2900)),
}
SETS["oc351"] = SETS["ascii"] + SETS["latin1"] + list(range(0x2500, 0x25A0))
BLANK_OK = {0x20, 0xA0, 0x2800}


def opentext(p):
    if p.endswith(".gz"):
        return gzip.open(p, "rt", encoding="utf-8", errors="replace")
    return open(p, "r", encoding="utf-8", errors="replace")


def load_bdf(p):
    g = {}
    enc = None
    inbm = False
    ink = False
    with opentext(p) as f:
        for line in f:
            if line.startswith("ENCODING "):
                enc = int(line.split()[1])
            elif line.startswith("BITMAP"):
                inbm, ink = True, False
            elif line.startswith("ENDCHAR"):
                if enc is not None and enc >= 0:
                    g[enc] = ink
                inbm, enc = False, None
            elif inbm:
                s = line.strip()
                if s and int(s, 16):
                    ink = True
    return g


def load_hex(p):
    g = {}
    with opentext(p) as f:
        for line in f:
            line = line.strip()
            if ":" not in line or line.startswith("#"):
                continue
            cp, data = line.split(":", 1)
            try:
                g[int(cp, 16)] = int(data, 16) != 0
            except ValueError:
                pass
    return g


def main():
    for spec in sys.argv[1:]:
        p = spec
        if p.lower().endswith(".bdf") or p.lower().endswith(".bdf.gz"):
            g = load_bdf(p)
        else:
            g = load_hex(p)
        parts = []
        blanks_oc = []
        for name, cps in SETS.items():
            enc = sum(1 for c in cps if c in g)
            ink = sum(1 for c in cps if c in g and (g[c] or c in BLANK_OK))
            parts.append("%s %d/%d enc, %d inked" % (name, enc, len(cps), ink))
            if name == "oc351":
                blanks_oc = [c for c in cps if c in g and not g[c] and c not in BLANK_OK]
        total = len(g)
        blank_total = sum(1 for c, v in g.items() if not v and c not in BLANK_OK)
        print("%s: %d glyphs, %d blank (excl. spaces)" % (p.replace("\\", "/").split("/")[-1], total, blank_total))
        print("   " + "; ".join(parts))
        if blanks_oc:
            print("   blank OC351 code points: " + " ".join("U+%04X" % c for c in blanks_oc[:40]) + (" ..." if len(blanks_oc) > 40 else ""))


if __name__ == "__main__":
    main()
