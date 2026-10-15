"""Shift-aware attribution of each 18x18ja glyph (inner 16x16) to candidate
16x16 sources.

Usage: python -I -X utf8 attrib18ja.py key=path ...  (same keys as compare18ja)
Optional: outdir=<dir> writes attribution.tsv there.
"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdflib  # noqa: E402
from compare18ja import gl_to_unicode, block  # noqa: E402

SPEC = [('j90_16', 'euc_jp'), ('jiskan16', 'euc_jp'), ('jksp16', 'jisx0212'),
        ('gb16fs', 'gb2312'), ('hanglg16', 'euc_kr'), ('hanglm16', 'euc_kr'),
        ('gb16st', 'gb2312')]


def load(paths):
    srcs = {}
    for key, codec in SPEC:
        if key in paths:
            _, _, g, _ = bdflib.read_bdf(paths[key])
            m = {}
            for enc, rows in g.items():
                u = gl_to_unicode(enc, codec)
                if u is not None:
                    m[u] = rows
            srcs[key] = m
    return srcs


def main():
    paths = dict(a.split('=', 1) for a in sys.argv[1:])
    _, _, t, _ = bdflib.read_bdf(paths['t18ja'])
    inner = {cp: bdflib.inner16_of_18(r)[0] for cp, r in t.items()}
    srcs = load(paths)
    order = [k for k, _ in SPEC if k in srcs]
    sets = {k: {} for k in order}
    for k in order:
        for cp, rows in srcs[k].items():
            sets[k].setdefault(rows, cp)

    res = {}
    shifts = collections.defaultdict(collections.Counter)
    for cp, tr in inner.items():
        r = None
        # 1. exact at same code point (first in order)
        for k in order:
            if srcs[k].get(cp) == tr:
                r = ('exact', k, 0, 0, cp)
                break
        # 2. translation-identical at same code point
        if r is None:
            for k in order:
                s = srcs[k].get(cp)
                if s is not None and any(s):
                    d, dx, dy = bdflib.best_shift_hamming(s, tr)
                    if d == 0:
                        r = ('shift', k, dx, dy, cp)
                        break
        # 3. exact or shifted anywhere in source (other code point)
        if r is None and any(tr):
            for k in order:
                for dx in range(-2, 3):
                    for dy in range(-2, 3):
                        st = bdflib.shift(tr, -dx, -dy)
                        if st in sets[k] and bdflib.shift(st, dx, dy) == tr:
                            r = ('elsewhere', k, dx, dy, sets[k][st])
                            break
                    if r:
                        break
                if r:
                    break
        if r is None:
            r = ('none', '-', 0, 0, None) if any(tr) else ('blank', '-', 0, 0, None)
        res[cp] = r
        if r[0] == 'shift':
            shifts[(block(cp), r[1])][(r[2], r[3])] += 1

    agg = collections.defaultdict(collections.Counter)
    for cp, r in res.items():
        agg[block(cp)][(r[0], r[1])] += 1
    for b in sorted(agg, key=lambda x: -sum(agg[x].values())):
        print('%-28s total %d' % (b, sum(agg[b].values())))
        for (how, k), n in agg[b].most_common():
            print('      %-10s %-10s %6d' % (how, k, n))
    print()
    print('Translation vectors (dx right+, dy down+) applied to the source glyph:')
    for key, c in sorted(shifts.items()):
        print('  ', key, dict(c.most_common()))
    tot = collections.Counter((r[0], r[1]) for r in res.values())
    print()
    print('ALL GLYPHS:', sum(tot.values()))
    for k, n in tot.most_common():
        print('   ', k, n)
    if 'outdir' in paths:
        with open(os.path.join(paths['outdir'], 'attribution.tsv'), 'w', encoding='utf-8') as f:
            f.write('cp\tblock\thow\tsource\tdx\tdy\tsource_cp\n')
            for cp in sorted(res):
                how, k, dx, dy, scp = res[cp]
                f.write('U+%04X\t%s\t%s\t%s\t%d\t%d\t%s\n' % (cp, block(cp), how, k, dx, dy,
                                                         ('U+%04X' % scp) if scp is not None else ''))


if __name__ == '__main__':
    main()
