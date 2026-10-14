"""Compare a BDF font's A-Z/0-9 shapes with unscii's font-arcade.txt, allowing for the transcription's
label offset after 'M' (its U+004E entry draws an O). Data only.
Usage: python -I arcadecheck.py font-arcade.txt font.bdf
"""
import sys


def norm(px):
    if not px:
        return frozenset()
    mx = min(x for x, _ in px)
    my = min(y for _, y in px)
    return frozenset((x - mx, y - my) for x, y in px)


def load_txt(p):
    entries = []  # (label cp, shape) in file order
    cur, buf = None, []
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.rstrip("\n")
            if s.startswith("U+"):
                if cur is not None and buf:
                    entries.append((cur, buf))
                cur, buf = int(s.split()[0][2:], 16), []
                continue
            t = s.strip().split()[0] if s.strip() else ""
            if cur is not None and t and set(t) <= set(".#"):
                buf.append(t)
    if cur is not None and buf:
        entries.append((cur, buf))
    out = []
    for cp, rows in entries:
        px = {(c, r) for r, t in enumerate(rows) for c, ch in enumerate(t) if ch == "#"}
        out.append((cp, norm(px)))
    return out


def load_bdf(p):
    g = {}
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    i = 0
    while i < len(lines):
        if lines[i].startswith("STARTCHAR"):
            enc = bbx = None
            j = i + 1
            while not lines[j].startswith("BITMAP"):
                if lines[j].startswith("ENCODING "):
                    enc = int(lines[j].split()[1])
                if lines[j].startswith("BBX "):
                    bbx = [int(x) for x in lines[j].split()[1:5]]
                j += 1
            j += 1
            px = set()
            r = 0
            while not lines[j].startswith("ENDCHAR"):
                hx = lines[j].strip()
                if hx:
                    v = int(hx, 16)
                    bits = len(hx) * 4
                    for c in range(bbx[0]):
                        if v >> (bits - 1 - c) & 1:
                            px.add((c, r))
                r += 1
                j += 1
            g[enc] = norm(px)
            i = j
        i += 1
    return g


def main():
    tx = load_txt(sys.argv[1])
    bd = load_bdf(sys.argv[2])
    shapes = {}
    for cp, sh in tx:
        shapes.setdefault(sh, []).append(cp)
    targets = [ord(c) for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    hits = []
    for cp in targets:
        if cp not in bd:
            continue
        sh = bd[cp]
        if sh in shapes:
            hits.append("%s(=entry %s)" % (chr(cp), "/".join("U+%04X" % x for x in shapes[sh])))
    print("transcription entries:", len(tx))
    print("font glyphs whose shape equals some transcription entry: %d/%d" % (len(hits), len([c for c in targets if c in bd])))
    print(" ".join(hits))


if __name__ == "__main__":
    main()
