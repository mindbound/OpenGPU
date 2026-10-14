"""Size of a Unifont-style .hex table packed at 1 bit per pixel, raw and deflated (own code, MIT).

Usage: python -I hexpack.py FILE.hex[.gz] MAXCP
Keeps codepoints below MAXCP (hex), concatenates each glyph's bitmap bytes plus a 3-byte
codepoint and 1-byte width per glyph, and reports raw and zlib level-9 sizes.
"""
import gzip
import sys
import zlib


def main():
    path, maxcp = sys.argv[1], int(sys.argv[2], 16)
    opener = gzip.open if path.endswith(".gz") else open
    idx = bytearray()
    bm = bytearray()
    n = 0
    with opener(path, "rt", encoding="ascii") as f:
        for line in f:
            line = line.strip()
            if ":" not in line:
                continue
            cp, data = line.split(":", 1)
            cp = int(cp, 16)
            if cp >= maxcp:
                continue
            raw = bytes.fromhex(data)
            idx += cp.to_bytes(3, "little") + bytes([len(raw)])
            bm += raw
            n += 1
    blob = bytes(idx + bm)
    print(f"glyphs {n} index {len(idx)} bitmaps {len(bm)} raw {len(blob)} deflate9 {len(zlib.compress(blob, 9))}")


if __name__ == "__main__":
    main()
