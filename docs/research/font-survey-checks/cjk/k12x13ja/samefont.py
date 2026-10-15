# Compare two BDF fonts glyph by glyph (encoding, bbx, pixels).
# usage: python -I -X utf8 samefont.py a.bdf b.bdf
import sys, os, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bdflib import read_bdf
pa, A = read_bdf(sys.argv[1]); pb, B = read_bdf(sys.argv[2])
same = [k for k in A if k in B and A[k]['pix'] == B[k]['pix'] and A[k]['bbx'] == B[k]['bbx']]
diff = [k for k in A if k in B and k not in same]
print('A', len(A), 'B', len(B), 'identical', len(same), 'different', len(diff), 'only A', len(set(A) - set(B)), 'only B', len(set(B) - set(A)))
for k in diff[:30]:
    print('  differs: %04X  |A^B| = %d' % (k, len(A[k]['pix'] ^ B[k]['pix'])))
for t in sys.argv[3:]:
    print(t, datetime.datetime.fromtimestamp(int(t), datetime.timezone.utc).isoformat())
