"""Translation-invariant glyph-shape identity between fonts of any cell size (data only).

Usage: python -I shapecmp.py NAME=path[:rows] ... -- NAME=path[:rows] ...
  .bdf (any size), .hex (rows given, default 16; 8-px-wide only), .txt (unscii src style, rows given).
For each candidate/reference pair prints how many printable-ASCII glyphs (U+0021..U+007E) have
exactly the same ink shape after moving the ink bounding box to the origin.
"""
import sys
import gzip


def opentext(p):
    if p.endswith(".gz"):
        return gzip.open(p, "rt", encoding="utf-8", errors="replace")
    return open(p, "r", encoding="utf-8", errors="replace")


def norm(px):
    if not px:
        return frozenset()
    mx = min(x for x, _ in px)
    my = min(y for _, y in px)
    return frozenset((x - mx, y - my) for x, y in px)


def load_bdf(p):
    g = {}
    with opentext(p) as f:
        lines = f.read().splitlines()
    i, n = 0, len(lines)
    while i < n:
        if lines[i].startswith("STARTCHAR"):
            enc = bbx = None
            j = i + 1
            while j < n and not lines[j].startswith("BITMAP"):
                if lines[j].startswith("ENCODING "):
                    enc = int(lines[j].split()[1])
                elif lines[j].startswith("BBX "):
                    bbx = [int(x) for x in lines[j].split()[1:5]]
                j += 1
            j += 1
            px = set()
            r = 0
            while j < n and not lines[j].startswith("ENDCHAR"):
                hx = lines[j].strip()
                if hx and bbx:
                    v = int(hx, 16)
                    bits = len(hx) * 4
                    for c in range(bbx[0]):
                        if v >> (bits - 1 - c) & 1:
                            px.add((c, r))
                r += 1
                j += 1
            if enc is not None and enc >= 0:
                g[enc] = norm(px)
            i = j
        i += 1
    return g


def load_hex(p, rows):
    g = {}
    with opentext(p) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            cp, data = line.split(":", 1)
            try:
                cp = int(cp, 16)
            except ValueError:
                continue
            width = len(data) * 4 // rows
            if width not in (8, 16):
                continue
            bpr = width // 4
            px = set()
            for r in range(rows):
                v = int(data[r * bpr:(r + 1) * bpr], 16)
                for c in range(width):
                    if v >> (width - 1 - c) & 1:
                        px.add((c, r))
            g[cp] = norm(px)
    return g


def load_txt(p, rows):
    g = {}
    cur = None
    buf = []

    def flush():
        if cur is not None and len(buf) == rows:
            px = set()
            for r, t in enumerate(buf):
                for c, ch in enumerate(t):
                    if ch == "#":
                        px.add((c, r))
            g[cur] = norm(px)

    with opentext(p) as f:
        for line in f:
            s = line.rstrip("\n")
            if s.startswith("U+"):
                flush()
                try:
                    cur = int(s.split()[0][2:], 16)
                except ValueError:
                    cur = None
                buf = []
                continue
            t = s.strip().split()[0] if s.strip() else ""
            if cur is not None and t and set(t) <= set(".#"):
                buf.append(t)
            elif not t and buf:
                flush()
                cur = None
                buf = []
    flush()
    return g


def load(spec):
    name, rest = spec.split("=", 1)
    rows = None
    if ":" in rest[2:]:
        idx = rest.rfind(":")
        if rest[idx + 1:].isdigit():
            rows = int(rest[idx + 1:])
            rest = rest[:idx]
    lp = rest.lower()
    if lp.endswith(".bdf") or lp.endswith(".bdf.gz"):
        return name, load_bdf(rest)
    if lp.endswith(".hex") or lp.endswith(".hex.gz"):
        return name, load_hex(rest, rows or 16)
    return name, load_txt(rest, rows or 8)


def main():
    a = sys.argv[1:]
    k = a.index("--")
    refs = [load(s) for s in a[:k]]
    cands = [load(s) for s in a[k + 1:]]
    ascii_cps = range(0x21, 0x7F)
    for cn, cg in cands:
        union = set()
        for rn, rg in refs:
            both = [c for c in ascii_cps if c in cg and c in rg and cg[c]]
            same = [c for c in both if cg[c] == rg[c]]
            union.update(same)
            print("%-12s vs %-10s ASCII same shape %2d/%2d  %s" % (cn, rn, len(same), len(both), "".join(chr(c) for c in same)))
        print("%-12s vs any ref: %d glyphs" % (cn, len(union)))


if __name__ == "__main__":
    main()
