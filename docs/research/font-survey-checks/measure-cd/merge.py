"""usage: python -I merge.py OUT.bdf IN1.bdf IN2.bdf ... -- concatenates glyphs (first file wins per code point), keeps IN1's header; for previews of a primary+fallback pair only."""
import sys
out, ins = sys.argv[1], sys.argv[2:]
head = []; seen = set(); chars = []
for k, path in enumerate(ins):
    lines = open(path, encoding='latin-1').read().splitlines(); cur = None
    for line in lines:
        if line.startswith('STARTCHAR'): cur = [line]; continue
        if cur is not None:
            cur.append(line)
            if line.startswith('ENDCHAR'):
                enc = [int(l.split()[1]) for l in cur if l.startswith('ENCODING')]
                if enc and enc[0] >= 0 and enc[0] not in seen: seen.add(enc[0]); chars.append(cur)
                cur = None
            continue
        if k == 0 and not line.startswith(('CHARS ', 'ENDFONT')): head.append(line)
open(out, 'w', encoding='latin-1').write('\n'.join(head + ['CHARS %d' % len(chars)] + [l for c in chars for l in c] + ['ENDFONT']) + '\n')
print({'out': out, 'glyphs': len(chars)})
