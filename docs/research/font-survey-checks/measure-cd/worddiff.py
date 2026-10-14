"""usage: python -I worddiff.py REF OTHER [startword] -- word-level diff of two licence texts from the first occurrence of startword."""
import sys, difflib
s = sys.argv[3] if len(sys.argv) > 3 else 'PREAMBLE'
def words(p):
    t = open(p, encoding='utf-8', errors='replace').read(); return t[t.find(s):].split()
a, b = words(sys.argv[1]), words(sys.argv[2])
for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
    if op != 'equal': print(op, ' '.join(a[i1:i2])[:200], '=>', ' '.join(b[j1:j2])[:200])
