"""Find glyphs a candidate shares (same translation-normalised ink shape) with a possible donor font,
over all common code points, grouped by Unicode block range (data only).
Usage: python -I borrowcheck.py CANDIDATE.bdf DONOR.bdf|DONOR.hex[.gz]:rows [DONOR2 ...]
"""
import sys
import gzip

BLOCKS = [
    (0x0000, 0x007F, "ASCII"), (0x0080, 0x00FF, "Latin-1"), (0x0100, 0x024F, "Latin Ext-A/B"),
    (0x0250, 0x036F, "IPA/Modifiers/Combining"), (0x0370, 0x03FF, "Greek"), (0x0400, 0x052F, "Cyrillic"),
    (0x0530, 0x1FFF, "Other scripts"), (0x2000, 0x206F, "Gen Punct"), (0x2070, 0x218F, "Super/Currency/Letterlike/Number"),
    (0x2190, 0x21FF, "Arrows"), (0x2200, 0x22FF, "Math"), (0x2300, 0x23FF, "Misc Technical"),
    (0x2400, 0x24FF, "Control/OCR/Enclosed"), (0x2500, 0x259F, "Box+Blocks"), (0x25A0, 0x25FF, "Geometric"),
    (0x2600, 0x27BF, "Misc Symbols/Dingbats"), (0x27C0, 0x27FF, "Math misc"), (0x2800, 0x28FF, "Braille"),
    (0x2900, 0x2E7F, "Arrows/Math/Supp"), (0x2E80, 0x9FFF, "CJK"), (0xA000, 0xDFFF, "Yi..Hangul"),
    (0xE000, 0xF8FF, "PUA"), (0xF900, 0xFFFF, "Compat/Halfwidth/Specials"), (0x10000, 0x10FFFF, "Supplementary"),
]


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
            bpr = width // 4
            px = set()
            for r in range(rows):
                v = int(data[r * bpr:(r + 1) * bpr], 16)
                for c in range(width):
                    if v >> (width - 1 - c) & 1:
                        px.add((c, r))
            g[cp] = norm(px)
    return g


def load(spec):
    rows = None
    if ":" in spec[2:] and spec.rsplit(":", 1)[1].isdigit():
        spec, rows = spec.rsplit(":", 1)
        rows = int(rows)
    if spec.lower().endswith((".bdf", ".bdf.gz")):
        return load_bdf(spec)
    return load_hex(spec, rows or 16)


def block(cp):
    for a, b, name in BLOCKS:
        if a <= cp <= b:
            return name
    return "?"


def main():
    cand = load(sys.argv[1])
    for d in sys.argv[2:]:
        donor = load(d)
        common = [c for c in cand if c in donor and len(cand[c]) >= 4]
        same = [c for c in common if cand[c] == donor[c]]
        stats = {}
        for c in common:
            b = block(c)
            s = stats.setdefault(b, [0, 0])
            s[1] += 1
            if cand[c] == donor[c]:
                s[0] += 1
        print("== %s vs %s: %d/%d common non-trivial glyphs share a shape" % (
            sys.argv[1].replace("\\", "/").split("/")[-1], d.replace("\\", "/").split("/")[-1], len(same), len(common)))
        for b, (s, t) in sorted(stats.items(), key=lambda kv: -kv[1][0]):
            if s:
                print("   %-34s %5d / %5d" % (b, s, t))


if __name__ == "__main__":
    main()
