"""Do 18x18ja's (and 12x13ja's) Hangul syllables or KS X 1001 symbols come
from the KAIST/sjk johab Gothic lineage?
Usage: python -I -X utf8 ja_hangul.py <18x18ja.bdf> <18x18ko.bdf> <johabshg16.bdf> <hanglg16.bdf>"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf, compare_hangul as ch
jafbb, ja = bdf.read_bdf(sys.argv[1]); kofbb, ko = bdf.read_bdf(sys.argv[2])
jfont = bdf.read_bdf(sys.argv[3]); _, dw = bdf.read_bdf(sys.argv[4])
comp, _ = ch.compose(jfont)
syl = [cp for cp in range(0xAC00, 0xD7A4) if cp in ja and bdf.popcount(ja[cp]) > 0]
best = None
for dy in range(3):
    for dx in range(3):
        n = sum(1 for cp in syl if bdf.crop(ja[cp], jafbb[0], dx, dy, 16, 16) == comp[cp])
        if best is None or n > best[0]: best = (n, dx, dy)
same_ko = sum(1 for cp in syl if ja[cp] == ko.get(cp))
# Daewoo wansung Hangul: KS X 1001 rows B0-C8
dwm = 0; tot = 0
for r in range(0xB0, 0xC9):
    for c in range(0xA1, 0xFF):
        try: cp = ord(bytes([r, c]).decode('euc_kr'))
        except UnicodeDecodeError: continue
        if cp in ja and bdf.popcount(ja[cp]) > 0:
            tot += 1
            g = dw.get(((r-0x80) << 8) | (c-0x80))
            if g is not None and bdf.crop(ja[cp], jafbb[0], 1, 1, 16, 16) == g: dwm += 1
print(f'18x18ja inked syllables {len(syl)}; equal to composed KAIST Gothic (best offset dx={best[1]} dy={best[2]}): {best[0]}; '
      f'equal to 18x18ko full cell: {same_ko}; KS X 1001 Hangul cells {tot}, equal to Daewoo Gothic inner(1,1): {dwm}')
