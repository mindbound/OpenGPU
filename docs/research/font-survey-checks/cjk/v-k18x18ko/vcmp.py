"""Verifier re-run of the 18x18ko Hangul vs KAIST Gothic comparison.
Usage: vcmp.py <18x18ko.bdf> <johabshg16.bdf> <johabshm16.bdf> <sjkg16.bdf> <johabsg16.bdf> <unifont.hex.gz> <daewoo_hanglg16.bdf>
"""
import sys, os, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vbdf import read_bdf, read_hex, shift

# Unicode jamo index -> Johab 5-bit codes
L2F = [i + 2 for i in range(19)]
V2M = [3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 18, 19, 20, 21, 22, 23, 26, 27, 28, 29]
T2L = [1] + [t + 1 for t in range(1, 17)] + [t + 2 for t in range(17, 28)]

# Tables transcribed from hanterm-3.1.6 hangul.c (convert_3_to_display and its tables)
fcon_map1 = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 3, 0, 0, 3, 1, 2, 4, 4, 4, 0, 0, 2, 1, 3, 0]
fcon_map2 = [0, 0, 5, 5, 5, 5, 5, 5, 0, 0, 5, 5, 5, 6, 8, 8, 0, 0, 8, 6, 7, 9, 9, 9, 0, 0, 7, 6, 8, 5]
vow_base = [0, 0, 0, 311, 314, 317, 320, 323, 0, 0, 326, 329, 332, 335, 339, 343, 0, 0,
            347, 351, 355, 358, 361, 364, 0, 0, 367, 370, 374, 378]
vow_kind = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0]
lcon_kind = [0, 0, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
lcon_map = [0, 0, 0, 0, 2, 0, 2, 1, 0, 0, 2, 1, 2, 3, 0, 0, 0, 0, 0, 3, 3, 1, 1, 1, 0, 0, 3, 3, 0, 1]
assert len(fcon_map1) == len(fcon_map2) == len(vow_base) == len(vow_kind) == len(lcon_map) == 30
assert len(lcon_kind) == 30


def pieces(f, m, l):
    out = []
    if f != 1:
        out.append(f * 10 + (fcon_map2[m] if l > 1 else fcon_map1[m]) - 19)
    if m != 2:
        ind = vow_base[m]
        if vow_kind[m] == 0:
            ind += lcon_kind[l]
        else:
            ind += (0 if f in (2, 17) else 1) + (2 if l > 1 else 0)
        out.append(ind)
    if l != 1:
        out.append(l * 4 + lcon_map[m] + (397 if l < 18 else 393))
    return out


def compose(font, cp):
    s = cp - 0xAC00
    L, V, T = s // 588, (s % 588) // 28, s % 28
    pix = set()
    for ind in pieces(L2F[L], V2M[V], T2L[T]):
        pix |= font.get(ind, frozenset())
    return frozenset(pix)


def best_shift(a, b, keys, rng=3):
    best = None
    for dx in range(-rng, rng + 1):
        for dy in range(-rng, rng + 1):
            eq = sum(1 for k in keys if shift(b[k], dx, dy) == a[k])
            ham = sum(len(shift(b[k], dx, dy) ^ a[k]) for k in keys)
            cand = (eq, -ham, dx, dy)
            if best is None or cand > best:
                best = cand
    return best[2], best[3]


def compare(name, ko, other, keys):
    sample = keys[::50]
    dx, dy = best_shift(ko, other, sample)
    ident = 0
    hams = []
    for k in keys:
        h = len(shift(other[k], dx, dy) ^ ko[k])
        hams.append(h)
        if h == 0:
            ident += 1
    print('%-34s compared %5d identical %5d  median Hamming %5.1f  best shift (%d,%d)' % (
        name, len(keys), ident, statistics.median(hams), dx, dy))
    return dx, dy


def main():
    kop, ko = read_bdf(sys.argv[1])
    gp, g = read_bdf(sys.argv[2])
    mp, m = read_bdf(sys.argv[3])
    sp, sjk = read_bdf(sys.argv[4])
    sgp, sg = read_bdf(sys.argv[5])
    uni = read_hex(sys.argv[6])
    dwp, dw = read_bdf(sys.argv[7])
    print('18x18ko:', kop['FONT'], kop['FONTBOUNDINGBOX'], len(ko), 'glyphs')
    print('gothic :', gp['FONT'], len(g))
    print('myeong :', mp['FONT'], len(m))
    print('sjk    :', sp['FONT'], len(sjk))
    print('sjk-s  :', sgp['FONT'], len(sg))
    print('daewoo :', dwp['FONT'], len(dw))
    syl = list(range(0xAC00, 0xD7A4))
    present = [c for c in syl if c in ko]
    inked = [c for c in present if ko[c]]
    print('18x18ko syllables present %d inked %d' % (len(present), len(inked)))
    G = {c: compose(g, c) for c in syl}
    M = {c: compose(m, c) for c in syl}
    U = {c: uni.get(c, frozenset()) for c in syl}
    dx, dy = compare('KAIST Gothic (johabshg16) composed', ko, G, syl)
    # ink outside the shifted 16x16 window
    xs = set(range(dx, dx + 16))
    ys = set(range(-2 + dy, 14 + dy))
    outside = sum(1 for c in syl if any(x not in xs or y not in ys for (x, y) in ko[c]))
    print('18x18ko syllables with ink outside the 16x16 window at shift (%d,%d): %d' % (dx, dy, outside))
    compare('KAIST Myeongjo composed (baseline)', ko, M, syl)
    compare('Unifont 18.0.01 (baseline)', ko, U, syl)
    # Daewoo Gothic baseline on the 2,350 KS X 1001 syllables
    ks = []
    D = {}
    for b1 in range(0xB0, 0xC9):
        for b2 in range(0xA1, 0xFF):
            ch = bytes([b1, b2]).decode('euc_kr')
            cp = ord(ch)
            enc = ((b1 - 0x80) << 8) | (b2 - 0x80)
            if 0xAC00 <= cp <= 0xD7A3 and enc in dw:
                ks.append(cp)
                D[cp] = dw[enc]
    print('KS X 1001 syllables found in Daewoo font:', len(ks))
    compare('Daewoo Gothic hanglg16 (baseline)', ko, D, ks)
    Gks = {c: G[c] for c in ks}
    compare('KAIST Gothic composed, KS subset', ko, Gks, ks)
    # how close KAIST Gothic and Daewoo Gothic are to each other (same style)
    ham = [len(G[c] ^ D[c]) for c in ks]
    print('KAIST Gothic vs Daewoo Gothic (no shift) identical %d median Hamming %.1f' % (
        sum(1 for h in ham if h == 0), statistics.median(ham)))
    # Lineage: jamo pieces 1..528
    piece = [k for k in range(1, 529) if isinstance(k, int)]
    common = [k for k in piece if k in g and k in sjk]
    inked_c = [k for k in common if g[k] or sjk[k]]
    print('johabshg16 pieces vs sjkg16: common %d, inked %d, identical inked %d, identical all %d' % (
        len(common), len(inked_c), sum(1 for k in inked_c if g[k] == sjk[k]), sum(1 for k in common if g[k] == sjk[k])))
    inked_m = [k for k in range(1, 529) if k in m and (m[k] or sjk.get(k))]
    print('johabshm16 pieces vs sjkg16 (baseline): inked %d identical %d' % (
        len(inked_m), sum(1 for k in inked_m if m[k] == sjk.get(k))))
    cs = [k for k in sg if k in sjk]
    print('johabsg16 vs sjkg16 whole font: johabsg16 %d glyphs, sjkg16 %d, common %d, identical %d' % (
        len(sg), len(sjk), len(cs), sum(1 for k in cs if sg[k] == sjk[k])))
    # KS graphic area of johabsh vs sjk
    ga = [k for k in g if isinstance(k, int) and k >= 529 and k in sjk]
    print('johabshg16 graphic area (>=529) present in sjk: %d, identical %d' % (
        len(ga), sum(1 for k in ga if g[k] == sjk[k])))


if __name__ == '__main__':
    main()
