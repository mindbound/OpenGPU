"""Where do 18x18ko's KS X 1001 symbols, jamo and Hanja come from?

Usage: python -I -X utf8 ksx_areas.py <18x18ko.bdf> <johabshg16.bdf>
        <hanglg16.bdf (Daewoo)> <18x18ja.bdf> <sjkg16.bdf (1991)>

For each KS X 1001 cell (rows 0xA1-0xAC symbols/jamo, 0xCA-0xFD Hanja),
decode to Unicode with Python's euc_kr codec, take 18x18ko's glyph's inner
16x16 at (1,1) and compare with:
  - johabsh graphic area, index (row-0xA1)*94 + (col-0xA1) + 0x211
    (Hanterm convert_3_to_display, f == 0xff)
  - Daewoo hanglg16 (GL encoding (row-0x80)<<8 | (col-0x80))
  - 18x18ja (same code point, full 18x18 bitmap compared with 18x18ko)
  - sjkg16 (1991) at the johabsh index, symbols only (it has 1644 glyphs)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf  # noqa: E402


def main():
    kofbb, ko = bdf.read_bdf(sys.argv[1])
    _, jsh = bdf.read_bdf(sys.argv[2])
    _, dw = bdf.read_bdf(sys.argv[3])
    jafbb, ja = bdf.read_bdf(sys.argv[4])
    _, sjk = bdf.read_bdf(sys.argv[5])
    areas = {'symbols+jamo rows A1-AC': range(0xA1, 0xAD),
             'Hangul jamo row A4 only': range(0xA4, 0xA5),
             'Hanja rows CA-FD': range(0xCA, 0xFE)}
    for name, rows in areas.items():
        n = 0
        c = {'jsh': 0, 'dw': 0, 'ja': 0, 'sjk': 0, 'jsh=dw': 0,
             'only_jsh': 0, 'only_dw': 0, 'neither': 0, 'sjk=jsh': 0}
        for r in rows:
            for col in range(0xA1, 0xFF):
                try:
                    u = bytes([r, col]).decode('euc_kr')
                except UnicodeDecodeError:
                    continue
                if len(u) != 1:
                    continue
                cp = ord(u)
                if cp not in ko:
                    continue
                g = ko[cp]
                if bdf.popcount(g) == 0:
                    continue
                n += 1
                inner = bdf.crop(g, kofbb[0], 1, 1, 16, 16)
                ji = (r - 0xA1) * 94 + (col - 0xA1) + 0x211
                a = jsh.get(ji)
                b = dw.get(((r - 0x80) << 8) | (col - 0x80))
                s = sjk.get(ji)
                mj = a is not None and inner == a
                md = b is not None and inner == b
                if mj:
                    c['jsh'] += 1
                if md:
                    c['dw'] += 1
                if a is not None and b is not None and a == b:
                    c['jsh=dw'] += 1
                if mj and not md:
                    c['only_jsh'] += 1
                if md and not mj:
                    c['only_dw'] += 1
                if not mj and not md:
                    c['neither'] += 1
                if s is not None and a is not None and s == a and bdf.popcount(a) > 0:
                    c['sjk=jsh'] += 1
                if cp in ja and ja[cp] == g:
                    c['ja'] += 1
        print(f'{name}: inked 18x18ko cells {n}; equal to johabsh {c["jsh"]}; '
              f'to Daewoo {c["dw"]}; johabsh==Daewoo {c["jsh=dw"]}; only johabsh {c["only_jsh"]}; '
              f'only Daewoo {c["only_dw"]}; neither {c["neither"]}; '
              f'18x18ko==18x18ja (full cell) {c["ja"]}; sjk1991==johabsh {c["sjk=jsh"]}')


if __name__ == '__main__':
    main()
