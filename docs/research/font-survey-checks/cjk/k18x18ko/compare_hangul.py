"""Compare 18x18ko's Hangul syllables with syllables composed from Hanterm
johab (10-6-6 / "10/3 or 4/4") jamo-piece fonts, and with baselines.

Usage:
  python -I -X utf8 compare_hangul.py <18x18ko.bdf> <unifont.hex[.gz]>
         <uni-johabg16.bdf.gz> <label=johabfont.bdf> [<label=johabfont.bdf> ...]

The composition follows Hanterm 3.1.6 hangul.c convert_3_to_display()
(tables fcon_map1/2, vow_base, vow_kind, lcon_kind, lcon_map), which is the
same as Jungshik Shin's johab2ucs2 as fixed by Paul Hardy in 2008.
Pieces are OR-ed together in a 16x16 cell.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf  # noqa: E402

# Hanterm tables, indexed by johab codes: f 2..20, m 3..29, l 1..29
FCON_MAP1 = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 3, 0, 0,
             3, 1, 2, 4, 4, 4, 0, 0, 2, 1, 3, 0]
FCON_MAP2 = [0, 0, 5, 5, 5, 5, 5, 5, 0, 0, 5, 5, 5, 6, 8, 8, 0, 0,
             8, 6, 7, 9, 9, 9, 0, 0, 7, 6, 8, 5]
VOW_BASE = [0, 0, 0, 311, 314, 317, 320, 323, 0, 0, 326, 329, 332, 335,
            339, 343, 0, 0, 347, 351, 355, 358, 361, 364, 0, 0, 367, 370,
            374, 378]
VOW_KIND = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0,
            1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0]
LCON_KIND = [0, 0, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
             0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
LCON_MAP = [0, 0, 0, 0, 2, 0, 2, 1, 0, 0, 2, 1, 2, 3, 0, 0, 0, 0,
            0, 3, 3, 1, 1, 1, 0, 0, 3, 3, 0, 1]
VCODES = [3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 18, 19, 20, 21, 22, 23,
          26, 27, 28, 29]
TCODES = [1] + list(range(2, 18)) + list(range(19, 30))


def pieces(f, m, l):
    """Hanterm convert_3_to_display() for a modern syllable."""
    out = []
    out.append(f * 10 + (FCON_MAP2[m] if l > 1 else FCON_MAP1[m]) - 19)
    ind = VOW_BASE[m]
    if VOW_KIND[m] == 0:
        ind += LCON_KIND[l]
    else:
        ind += (0 if f in (2, 17) else 1) + (2 if l > 1 else 0)
    out.append(ind)
    if l != 1:
        out.append(l * 4 + LCON_MAP[m] + (397 if l < 18 else 393))
    return out


def compose(font):
    fbb, gl = font
    fw, fh = fbb[0], fbb[1]
    res = {}
    missing = 0
    for s in range(11172):
        L, V, T = s // 588, (s // 28) % 21, s % 28
        f, m, l = L + 2, VCODES[V], TCODES[T]
        rows = [0] * fh
        for p in pieces(f, m, l):
            g = gl.get(p)
            if g is None:
                missing += 1
                continue
            rows = [a | b for a, b in zip(rows, g)]
        res[0xAC00 + s] = bdf.crop(tuple(rows), fw, 0, 0, 16, 16) if fw >= 16 else tuple(rows)
    return res, missing


def compare(a, b, label):
    keys = sorted(set(a) & set(b))
    ident = 0
    near1 = 0
    near4 = 0
    hd = []
    for k in keys:
        d = bdf.hamming(a[k], b[k])
        hd.append(d)
        if d == 0:
            ident += 1
        if d <= 1:
            near1 += 1
        if d <= 4:
            near4 += 1
    hd.sort()
    med = hd[len(hd) // 2] if hd else None
    print(f"{label}: compared {len(keys)}; identical {ident}; "
          f"<=1 px diff {near1}; <=4 px diff {near4}; median Hamming {med}")
    return ident


def main():
    ko_path, hex_path, unijohab_path = sys.argv[1:4]
    johabs = []
    for arg in sys.argv[4:]:
        lab, p = arg.split('=', 1)
        johabs.append((lab, p))

    fbb, ko = bdf.read_bdf(ko_path)
    print('18x18ko FONTBOUNDINGBOX', fbb, 'glyphs', len(ko))
    syl = {cp: ko[cp] for cp in range(0xAC00, 0xD7A4) if cp in ko}
    inked = sum(1 for g in syl.values() if bdf.popcount(g) > 0)
    print(f'18x18ko U+AC00-D7A3: present {len(syl)}; inked {inked}')
    # any ink outside the inner 16x16 (rows 1..16, cols 1..16)?
    fw = fbb[0]
    border = 0
    for g in syl.values():
        inner_mask_rows = []
        tot = bdf.popcount(g)
        best = 0
        for dy in range(0, 3):
            for dx in range(0, 3):
                c = bdf.popcount(bdf.crop(g, fw, dx, dy, 16, 16))
                best = max(best, c)
        if best != tot:
            border += 1
    print(f'18x18ko syllables with ink outside every 16x16 window: {border}')

    composed = {}
    for lab, p in johabs:
        font = bdf.read_bdf(p)
        print(f'{lab}: FONTBOUNDINGBOX {font[0]}, glyphs {len(font[1])}')
        c, miss = compose(font)
        print(f'{lab}: composed 11172, missing pieces {miss}')
        composed[lab] = c

    # choose the padding offset that maximises identity for each johab font
    for lab, c in composed.items():
        best = None
        for dy in range(0, 3):
            for dx in range(0, 3):
                inner = {k: bdf.crop(v, fw, dx, dy, 16, 16) for k, v in syl.items()}
                n = sum(1 for k in inner if k in c and inner[k] == c[k])
                if best is None or n > best[0]:
                    best = (n, dx, dy)
        print(f'{lab}: best offset dx={best[1]} dy={best[2]} identical {best[0]}')

    # fixed offset (1,1), "padded with one pixel on all sides"
    inner = {k: bdf.crop(v, fw, 1, 1, 16, 16) for k, v in syl.items()}
    print('--- 18x18ko inner 16x16 at (1,1) against:')
    for lab, c in composed.items():
        compare(inner, c, f'  composed {lab}')

    uh = bdf.read_hex(hex_path)
    uni = {cp: uh[cp][1] for cp in range(0xAC00, 0xD7A4) if cp in uh and uh[cp][0] == 16}
    print(f'unifont baseline: {len(uni)} 16-wide syllables')
    compare(inner, uni, '  unifont (baseline, unrelated design)')
    for lab, c in composed.items():
        compare(c, uni, f'baseline: composed {lab} vs unifont')

    ufbb, uj = bdf.read_bdf(unijohab_path)
    ujs = {cp: bdf.crop(uj[cp], ufbb[0], 0, 0, 16, 16) for cp in range(0xAC00, 0xD7A4) if cp in uj}
    print(f'uni-johabg16 (Hardy johab2ucs2 output): {len(ujs)} syllables, fbb {ufbb}')
    for lab, c in composed.items():
        compare(c, ujs, f'control: composed {lab} vs uni-johabg16')
    compare(inner, ujs, '  18x18ko inner vs uni-johabg16')

    # where do 18x18ko and composed Gothic differ?
    for lab, c in composed.items():
        diffs = [k for k in sorted(inner) if k in c and inner[k] != c[k]]
        if diffs and len(diffs) < 11172:
            print(f'{lab}: {len(diffs)} differing syllables; first 20:',
                  ' '.join(f'U+{k:04X}' for k in diffs[:20]))


if __name__ == '__main__':
    main()
