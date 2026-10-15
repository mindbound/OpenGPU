"""List code points in a range where 18x18ja's inner 16x16 equals Unifont's
16x16 glyph, and which source attribution.tsv gives them.
Usage: python -I -X utf8 unienc.py <18x18ja.bdf> <unifont.hex.gz> <attribution.tsv> <lo-hi hex>
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdflib
_, _, t, _ = bdflib.read_bdf(sys.argv[1])
u = bdflib.read_hex(sys.argv[2])
att = {}
for line in open(sys.argv[3], encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    att[p[0]] = p[3] + '/' + p[2]
lo, hi = (int(x, 16) for x in sys.argv[4].split('-'))
for cp in range(lo, hi + 1):
    if cp in t and cp in u and bdflib.inner16_of_18(t[cp])[0] == u[cp]:
        print('U+%04X %s %s' % (cp, chr(cp), att.get('U+%04X' % cp)))
