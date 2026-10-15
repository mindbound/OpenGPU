"""Crude text extraction from a Word 97 .doc: print runs of UTF-16LE text
(CJK, kana, ASCII and common punctuation) of at least N characters.

Usage: python -I -X utf8 doctext.py <file.doc> [minlen]
"""
import sys


def ok(c):
    o = ord(c)
    return (0x20 <= o < 0x7F or 0x3000 <= o <= 0x30FF or 0x4E00 <= o <= 0x9FFF
            or 0xFF00 <= o <= 0xFFEF or o in (0x0D, 0x0A, 0x2010, 0x2015, 0x2018,
                                              0x2019, 0x201C, 0x201D, 0x2026, 0x25CB, 0x2460,
                                              0x2461, 0x2462, 0x2463, 0x2464))


def main():
    data = open(sys.argv[1], 'rb').read()
    minlen = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    for start in (0, 1):
        run = []
        for i in range(start, len(data) - 1, 2):
            c = chr(data[i] | (data[i + 1] << 8))
            if ok(c):
                run.append(c)
            else:
                if len(run) >= minlen and any(ord(x) > 0x3000 for x in run):
                    print(''.join(run).replace('\r', '\n'))
                run = []
        if len(run) >= minlen:
            print(''.join(run))


if __name__ == '__main__':
    main()
