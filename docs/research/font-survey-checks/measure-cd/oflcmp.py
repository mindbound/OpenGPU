"""usage: python -I oflcmp.py REF FILE... -- compares the OFL body (from 'PREAMBLE' to end of DISCLAIMER) word by word; prints header lines before the body."""
import sys, re
def split(path):
    t = open(path, encoding='utf-8', errors='replace').read()
    i = t.find('PREAMBLE'); j = t.find('OTHER DEALINGS IN THE FONT SOFTWARE', i)
    body = re.sub(r'\s+', ' ', t[i:j + 36] if j > 0 else t[i:]).strip()
    return t[:i].strip().splitlines(), body
_, ref = split(sys.argv[1])
for f in sys.argv[2:]:
    head, body = split(f)
    hd = [l for l in head if l.strip() and not set(l.strip()) <= set('-')]
    print(f.split('/')[-2] + '/' + f.split('/')[-1], '| body identical to reference:', body == ref, '| header:', ' / '.join(hd)[:400].encode('ascii', 'replace').decode())
