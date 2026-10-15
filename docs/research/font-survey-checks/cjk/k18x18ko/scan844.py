"""Compose Hangul syllables from every johab844 (8-4-4) font in a directory
(Hanterm 3.1.6 convert_3_to_jo844 rule) and compare each with syllables
composed from the 10-6-6 KAIST fonts (convert_3_to_display rule).

Usage: python -I -X utf8 scan844.py <dir-with-8-4-4-bdfs> <label=10-6-6.bdf> [...]
Prints, per 10-6-6 font, the 8-4-4 fonts ranked by identical syllables and
by mean Hamming distance, so the rest of the collection is the baseline.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf  # noqa: E402
import compare_hangul as ch  # noqa: E402

TYPE1_NO = [0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 3, 3, 1, 2, 4, 4, 4, 2, 1, 3, 0]
TYPE1_YES = [5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 7, 7, 7, 6, 6, 7, 7, 7, 6, 6, 7, 5]
TYPE3 = [0, 0, 2, 0, 2, 1, 2, 1, 2, 3, 0, 2, 1, 3, 3, 1, 2, 1, 3, 3, 1, 1]


def compose844(font):
    fbb, gl = font
    res = {}
    miss = 0
    for s in range(11172):
        L, V, T = s // 588, (s // 28) % 21, s % 28
        h1, h2, h3 = L + 1, V + 1, T
        type1 = TYPE1_YES[h2] if h3 else TYPE1_NO[h2]
        type2 = (0 if h1 in (0, 1, 16) else 1) + (2 if h3 else 0)
        type3 = TYPE3[h2]
        idx = [type1 * 20 + h1, type2 * 22 + h2 + 160]
        if h3:
            idx.append(type3 * 28 + h3 + 248)
        rows = [0] * fbb[1]
        for p in idx:
            g = gl.get(p)
            if g is None:
                miss += 1
                continue
            rows = [a | b for a, b in zip(rows, g)]
        res[0xAC00 + s] = bdf.crop(tuple(rows), fbb[0], 0, 0, 16, 16)
    return res, miss


def stats(a, b):
    ident = 0
    tot = 0
    for k in a:
        d = bdf.hamming(a[k], b[k])
        tot += d
        if d == 0:
            ident += 1
    return ident, tot / len(a)


def main():
    d = sys.argv[1]
    refs = []
    for arg in sys.argv[2:]:
        lab, p = arg.split('=', 1)
        c, miss = ch.compose(bdf.read_bdf(p))
        refs.append((lab, c))
    results = {lab: [] for lab, _ in refs}
    n = 0
    for name in sorted(os.listdir(d)):
        if not name.endswith('.bdf'):
            continue
        font = bdf.read_bdf(os.path.join(d, name))
        if font[0] is None or font[0][0] != 16 or font[0][1] != 16:
            continue
        c, miss = compose844(font)
        n += 1
        for lab, rc in refs:
            ident, mh = stats(rc, c)
            results[lab].append((ident, -mh, name, miss))
    print(f'8-4-4 fonts scanned: {n}')
    for lab, rows in results.items():
        rows.sort(reverse=True)
        ids = [r[0] for r in rows]
        print(f'== {lab}: identical syllables per 8-4-4 font: max {ids[0]}, '
              f'median {ids[len(ids)//2]}, fonts with >0: {sum(1 for i in ids if i > 0)}')
        for ident, nmh, name, miss in rows[:6]:
            print(f'   {name:16s} identical {ident:5d}  mean Hamming {-nmh:6.1f}  missing {miss}')
        rows.sort(key=lambda r: r[1], reverse=True)
        print('   lowest mean Hamming:', ', '.join(f'{r[2]} {-r[1]:.1f}' for r in rows[:6]))
        mhs = sorted(-r[1] for r in rows)
        print(f'   median of mean Hamming over all 8-4-4 fonts: {mhs[len(mhs)//2]:.1f}')


if __name__ == '__main__':
    main()
