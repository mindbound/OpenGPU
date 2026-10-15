"""Compare 12x13ja (Unicode) against JIS-encoded 12px fonts.
usage: cmp1.py <12x13ja.bdf> <name=jisfont.bdf> [name=jisfont.bdf ...]
For each JIS font: classify JIS codes, map to Unicode, compare with best shift in -2..2.
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

uni = vbdf.read(sys.argv[1])['glyphs']
for arg in sys.argv[2:]:
    name, path = arg.split('=', 1)
    jf = vbdf.read(path)['glyphs']
    stats = {}
    dxs = {}
    ham = {}
    jac = {}
    unmapped = 0
    for code, g in jf.items():
        if not (0x2121 <= code <= 0x7E7E):
            continue
        c = cls(code)
        us = vbdf.jis_to_uni(code)
        cands = [ord(u) for u in us if ord(u) in uni]
        if not cands:
            unmapped += 1
            continue
        best = None
        for u in cands:
            bm = vbdf.best_match(uni[u].pix, g.pix)
            j = max(vbdf.jaccard(uni[u].pix, vbdf.shift(g.pix, bm[1], bm[2])), 0)
            if best is None or bm[0] < best[0][0]:
                best = (bm, j)
        (d, dx, dy), j = best
        s = stats.setdefault(c, [0, 0])
        s[1] += 1
        if d == 0:
            s[0] += 1
            dxs.setdefault(c, {}).setdefault((dx, dy), 0)
            dxs[c][(dx, dy)] += 1
        ham.setdefault(c, []).append(d)
        jac.setdefault(c, []).append(j)
    print('=== 12x13ja vs', name, path)
    print('unmapped/absent in 12x13ja:', unmapped)
    for c in ['kanji1', 'kanji2', 'hira', 'kata', 'sym', 'alnum', 'greekcyr', 'box', 'other']:
        if c in stats:
            print(f'{c:9s} identical {stats[c][0]:5d}/{stats[c][1]:5d}  median-ham {statistics.median(ham[c]):5.1f}  mean-jacc {statistics.mean(jac[c]):.3f}  shifts {dxs.get(c, {})}')
    k = [h for c in ('kanji1', 'kanji2') for h in ham.get(c, [])]
    kj = [h for c in ('kanji1', 'kanji2') for h in jac.get(c, [])]
    ki = sum(stats.get(c, [0, 0])[0] for c in ('kanji1', 'kanji2'))
    print(f'ALL KANJI identical {ki}/{len(k)} median-ham {statistics.median(k)} mean-jacc {statistics.mean(kj):.3f}')
