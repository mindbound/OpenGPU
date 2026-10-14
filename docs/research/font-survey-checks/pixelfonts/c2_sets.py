# Critic: coverage, subset relations, 3d rule, 8x16 fit, distinct sets, atlas. python -I c2_sets.py <bdfdir> <font.hex>
import sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from c1_bdf import load_all, ink

fonts = load_all(sys.argv[1])
hexpath = sys.argv[2]

def bitmap(g):
    s, _ = ink(g); return frozenset(s)

# --- coverage
sets = collections.defaultdict(list)
for n, f in fonts.items(): sets[frozenset(f['glyphs'])].append(n)
ext = next(s for s in sets if len(s) == 145)
asc = set(range(0x20, 0x7f))
print('ext non-ASCII:', ' '.join('%04X' % c for c in sorted(ext - asc)))
for s, ns in sets.items(): print(len(s), len(ns), 'e.g.', ns[0], 'extra vs ASCII:', ['%04X' % c for c in sorted(s - asc)][:5] if len(s) < 100 else '')
sub351 = set(range(0x20, 0x7f)) | set(range(0xa0, 0x100)) | set(range(0x2500, 0x25a0))
print('subset size', len(sub351), 'ext covers', len(ext & sub351), 'latin1 part', len(ext & set(range(0xa0, 0x100))), 'box/block', len(ext & set(range(0x2500, 0x25a0))))
print('ext outside subset:', ' '.join('%04X' % c for c in sorted(ext - sub351)))
print('latin1 missing:', ' '.join('%04X' % c for c in sorted(set(range(0xa0, 0x100)) - ext)))

# font.hex narrow/wide check for ext set
narrow = set(); wide = set()
with open(hexpath, encoding='ascii') as fh:
    for ln in fh:
        ln = ln.strip()
        if not ln or ':' not in ln: continue
        cp, h = ln.split(':', 1)
        (narrow if len(h) == 32 else wide).add(int(cp, 16))
print('font.hex narrow', len(narrow), 'wide', len(wide), 'ext all narrow in font.hex', ext <= narrow, 'subset all narrow', sub351 <= narrow, 'FFFD narrow', 0xFFFD in narrow)

# --- English vs ext identity
def key(n):
    return n.replace('extended_latin_', '')
diff_pairs = 0; pairs = 0; glyphs_cmp = 0
for n, f in fonts.items():
    if 'extended_latin' in n:
        e = fonts.get(key(n))
        if e is None: continue
        pairs += 1
        for cp, g in e['glyphs'].items():
            g2 = f['glyphs'][cp]; glyphs_cmp += 1
            if bitmap(g) != bitmap(g2) or g['dw'] != g2['dw']: diff_pairs += 1
print('english/ext pairs', pairs, 'glyphs compared', glyphs_cmp, 'differences', diff_pairs)
print('english with no ext twin:', [n for n in fonts if 'extended' not in n and n.replace('pixelfont_', 'pixelfont_extended_latin_', 1) not in fonts and ('pixelfont_3d_condensed_extended_latin_' + n.split('pixelfont_3d_condensed_')[-1]) not in fonts])

# --- 3d_condensed vs 3d
cnt = collections.Counter(); bmpdiff = 0; asc_desc = []
for n, f in fonts.items():
    if '_3d_condensed_' in n:
        b = fonts[n.replace('_3d_condensed_', '_3d_')]
        for cp, g in f['glyphs'].items():
            g2 = b['glyphs'][cp]
            if bitmap(g) != bitmap(g2): bmpdiff += 1
            cnt[g['dw'][0] - g2['dw'][0]] += 1
        asc_desc.append((n, f['props']['FONT_ASCENT'], f['props']['FONT_DESCENT'], b['props']['FONT_ASCENT'], b['props']['FONT_DESCENT']))
print('3d_condensed vs 3d: bitmap diffs', bmpdiff, 'advance deltas', dict(cnt))
odd = [a for a in asc_desc if not (a[1] == a[3] and a[2] == 0 and a[4] == 1)]
print('3d_cond asc/desc that do not follow (same ascent, descent 1->0):', odd)

# --- 3d vs base rule
def dil(s):
    return {(x + dx, y + dy) for (x, y) in s for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
res = collections.Counter(); offs = collections.Counter(); filled = 0; fails = []
for n, f in fonts.items():
    if '_3d_' in n and 'condensed' not in n:
        bn = n.replace('_3d_', '_')
        base = fonts[bn]
        for cp, g in f['glyphs'].items():
            if cp == 32: continue
            T = bitmap(g); B0 = bitmap(base['glyphs'][cp])
            ok = False
            for ox in range(0, 3):
                for oy in range(0, 4):
                    B = {(x + ox, y + oy) for x, y in B0}
                    D = dil(B)
                    R = (D | {(x + 1, y - 1) for x, y in D}) - B
                    if R == T: ok = True; offs[(ox, oy)] += 1; break
                    # counter fill variant: allow T == R plus enclosed holes
                    if R <= T and not (T & B):
                        extra = T - R
                        # extra pixels must be enclosed (not reachable from outside the bbox w/o crossing R)
                        xs = [x for x, y in T | B]; ys = [y for x, y in T | B]
                        x0, x1, y0, y1 = min(xs) - 1, max(xs) + 1, min(ys) - 1, max(ys) + 1
                        seen = set(); st = [(x0, y0)]
                        while st:
                            p = st.pop()
                            if p in seen or p in R or p in B: continue
                            if not (x0 <= p[0] <= x1 and y0 <= p[1] <= y1): continue
                            seen.add(p); x, y = p; st += [(x+1, y), (x-1, y), (x, y+1), (x, y-1)]
                        if extra and not (extra & seen):
                            ok = True; offs[(ox, oy, 'fill')] += 1; filled += 1; break
                if ok: break
            res[ok] += 1
            if not ok: fails.append((n, '%04X' % cp))
print('3d rule (dilation+shadow(+1,-1) minus base at offset): matched', res[True], 'unmatched', res[False], 'counter-filled', filled)
print('offsets used (x, y-up):', dict(offs))
print('unmatched sample', fails[:10])

# body cleared check
inter = 0; tot = 0
for n, f in fonts.items():
    if '_3d_' in n and 'condensed' not in n:
        base = fonts[n.replace('_3d_', '_')]
