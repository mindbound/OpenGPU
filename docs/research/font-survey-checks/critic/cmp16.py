"""Compare 8x16 glyph bitmaps between fonts (data only, never executes inputs).

Usage: python -I cmp16.py REF=path [REF=path ...] -- CAND=path [CAND=path ...]
Formats by extension: .hex (Unifont hex, 8 or 16 wide, 16 rows), .bdf, .txt (unscii src style: 'U+XXXX' line then rows of . and #).
For every (candidate, reference) pair, prints for printable ASCII U+0021..U+007E and for Latin-1 letters U+00C0..U+00FF:
  exact  = glyphs pixel-identical within a 8x16 cell (after placing BDF glyphs by baseline into the cell),
  shift  = identical up to a translation of the ink bounding box,
  iou    = mean best-shift IoU of ink.
"""
import sys
import gzip


def opentext(p):
    if p.endswith(".gz"):
        return gzip.open(p, "rt", encoding="utf-8", errors="replace")
    return open(p, "r", encoding="utf-8", errors="replace")


def load_hex(p, rows=16):
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
            nbytes = len(data) // 2
            width = nbytes * 8 // rows
            if width != 8:
                continue
            bm = []
            for r in range(rows):
                byte = int(data[r * 2:(r + 1) * 2], 16)
                bm.append(byte)
            g[cp] = bm
    return g


def load_txt(p):
    g = {}
    cur = None
    rows = []
    with opentext(p) as f:
        for line in f:
            s = line.rstrip("\n")
            if s.startswith("U+"):
                if cur is not None and len(rows) == 16:
                    g[cur] = rows
                head = s.split()[0]
                try:
                    cur = int(head[2:], 16)
                except ValueError:
                    cur = None
                rows = []
                continue
            if cur is None:
                continue
            t = s.strip().split()[0] if s.strip() else ""
            if t and set(t) <= set(".#") and len(t) == 8:
                v = 0
                for ch in t:
                    v = (v << 1) | (1 if ch == "#" else 0)
                rows.append(v)
            elif not t:
                if len(rows) == 16:
                    g[cur] = rows
                    cur = None
                    rows = []
    if cur is not None and len(rows) == 16:
        g[cur] = rows
    return g


def load_bdf(p, cell_w=8, cell_h=16):
    g = {}
    ascent = descent = None
    with opentext(p) as f:
        lines = f.read().splitlines()
    for ln in lines:
        if ln.startswith("FONT_ASCENT "):
            ascent = int(ln.split()[1])
        elif ln.startswith("FONT_DESCENT "):
            descent = int(ln.split()[1])
        elif ln.startswith("CHARS "):
            break
    i = 0
    n = len(lines)
    while i < n:
        ln = lines[i]
        if ln.startswith("STARTCHAR"):
            enc = None
            bbx = None
            j = i + 1
            while j < n and not lines[j].startswith("BITMAP"):
                if lines[j].startswith("ENCODING "):
                    enc = int(lines[j].split()[1])
                elif lines[j].startswith("BBX "):
                    bbx = [int(x) for x in lines[j].split()[1:5]]
                j += 1
            j += 1
            data = []
            while j < n and not lines[j].startswith("ENDCHAR"):
                data.append(lines[j].strip())
                j += 1
            i = j
            if enc is None or enc < 0 or bbx is None:
                i += 1
                continue
            w, h, xo, yo = bbx
            rows = [0] * cell_h
            top = ascent - (yo + h)  # row index of first bitmap row in the cell
            for r, hx in enumerate(data):
                if not hx:
                    continue
                val = int(hx, 16)
                bits = len(hx) * 4
                for c in range(w):
                    if val >> (bits - 1 - c) & 1:
                        x = xo + c
                        y = top + r
                        if 0 <= x < cell_w and 0 <= y < cell_h:
                            rows[y] |= 1 << (cell_w - 1 - x)
            g[enc] = rows
        i += 1
    return g


def load(p):
    lp = p.lower()
    if lp.endswith(".hex") or lp.endswith(".hex.gz"):
        return load_hex(p)
    if lp.endswith(".bdf") or lp.endswith(".bdf.gz"):
        return load_bdf(p)
    return load_txt(p)


def pixels(rows):
    s = set()
    for y, v in enumerate(rows):
        for x in range(8):
            if v >> (7 - x) & 1:
                s.add((x, y))
    return s


def norm(s):
    if not s:
        return frozenset()
    mx = min(x for x, _ in s)
    my = min(y for _, y in s)
    return frozenset((x - mx, y - my) for x, y in s)


def best_iou(a, b):
    if not a and not b:
        return 1.0
    best = 0.0
    for dx in range(-3, 4):
        for dy in range(-4, 5):
            sb = {(x + dx, y + dy) for x, y in b}
            inter = len(a & sb)
            uni = len(a | sb)
            if uni:
                best = max(best, inter / uni)
    return best


SETS = {
    "ascii": list(range(0x21, 0x7F)),
    "latin1letters": [c for c in range(0xC0, 0x100) if c not in (0xD7, 0xF7)],
}


def compare(cand, ref):
    out = {}
    for name, cps in SETS.items():
        exact = shift = both = 0
        ious = []
        ids = []
        for cp in cps:
            if cp not in cand or cp not in ref:
                continue
            a = pixels(cand[cp])
            b = pixels(ref[cp])
            if not a and not b:
                continue
            both += 1
            if a == b:
                exact += 1
                ids.append(cp)
            if norm(a) == norm(b):
                shift += 1
            ious.append(best_iou(a, b))
        out[name] = {
            "both": both,
            "exact": exact,
            "shift": shift,
            "iou": round(sum(ious) / len(ious), 3) if ious else None,
            "exact_cps": "".join(chr(c) for c in ids),
        }
    return out


def main():
    args = sys.argv[1:]
    k = args.index("--")
    refs = [a.split("=", 1) for a in args[:k]]
    cands = [a.split("=", 1) for a in args[k + 1:]]
    rf = {n: load(p) for n, p in refs}
    cf = {n: load(p) for n, p in cands}
    for n, g in list(rf.items()) + list(cf.items()):
        print("# loaded %s: %d glyphs (8-wide)" % (n, len(g)))
    for cn, cg in cf.items():
        for rn, rg in rf.items():
            if cn == rn:
                continue
            r = compare(cg, rg)
            a = r["ascii"]
            l = r["latin1letters"]
            print("%-14s vs %-12s ASCII exact %2d/%2d shift %2d iou %s | Latin1-letters exact %2d/%2d shift %2d iou %s" % (
                cn, rn, a["exact"], a["both"], a["shift"], a["iou"], l["exact"], l["both"], l["shift"], l["iou"]))
            if a["exact"]:
                print("    ascii identical: %s" % a["exact_cps"].encode("ascii", "backslashreplace").decode())


if __name__ == "__main__":
    main()
