"""usage: python -I jisunmapped.py IN.bdf -- lists JIS row numbers of ENCODING values that Python's euc_jp codec cannot map."""
import sys, collections
rows = collections.Counter()
for line in open(sys.argv[1], encoding='latin-1'):
    if line.startswith('ENCODING '):
        v = int(line.split()[1])
        try: bytes([(v >> 8) | 0x80, (v & 0xFF) | 0x80]).decode('euc_jp')
        except Exception: rows[(v >> 8) - 0x20] += 1
print({'unmappedByJisRow': dict(sorted(rows.items()))})
