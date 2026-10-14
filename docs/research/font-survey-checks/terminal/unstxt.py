"""Minimal reader for unscii/funscii glyph source .txt files (pixel blocks, '=' overlays, FLIPX/FLIPY, named elements).
It is a lower-bound re-implementation of the subset needed to count and draw glyphs, not viznut's or asie's assembler.
Reads data only. Usage: python -I unstxt.py OUT.bdf WIDTH HEIGHT ASCENT FILE.txt...   (files in INCLUDE order; later definitions win)"""
import sys, re

out, W, H, ASC = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
defs = {}      # id -> ('bmp', rows) | ('expr', [refs]) | ('flipx'|'flipy', ref)
HDR = re.compile(r'^U\+([0-9A-Za-z_\-]+)\s*(.*)$')
SIZE = re.compile(r'^(\d+)x(\d+)\s*$')

def key(tok):
    tok = tok.strip()
    if tok.startswith("'") and len(tok) >= 2: return 'c%X' % ord(tok[1])
    if tok.upper().startswith('U+'): tok = tok[2:]
    if re.fullmatch(r'[0-9A-Fa-f]{4,6}', tok): return 'c%X' % int(tok, 16)
    return 'n' + tok

def resolve(k, depth=0):
    if k in cache: return cache[k]
    d = defs.get(k)
    if d is None or depth > 40: return None
    if d[0] == 'bmp': r = d[1]
    elif d[0] == 'expr':
        parts = [resolve(x, depth + 1) for x in d[1]]
        if any(p is None for p in parts): r = None
        else: r = [''.join('#' if any(p[y][x] == '#' for p in parts) else '.' for x in range(W)) for y in range(H)]
    elif d[0] == 'flipx':
        p = resolve(d[1], depth + 1); r = [row[::-1] for row in p] if p else None
    else:
        p = resolve(d[1], depth + 1); r = p[::-1] if p else None
    cache[k] = r
    return r

def eager(k):
    cache.clear(); r = resolve(k)
    if r is not None: defs[k] = ('bmp', r)
    cache.clear()

skipped = 0
cache = {}
for path in sys.argv[5:]:
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    i = 0
    while i < len(lines):
        m = HDR.match(lines[i].strip())
        if not m: i += 1; continue
        k = key(m.group(1)); rest = m.group(2).strip(); i += 1
        if rest.startswith('='):
            refs = [r for r in re.split(r'\s*\+\s*', rest[1:].strip()) if r]
            defs[k] = ('expr', [key(r) for r in refs]); eager(k); continue
        if rest.startswith('FLIPX') or rest.startswith('FLIPY'):
            defs[k] = (rest[:5].lower(), key(rest[5:].strip())); eager(k); continue
        if rest: skipped += 1; continue
        if i < len(lines) and SIZE.match(lines[i].strip()):
            w, h = map(int, SIZE.match(lines[i].strip()).groups()); i += 1
            if (w, h) != (W, H): skipped += 1
            rows = []
            while i < len(lines) and lines[i].strip() and set(lines[i].split()[0]) <= set('.#'):
                rows.append(lines[i].split()[0]); i += 1
            if (w, h) == (W, H):
                rows = [(r + '.' * W)[:W] for r in rows][:H]
                rows += ['.' * W] * (H - len(rows))
                defs[k] = ('bmp', rows)
        else:
            skipped += 1

cache = {}
glyphs = {}
for k in defs:
    if k.startswith('c'):
        r = resolve(k)
        if r is not None: glyphs[int(k[1:], 16)] = r
unresolved = sum(1 for k in defs if k.startswith('c') and int(k[1:], 16) not in glyphs)
nb = (W + 7) // 8
with open(out, 'w', encoding='ascii', newline='\n') as f:
    f.write('STARTFONT 2.1\nFONT -funscii-%dx%d\nSIZE %d 75 75\nFONTBOUNDINGBOX %d %d 0 %d\nSTARTPROPERTIES 3\nFONT_ASCENT %d\nFONT_DESCENT %d\nSPACING "C"\nENDPROPERTIES\nCHARS %d\n'
            % (W, H, H, W, H, ASC - H, ASC, H - ASC, len(glyphs)))
    for cp in sorted(glyphs):
        f.write('STARTCHAR U%04X\nENCODING %d\nSWIDTH 500 0\nDWIDTH %d 0\nBBX %d %d 0 %d\nBITMAP\n' % (cp, cp, W, W, H, ASC - H))
        for row in glyphs[cp]:
            v = int(row.replace('.', '0').replace('#', '1'), 2) << (nb * 8 - W)
            f.write('%0*X\n' % (nb * 2, v))
        f.write('ENDCHAR\n')
    f.write('ENDFONT\n')
print(out, 'code points', len(glyphs), 'unresolved', unresolved, 'skipped blocks', skipped)
