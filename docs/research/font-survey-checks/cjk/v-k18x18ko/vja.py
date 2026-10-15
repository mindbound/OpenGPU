"""Verifier spot-check: 18x18ja Hangul vs composed KAIST Gothic and Daewoo Gothic.
Usage: vja.py <18x18ja.bdf> <johabshg16.bdf> <hanglg16.bdf>
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vbdf import read_bdf, shift
from vcmp import compose


def main():
    _, ja = read_bdf(sys.argv[1])
    _, g = read_bdf(sys.argv[2])
    _, dw = read_bdf(sys.argv[3])
    syl = [c for c in range(0xAC00, 0xD7A4) if c in ja and ja[c]]
    print('18x18ja inked syllables', len(syl))
    G = {c: compose(g, c) for c in syl}
    best = max(((sum(1 for c in syl if shift(G[c], dx, dy) == ja[c]), dx, dy)
                for dx in range(-3, 4) for dy in range(-3, 4)))
    print('equal to composed KAIST Gothic (best shift): %d at (%d,%d)' % best)
    D = {}
    for c in syl:
        b = chr(c).encode('euc_kr')
        enc = ((b[0] - 0x80) << 8) | (b[1] - 0x80)
        if enc in dw:
            D[c] = dw[enc]
    best = max(((sum(1 for c in D if shift(D[c], dx, dy) == ja[c]), dx, dy)
                for dx in range(-3, 4) for dy in range(-3, 4)))
    print('equal to Daewoo Gothic (best shift): %d of %d at (%d,%d)' % (best[0], len(D), best[1], best[2]))


if __name__ == '__main__':
    main()
