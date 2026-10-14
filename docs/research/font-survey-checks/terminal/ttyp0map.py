"""Map a UW ttyp0 source BDF (UW-Fontspecific, named glyphs) to a Unicode BDF using mgl/unicode.mgl, with default variants.
Minimal re-implementation of the PUT/COPYTO/IFDEF/IFUNDEF subset; reads data only, runs nothing from the archive.
Usage: python -I ttyp0map.py SRC.bdf unicode.mgl OUT.bdf"""
import sys

src, mgl, out = sys.argv[1:4]
lines = open(src, encoding='latin-1').read().splitlines()
header = []; glyphs = {}; order = []; i = 0
while i < len(lines) and not lines[i].startswith('STARTCHAR'):
    if not lines[i].startswith('CHARS ') and not lines[i].startswith('ENDFONT'):
        header.append(lines[i])
    i += 1
cur = None
for l in lines[i:]:
    if l.startswith('STARTCHAR'):
        cur = [l]; name = l.split(None, 1)[1].strip()
    elif cur is not None:
        cur.append(l)
        if l == 'ENDCHAR':
            glyphs[name] = cur; order.append(name); cur = None
flags = set()
puts = []
for raw in open(mgl, encoding='latin-1'):
    w = raw.split()
    if not w or w[0].startswith('#'): continue
    ok = True
    while w and w[0] in ('IFDEF', 'IFUNDEF'):
        defined = w[1] in glyphs or w[1] in flags
        if (w[0] == 'IFDEF') != defined: ok = False
        w = w[2:]
    if not ok or not w: continue
    if w[0] == 'COPYTO' and w[1] in glyphs:
        glyphs[w[2]] = glyphs[w[1]]
    elif w[0] == 'PUT' and w[1] in glyphs:
        puts.append((w[1], int(w[2], 16)))
seen = set(); body = []
for name, cp in puts:
    if cp in seen: continue
    seen.add(cp)
    g = glyphs[name]
    g2 = ['STARTCHAR %s' % name]
    for l in g[1:]:
        g2.append('ENCODING %d' % cp if l.startswith('ENCODING') else l)
    body.extend(g2)
hdr = [h.replace('UW-Fontspecific', 'ISO10646-1') if h.startswith('FONT ') else h for h in header]
hdr = [('CHARSET_REGISTRY "ISO10646"' if h.startswith('CHARSET_REGISTRY') else 'CHARSET_ENCODING "1"' if h.startswith('CHARSET_ENCODING') else h) for h in hdr]
with open(out, 'w', encoding='latin-1', newline='\n') as f:
    f.write('\n'.join(hdr + ['CHARS %d' % len(seen)] + body + ['ENDFONT']) + '\n')
print(out, 'source glyphs', len(order), 'mapped code points', len(seen))
