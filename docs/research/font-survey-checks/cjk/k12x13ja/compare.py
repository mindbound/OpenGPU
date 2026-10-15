# Glyph-identity check for 12x13ja's JIS X 0208 glyphs.
# usage: python -I -X utf8 compare.py 12x13ja.bdf k12.bdf k14.bdf mplus_j12r.bdf shnmk12.bdf
import sys, os, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import (read_bdf, to_grid, from_grid, best_match, bdfresize_grid, nearest_grid,
                    jis_to_unicode_candidates, jis_class, hamming, jaccard)

p12x13, pk12, pk14, pmplus, pshnm = sys.argv[1:6]
_, U = read_bdf(p12x13)
pk12p, K12 = read_bdf(pk12)
pk14p, K14 = read_bdf(pk14)
_, MP = read_bdf(pmplus)
_, SH = read_bdf(pshnm)

print('glyph counts: 12x13ja', len(U), 'k12', len(K12), 'k14', len(K14), 'mplus_j12r', len(MP), 'shnmk12', len(SH))

# JIS -> Unicode map restricted to code points present in 12x13ja
jis2u = {}
unmapped = []
for code in sorted(K12):
    cands = jis_to_unicode_candidates(code)
    hit = [u for u in cands if u in U]
    if hit:
        jis2u[code] = hit[0]
    else:
        unmapped.append((code, cands))
print('k12 codes mapped to a 12x13ja code point:', len(jis2u), '; unmapped:', len(unmapped))
for code, c in unmapped[:40]:
    print('   unmapped JIS %04X cands %s inked-in-k12=%d' % (code, ['U+%04X' % u for u in c], len(K12[code]['pix'])))

CLASSES = ['symbols', 'alnum', 'hiragana', 'katakana', 'greek_cyrillic', 'box', 'kanji1', 'kanji2']
BUCKETS = [(0, 0), (1, 2), (3, 5), (6, 10), (11, 20), (21, 999)]


def summarise(name, pairs, note=''):
    """pairs: list of (cls, ham, jac)"""
    print('\n== %s %s' % (name, note))
    groups = {}
    for cls, h, j in pairs:
        g = 'kanji' if cls.startswith('kanji') else 'non-kanji'
        groups.setdefault(g, []).append((h, j))
        groups.setdefault(cls, []).append((h, j))
    for g in ['kanji', 'non-kanji'] + CLASSES:
        v = groups.get(g)
        if not v:
            continue
        hs = [h for h, _ in v]
        js = [j for _, j in v]
        bucket = []
        for lo, hi in BUCKETS:
            bucket.append(sum(1 for h in hs if lo <= h <= hi))
        print('  %-15s n=%5d  identical=%5d (%5.1f%%)  ham buckets 0/1-2/3-5/6-10/11-20/>20 = %s  '
              'median ham=%s  mean jaccard=%.3f  median jaccard=%.3f' % (
                  g, len(v), bucket[0], 100.0 * bucket[0] / len(v), '/'.join(str(b) for b in bucket),
                  statistics.median(hs), statistics.mean(js), statistics.median(js)))


def nonempty(*pixsets):
    return all(len(p) > 0 for p in pixsets)

WIN = (-2, -1, 0, 1, 2)

# 1. 12x13ja vs k12, fixed alignment and best alignment
fixed = []
best = []
shift_hist = {}
for code, u in jis2u.items():
    a = U[u]['pix']
    b = K12[code]['pix']
    if not nonempty(a, b):
        continue
    cls = jis_class(code)
    h = hamming(a, b)
    fixed.append((cls, h, jaccard(a, b)))
    bm = best_match(a, b, WIN, WIN)
    best.append((cls, bm[0], bm[1]))
    if bm[0] == 0:
        shift_hist[(bm[2], bm[3])] = shift_hist.get((bm[2], bm[3]), 0) + 1
summarise('12x13ja vs k12', fixed, '(fixed alignment: same baseline, same origin)')
summarise('12x13ja vs k12', best, '(best shift in dx,dy -2..2)')
print('  shifts of identical glyphs (dx,dy): count', sorted(shift_hist.items(), key=lambda t: -t[1]))
# with dx in {-1,0} only (Kuhn's adjustmargin moved glyphs 1 px left)
adj = []
for code, u in jis2u.items():
    a = U[u]['pix']; b = K12[code]['pix']
    if not nonempty(a, b):
        continue
    bm = best_match(a, b, (-1, 0), (0,))
    adj.append((jis_class(code), bm[0], bm[1]))
summarise('12x13ja vs k12', adj, '(dx in {-1,0}, dy 0: allowing only the adjustmargin move)')


def jis_pair(name, A, B, note='', window=WIN, codes=None):
    pairs = []
    codes = codes if codes is not None else sorted(set(A) & set(B))
    for code in codes:
        if code not in A or code not in B:
            continue
        a, b = A[code]['pix'], B[code]['pix']
        if not nonempty(a, b):
            continue
        bm = best_match(a, b, window, window)
        pairs.append((jis_class(code), bm[0], bm[1]))
    summarise(name, pairs, note)
    return pairs

# 12x13ja re-keyed by JIS for baseline comparisons
UJ = {code: U[u] for code, u in jis2u.items()}

jis_pair('12x13ja vs M+ j12r (baseline, unrelated 12 px)', UJ, MP, '(best shift -2..2)')
jis_pair('12x13ja vs Shinonome 12 (baseline, unrelated 12 px)', UJ, SH, '(best shift -2..2)')
jis_pair('k12 vs M+ j12r (baseline)', K12, MP, '(best shift -2..2)')
jis_pair('k12 vs Shinonome 12 (baseline)', K12, SH, '(best shift -2..2)')
jis_pair('M+ j12r vs Shinonome 12 (baseline pair of two unrelated 12 px fonts)', MP, SH, '(best shift -2..2)')

# 2. k14 scaled to 12 by bdfresize's area method, at various thresholds, vs k12
def scale_font(F, method, tn=None, td=None):
    out = {}
    for code, g in F.items():
        w, h, xo, yo = g['bbx']
        src = to_grid(g['pix'], xo, yo + h - 1, w, h)
        nw, nh = w * 12 // 14, h * 12 // 14
        if method == 'area':
            grid, _, _ = bdfresize_grid(src, nw, nh, tn, td)
        else:
            grid = nearest_grid(src, nw, nh)
        # k14 BBX 14 14 0 -2 -> k12 BBX 12 12 0 -1 (bdfresize: YRESIZE(-2) = -1 with truncation toward zero)
        nyo = int(yo * 12 / 14)
        nxo = int(xo * 12 / 14)
        out[code] = {'bbx': (nw, nh, nxo, nyo), 'pix': from_grid(grid, nxo, nyo + nh - 1)}
    return out

print('\n== k14 BBX set:', sorted({g['bbx'] for g in K14.values()}), ' k12 BBX set:', sorted({g['bbx'] for g in K12.values()}))

results = {}
for label, method, tn, td in [('area 20%', 'area', 20, 100), ('area 25% (bdfresize default -b 4)', 'area', 25, 100),
                              ('area 30%', 'area', 30, 100), ('area 35%', 'area', 35, 100),
                              ('area 40%', 'area', 40, 100), ('area 45%', 'area', 45, 100),
                              ('area 50%', 'area', 50, 100), ('area 60%', 'area', 60, 100),
                              ('nearest', 'nearest', None, None)]:
    S = scale_font(K14, method, tn, td)
    common = sorted(set(S) & set(K12))
    ident = 0; n = 0; hs = []
    pairs = []
    for code in common:
        a, b = K12[code]['pix'], S[code]['pix']
        if not nonempty(a, b):
            continue
        n += 1
        h = hamming(a, b)
        hs.append(h)
        ident += h == 0
        pairs.append((jis_class(code), h, jaccard(a, b)))
    results[label] = (S, pairs)
    print('k12 vs k14->12 %-34s fixed alignment: n=%d identical=%d (%.1f%%) median ham=%s' % (
        label, n, ident, 100.0 * ident / n, statistics.median(hs)))

S40, pairs40 = results['area 40%']
summarise('k12 vs k14 area-scaled 12/14 at 40%', pairs40, '(fixed alignment)')
summarise('k12 vs k14 nearest-scaled 12/14', results['nearest'][1], '(fixed alignment)')
jis_pair('k12 vs k14 area 40%', K12, S40, '(best shift -2..2)')
jis_pair('k14 area 40% vs M+ j12r (baseline)', S40, MP, '(best shift -2..2)')
jis_pair('k14 area 40% vs Shinonome 12 (baseline)', S40, SH, '(best shift -2..2)')
jis_pair('12x13ja vs k14 area 40%', UJ, S40, '(best shift -2..2)')
jis_pair('12x13ja vs k14 nearest', UJ, results['nearest'][0], '(best shift -2..2)')

# Which k12 glyphs differ from the 40% output: count differing pixels and list a sample
diff_codes = [(code, hamming(K12[code]['pix'], S40[code]['pix'])) for code in sorted(K12)
              if code in S40 and hamming(K12[code]['pix'], S40[code]['pix']) > 0]
print('\nk12 glyphs differing from k14@40%%: %d; by class:' % len(diff_codes))
cc = {}
for code, h in diff_codes:
    cc[jis_class(code)] = cc.get(jis_class(code), 0) + 1
print('  ', cc)

# 12x13ja wide glyphs (DWIDTH 12) in CJK blocks that have no k12 source
mapped_u = set(jis2u.values())
extra = [u for u, g in U.items() if (0x3000 <= u <= 0x9FFF or 0xFF00 <= u <= 0xFFEF) and u not in mapped_u and g['pix']
         and not (0xAC00 <= u <= 0xD7A3)]
print('\n12x13ja inked code points in U+3000-9FFF/FF00-FFEF not mapped from k12:', len(extra))
print('  ', ' '.join('U+%04X' % u for u in extra[:120]))
uro = [u for u in U if 0x4E00 <= u <= 0x9FFF and U[u]['pix']]
print('12x13ja inked URO:', len(uro), '; of which mapped from k12 JIS codes:', len([u for u in uro if u in mapped_u]))


def show(title, pix, x0, ytop, w, h):
    print('  ' + title)
    for row in to_grid(pix, x0, ytop, w, h):
        print('    ' + ''.join('#' if v else '.' for v in row))

for code in (0x3021, 0x3B7A, 0x4A38, 0x2422, 0x2526):  # 亜, 字?, sample, hiragana, katakana
    u = jis2u.get(code)
    print('\nJIS %04X -> U+%04X %s' % (code, u, chr(u)))
    show('k14 (14x14)', K14[code]['pix'], 0, 11, 14, 14)
    show('k14 -> 12 area 40%', S40[code]['pix'], 0, 10, 12, 12)
    show('k12', K12[code]['pix'], 0, 10, 12, 12)
    show('12x13ja', U[u]['pix'], 0, 10, 12, 13)
    show('M+ j12r', MP[code]['pix'], 0, 10, 12, 13)
    show('Shinonome 12', SH[code]['pix'], 0, 9, 12, 12)
