"""Verifier: KS X 1001 symbol rows (A1-AC) and Hanja rows (CA-FD) of 18x18ko vs johabsh graphic area and Daewoo.
Usage: vksx.py <18x18ko.bdf> <johabshg16.bdf> <hanglg16.bdf> <18x18ja.bdf>
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vbdf import read_bdf, shift


def cells(rows):
    for b1 in rows:
        for b2 in range(0xA1, 0xFF):
            try:
                ch = bytes([b1, b2]).decode('euc_kr')
            except UnicodeDecodeError:
                continue
            if len(ch) != 1:
                continue
            yield b1, b2, ord(ch)


def best(ko, other, pairs):
    res = None
    for dx in range(-3, 4):
        for dy in range(-3, 4):
            eq = sum(1 for cp, k in pairs if shift(other[k], dx, dy) == ko[cp])
            if res is None or eq > res[0]:
                res = (eq, dx, dy)
    return res


def main():
    _, ko = read_bdf(sys.argv[1])
    _, g = read_bdf(sys.argv[2])
    _, dw = read_bdf(sys.argv[3])
    _, ja = read_bdf(sys.argv[4])
    for label, rows in (('symbols+jamo A1-AC', range(0xA1, 0xAD)), ('Hanja CA-FD', range(0xCA, 0xFE))):
        cl = [(b1, b2, cp) for b1, b2, cp in cells(rows) if cp in ko and ko[cp]]
        gp = [(cp, (b1 - 0xA1) * 94 + (b2 - 0xA1) + 0x211) for b1, b2, cp in cl if ((b1 - 0xA1) * 94 + (b2 - 0xA1) + 0x211) in g]
        dp = [(cp, ((b1 - 0x80) << 8) | (b2 - 0x80)) for b1, b2, cp in cl if (((b1 - 0x80) << 8) | (b2 - 0x80)) in dw]
        jp = [(cp, cp) for b1, b2, cp in cl if cp in ja]
        eg, gx, gy = best(ko, g, gp)
        ed, ddx, ddy = best(ko, dw, dp)
        ej = sum(1 for cp, k in jp if ja[k] == ko[cp])
        gset = {cp for cp, k in gp if shift(g[k], gx, gy) == ko[cp]}
        dset = {cp for cp, k in dp if shift(dw[k], ddx, ddy) == ko[cp]}
        print('%s: inked 18x18ko cells %d; = johabsh %d (shift %d,%d); = Daewoo %d (shift %d,%d); both %d; only johabsh %d; only Daewoo %d; = 18x18ja %d (of %d present)' % (
            label, len(cl), eg, gx, gy, ed, ddx, ddy, len(gset & dset), len(gset - dset), len(dset - gset), ej, len(jp)))
        if label.startswith('symbols'):
            neither = [hex(cp) for b1, b2, cp in cl if cp not in gset and cp not in dset]
            print('  neither:', neither)
            jamo = [cp for cp in gset if 0x3131 <= cp <= 0x318E]
            print('  compatibility jamo equal to johabsh:', len(jamo))


if __name__ == '__main__':
    main()
