"""Compare two 16x16 BDF fonts glyph-by-glyph at the same ENCODING value.

Usage: python -I -X utf8 pairbdf.py <a.bdf> <b.bdf> [rowlo-rowhi]
Optional row range (e.g. 0x30-0x74) restricts to 94x94 rows (high byte).
Prints identical / translation-identical counts and a Hamming histogram.
"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdflib  # noqa: E402


def main():
    _, _, a, _ = bdflib.read_bdf(sys.argv[1])
    _, _, b, _ = bdflib.read_bdf(sys.argv[2])
    lo, hi = 0, 0xFFFF
    if len(sys.argv) > 3:
        x, y = sys.argv[3].split('-')
        lo, hi = int(x, 16), int(y, 16)
    common = [e for e in a if e in b and lo <= (e >> 8) <= hi]
    ident = sum(1 for e in common if a[e] == b[e])
    hist = collections.Counter()
    shifted = 0
    for e in common:
        if a[e] != b[e]:
            d = bdflib.hamming(a[e], b[e])
            hist[min(d // 10 * 10, 150)] += 1
            if bdflib.best_shift_hamming(a[e], b[e])[0] == 0:
                shifted += 1
    print('common', len(common), 'identical', ident, 'translation-identical', shifted,
          'only-in-a', len([e for e in a if e not in b and lo <= (e >> 8) <= hi]),
          'only-in-b', len([e for e in b if e not in a and lo <= (e >> 8) <= hi]))
    print('Hamming histogram of non-identical (bucket of 10 px):', dict(sorted(hist.items())))


if __name__ == '__main__':
    main()
