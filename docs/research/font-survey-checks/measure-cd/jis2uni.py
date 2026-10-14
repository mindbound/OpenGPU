"""usage: python -I jis2uni.py IN.bdf OUT.bdf [euc_jp|euc_kr|gb2312] -- rewrite ENCODING of a 94x94 double-byte BDF (JIS X 0208 / KS X 1001 / GB 2312 row-cell codes) to Unicode via Python's codecs."""
import sys
src, dst = sys.argv[1], sys.argv[2]; codec = sys.argv[3] if len(sys.argv) > 3 else 'euc_jp'
out = []; bad = 0; n = 0
for line in open(src, encoding='latin-1').read().splitlines():
    if line.startswith('ENCODING '):
        v = int(line.split()[1]); n += 1
        try:
            ch = bytes([(v >> 8) | 0x80, (v & 0xFF) | 0x80]).decode(codec)
            line = 'ENCODING %d' % ord(ch) if len(ch) == 1 else 'ENCODING -1'
        except Exception:
            bad += 1; line = 'ENCODING -1'
    out.append(line)
open(dst, 'w', encoding='latin-1').write('\n'.join(out) + '\n')
print({'glyphs': n, 'unmapped': bad})
