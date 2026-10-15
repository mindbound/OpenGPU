"""Break down by Unicode block the 18x18ko KS X 1001 symbol cells (rows
A1-AC) whose inner 16x16 equals the johabsh graphic-area glyph.
Usage: python -I -X utf8 ksx_blocks.py <18x18ko.bdf> <johabshg16.bdf>"""
import os, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf
BLOCKS = [(0x0000,0x007F,'ASCII'),(0x0080,0x00FF,'Latin-1'),(0x0100,0x024F,'Latin Ext'),
          (0x02B0,0x02FF,'Spacing modifiers'),(0x0370,0x03FF,'Greek'),(0x0400,0x04FF,'Cyrillic'),
          (0x2000,0x206F,'General punct'),(0x2070,0x209F,'Super/subscripts'),(0x20A0,0x20CF,'Currency'),
          (0x2100,0x214F,'Letterlike'),(0x2150,0x218F,'Number forms'),(0x2190,0x21FF,'Arrows'),
          (0x2200,0x22FF,'Math operators'),(0x2300,0x23FF,'Misc technical'),(0x2460,0x24FF,'Enclosed alnum'),
          (0x2500,0x257F,'Box drawing'),(0x2580,0x259F,'Block elements'),(0x25A0,0x25FF,'Geometric'),
          (0x2600,0x26FF,'Misc symbols'),(0x3000,0x303F,'CJK punct'),(0x3040,0x309F,'Hiragana'),
          (0x30A0,0x30FF,'Katakana'),(0x3130,0x318F,'Hangul compat jamo'),(0x3200,0x32FF,'Enclosed CJK'),
          (0x3300,0x33FF,'CJK compat'),(0xFF00,0xFFEF,'Halfwidth/fullwidth')]
def blk(cp):
    for a,b,n in BLOCKS:
        if a<=cp<=b: return n
    return 'U+%04X other' % cp
kofbb, ko = bdf.read_bdf(sys.argv[1]); _, jsh = bdf.read_bdf(sys.argv[2])
cnt = collections.Counter(); miss = []
for r in range(0xA1,0xAD):
    for c in range(0xA1,0xFF):
        try: u = bytes([r,c]).decode('euc_kr')
        except UnicodeDecodeError: continue
        cp = ord(u)
        if cp not in ko or bdf.popcount(ko[cp]) == 0: continue
        inner = bdf.crop(ko[cp], kofbb[0], 1, 1, 16, 16)
        a = jsh.get((r-0xA1)*94+(c-0xA1)+0x211)
        if a == inner: cnt[blk(cp)] += 1
        else: miss.append('U+%04X' % cp)
for k,v in sorted(cnt.items(), key=lambda x:-x[1]): print(f'{v:5d} {k}')
print('not equal:', ' '.join(miss))
