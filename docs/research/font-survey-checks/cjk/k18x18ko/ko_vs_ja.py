"""Count 18x18ko glyphs that are identical to 18x18ja at the same code point.
Usage: python -I -X utf8 ko_vs_ja.py <18x18ko.bdf> <18x18ja.bdf>"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdf
_, ko = bdf.read_bdf(sys.argv[1]); _, ja = bdf.read_bdf(sys.argv[2])
hang = set(range(0xAC00, 0xD7A4))
rest = [cp for cp in ko if cp not in hang]
same = [cp for cp in rest if cp in ja and ja[cp] == ko[cp]]
notin = [cp for cp in rest if cp not in ja]
print('18x18ko glyphs', len(ko), '; non-syllable', len(rest), '; identical to 18x18ja', len(same),
      '; absent from 18x18ja', len(notin))
uro = [cp for cp in rest if 0x4E00 <= cp <= 0x9FFF]
print('URO in 18x18ko', len(uro), '; identical to 18x18ja', sum(1 for cp in uro if cp in ja and ja[cp]==ko[cp]))
