# How k12 differs from k14 scaled 12/14 by area coverage: per-class threshold scan and edit shapes.
# usage: python -I -X utf8 editstats.py k12.bdf k14.bdf
import sys, os, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf, to_grid, from_grid, bdfresize_grid, jis_class

_, K12 = read_bdf(sys.argv[1])
_, K14 = read_bdf(sys.argv[2])

def scaled(code, tn, td=100):
    g = K14[code]
    w, h, xo, yo = g['bbx']
    src = to_grid(g['pix'], xo, yo + h - 1, w, h)
    grid, gray, lvl = bdfresize_grid(src, 12, 12, tn, td)
    return from_grid(grid, 0, 10), gray, lvl

classes = {}
for code in K12:
    classes.setdefault(jis_class(code), []).append(code)

print('identical count per class and threshold (percent of 196 = 14*14 area units)')
ths = [30, 35, 38, 39, 40, 41, 42, 45, 50]
print('%-15s %5s ' % ('class', 'n') + ' '.join('%6d%%' % t for t in ths))
cache = {}
for cls in ['symbols', 'alnum', 'hiragana', 'katakana', 'greek_cyrillic', 'box', 'kanji1', 'kanji2']:
    codes = [c for c in classes.get(cls, []) if K12[c]['pix']]
    row = []
    for t in ths:
        n = 0
        for c in codes:
            key = (c, t)
            if key not in cache:
                cache[key] = scaled(c, t)[0]
            n += cache[key] == K12[c]['pix']
        row.append(n)
    print('%-15s %5d ' % (cls, len(codes)) + ' '.join('%7d' % v for v in row))

# integer graylevel at 40%: 196*40//100 = 78; check the threshold boundary: is any k12 pixel set with gray in (0, 78)?
print('\ngraylevel at 40%:', 196 * 40 // 100)

# Edit shapes for kanji1 vs 40% output
add = []; rem = []; both = 0; only_add = 0; only_rem = 0; ham = []
for c in classes['kanji1']:
    a = K12[c]['pix']; s = cache[(c, 40)]
    plus = a - s; minus = s - a
    if not plus and not minus:
        continue
    add.append(len(plus)); rem.append(len(minus)); ham.append(len(plus) + len(minus))
    if plus and minus:
        both += 1
    elif plus:
        only_add += 1
    else:
        only_rem += 1
print('kanji1 differing from 40%% output: %d; only pixels added %d, only removed %d, both %d' % (len(ham), only_add, only_rem, both))
print('  added px: mean %.2f median %s; removed px: mean %.2f median %s; changed px median %s, p90 %s, max %s' % (
    statistics.mean(add), statistics.median(add), statistics.mean(rem), statistics.median(rem),
    statistics.median(ham), sorted(ham)[int(0.9 * len(ham))], max(ham)))

# Are k12's added pixels in places where the scaled gray score was non-zero (i.e. under k14 ink)?
gray_hits = 0; gray_zero = 0
for c in classes['kanji1']:
    a = K12[c]['pix']; s, gray, lvl = scaled(c, 40)
    for (x, y) in a - s:
        r = 10 - y
        if gray[r][x] > 0:
            gray_hits += 1
        else:
            gray_zero += 1
print('kanji1 added pixels lying under some k14 ink (gray > 0): %d; under no k14 ink: %d' % (gray_hits, gray_zero))

# kanji2 differences listed
print('\nkanji2 codes differing from 40% output:')
for c in classes['kanji2']:
    a = K12[c]['pix']; s = cache[(c, 40)]
    if a != s:
        print('  %04X %s +%d -%d' % (c, bytes([(c >> 8) | 0x80, (c & 0xFF) | 0x80]).decode('euc_jp'), len(a - s), len(s - a)))
