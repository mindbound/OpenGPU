"""Reproduce a 14->12 reduction of k14 and compare with k12 (verifier's own implementation).
usage: reduce.py <k14.bdf> <k12.bdf> [baseline_name=jis12font.bdf ...]
Area-coverage per bdfresize charresize.c (Kagotani 1988/1992): source pixel x covers
[x*nw, (x+1)*nw), dest pixel covers [X*w, (X+1)*w); gray = overlap area; on if gray >= thr.
Also a nearest-neighbour variant.
"""
import sys, os, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf

def cls(code):
    r = code >> 8
    if 0x30 <= r <= 0x4F: return 'kanji1'
    if 0x50 <= r <= 0x74: return 'kanji2'
    if r == 0x24: return 'hira'
    if r == 0x25: return 'kata'
    if r in (0x21, 0x22): return 'sym'
    if r == 0x23: return 'alnum'
    if r in (0x26, 0x27): return 'greekcyr'
    if r == 0x28: return 'box'
    return 'other'

def grid(g):
    w, h, xo, yo = g.bbx
    return [[(g.rows[r] >> (w - 1 - c)) & 1 for c in range(w)] for r in range(h)]

def area_reduce(src, w, h, nw, nh):
    gray = [[0] * nw for _ in range(nh)]
    for y in range(h):
        for x in range(w):
            if not src[y][x]:
                continue
            X, Y, X1, Y1 = x * nw, y * nh, (x + 1) * nw, (y + 1) * nh
            for ny in range(Y // h, -(-Y1 // h)):
                for nx in range(X // w, -(-X1 // w)):
                    ox = min(X1, (nx + 1) * w) - max(X, nx * w)
                    oy = min(Y1, (ny + 1) * h) - max(Y, ny * h)
                    if ox > 0 and oy > 0:
                        gray[ny][nx] += ox * oy
    return gray

def nearest(src, w, h, nw, nh):
    return [[src[(y * h) // nh][(x * w) // nw] for x in range(nw)] for y in range(nh)]

k14 = vbdf.read(sys.argv[1])['glyphs']
k12 = vbdf.read(sys.argv[2])['glyphs']
bl = []
for a in sys.argv[3:]:
    n, p = a.split('=', 1)
    bl.append((n, vbdf.read(p)['glyphs']))

bbx14 = {g.bbx for g in k14.values()}
bbx12 = {g.bbx for g in k12.values()}
print('k14 BBX set', bbx14, 'k12 BBX set', bbx12)
print('codes only in k14', sorted(hex(c) for c in set(k14) - set(k12))[:10], 'only in k12', sorted(hex(c) for c in set(k12) - set(k14))[:10])

grays = {}
for code, g in k14.items():
    if code in k12 and g.bbx == (14, 14, 0, -2) and k12[code].bbx == (12, 12, 0, -1):
        grays[code] = area_reduce(grid(g), 14, 14, 12, 12)
print('compared glyphs', len(grays))

def tobits(gr, thr):
    return [[1 if v >= thr else 0 for v in row] for row in gr]

results = {}
for thr in list(range(50, 110, 2)) + [78, 79]:
    tot = 0
    per = {}
    for code, gr in grays.items():
        out = tobits(gr, thr)
        ok = out == grid(k12[code])
        c = cls(code)
        p = per.setdefault(c, [0, 0]); p[1] += 1
        if ok:
            p[0] += 1; tot += 1
    results[thr] = (tot, per)
for thr in sorted(results):
    print(f'thr>={thr:3d} ({thr/196*100:5.1f}%) identical {results[thr][0]}')
for thr in (78, 79):
    print('detail thr', thr, {c: tuple(v) for c, v in sorted(results[thr][1].items())})
# nearest neighbour
nn = 0
for code in grays:
    if nearest(grid(k14[code]), 14, 14, 12, 12) == grid(k12[code]):
        nn += 1
print('nearest-neighbour identical', nn)

# Baseline: 40% output vs unrelated 12px fonts (best shift), kanji only
def topix(bits):
    # 12x12 cell with BBX 12 12 0 -1 -> baseline coords
    return frozenset((c, 10 - r) for r in range(12) for c in range(12) if bits[r][c])
for n, f in bl:
    ident = 0; hs = []; js = []; tot = 0
    for code, gr in grays.items():
        if cls(code) not in ('kanji1', 'kanji2') or code not in f:
            continue
        p = topix(tobits(gr, 78))
        d, dx, dy = vbdf.best_match(f[code].pix, p)
        tot += 1; hs.append(d); js.append(vbdf.jaccard(f[code].pix, vbdf.shift(p, dx, dy)))
        if d == 0: ident += 1
    print(f'baseline 40% output vs {n}: kanji identical {ident}/{tot} median-ham {statistics.median(hs)} mean-jacc {statistics.mean(js):.3f}')
# Same baseline for real k12 vs those fonts
for n, f in bl:
    ident = 0; hs = []; tot = 0
    for code, g in k12.items():
        if cls(code) not in ('kanji1', 'kanji2') or code not in f:
            continue
        d, dx, dy = vbdf.best_match(f[code].pix, g.pix)
        tot += 1; hs.append(d)
        if d == 0: ident += 1
    print(f'baseline k12 vs {n}: kanji identical {ident}/{tot} median-ham {statistics.median(hs)}')

# Edit shape for kanji1 that differ from 40% output
lost_only = gained_only = both = 0
changed = []
add_under_ink = add_total = 0
jac = []
diff2 = []
for code, gr in grays.items():
    c = cls(code)
    out = tobits(gr, 78)
    real = grid(k12[code])
    if out == real:
        continue
    if c == 'kanji2':
        diff2.append(code)
    if c != 'kanji1':
        continue
    a = {(r, x) for r in range(12) for x in range(12) if out[r][x]}
    b = {(r, x) for r in range(12) for x in range(12) if real[r][x]}
    lost = a - b; gained = b - a
    if lost and not gained: lost_only += 1
    elif gained and not lost: gained_only += 1
    else: both += 1
    changed.append(len(lost) + len(gained))
    jac.append(len(a & b) / len(a | b))
    for (r, x) in gained:
        add_total += 1
        if gr[r][x] > 0:
            add_under_ink += 1
print(f'kanji1 differing: {len(changed)} lost-only {lost_only} gained-only {gained_only} both {both} median-changed {statistics.median(changed)} mean-jacc {statistics.mean(jac):.3f}')
print(f'added pixels with nonzero k14 coverage: {add_under_ink}/{add_total}')
print('kanji2 differing codes:', [hex(c) for c in sorted(diff2)])
