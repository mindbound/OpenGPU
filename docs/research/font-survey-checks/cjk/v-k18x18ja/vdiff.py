"""Diff my attribution against the investigator's attribution.tsv.
usage: python -I -X utf8 vdiff.py <attribution.tsv> <18x18ja.bdf> name=kind:codec:path ...
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf  # noqa: E402

theirs = {}
with open(sys.argv[1], encoding='utf-8') as f:
    hdr = f.readline().rstrip('\n').split('\t')
    for ln in f:
        p = ln.rstrip('\n').split('\t')
        d = dict(zip(hdr, p))
        theirs[int(d['cp'][2:], 16)] = d
tgt, _ = vbdf.read_bdf(sys.argv[2])
src = {}
for spec in sys.argv[3:]:
    name, rest = spec.split('=', 1)
    kind, codec, path = rest.split(':', 2)
    g, _ = vbdf.read_bdf(path)
    if codec == 'euc_jp0212':
        g, _ = vbdf.to_unicode(g, 'euc_jp', b'\x8f')
    else:
        g, _ = vbdf.to_unicode(g, codec)
    src[name] = g

print('theirs total', len(theirs), 'sources', collections.Counter(d['source'] for d in theirs.values()))
print('their hanglg16 total', sum(1 for d in theirs.values() if d['source'] == 'hanglg16'))
mine = {}
for cp, ink in tgt.items():
    who = 'none'
    if not ink:
        who = 'blank'
    else:
        for n, g in src.items():
            s = g.get(cp)
            if s and vbdf.norm(s)[0] == vbdf.norm(ink)[0]:
                who = n
                break
    mine[cp] = who
diff = collections.Counter()
ex = collections.defaultdict(list)
for cp in tgt:
    t = theirs[cp]['source'] if theirs[cp]['how'] in ('exact', 'shift') else ('blank' if theirs[cp]['how'] == 'blank' else theirs[cp]['how'] + ':' + theirs[cp]['source'])
    m = mine[cp]
    if t != m:
        diff[(t, m)] += 1
        ex[(t, m)].append('U+%04X' % cp)
for k, v in diff.most_common():
    print(k, v, ex[k][:12])
# the how/dx/dy for the 1px-shift cases in their table where I said none
for k in ex:
    if k[1] == 'none':
        for c in ex[k][:5]:
            print(theirs[int(c[2:], 16)])
