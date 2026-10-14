# usage: python -I romcompare.py UNSCII_HEX ROMTXT... -- count ASCII glyphs of an 8x8 .hex identical to each reference transcription
import sys, re, os
sys.stdout.reconfigure(encoding='utf-8')
def hexfont(p):
    g = {}
    for line in open(p, encoding='ascii'):
        if ':' not in line: continue
        cp, b = line.strip().split(':')
        if len(b) == 16: g[int(cp, 16)] = tuple('{:08b}'.format(int(b[i:i+2], 16)).replace('0', '.').replace('1', '#') for i in range(0, 16, 2))
    return g
def txtfont(p):
    g = {}; cur = None; rows = []
    for line in open(p, encoding='utf-8', errors='replace'):
        m = re.match(r'U\+([0-9A-Fa-f]{4,6})\b', line)
        if m:
            if cur is not None and len(rows) >= 8: g.setdefault(cur, tuple(rows[:8]))
            cur = int(m.group(1), 16); rows = []; continue
        m = re.match(r'([#.]{8})(\s|$)', line)
        if m and cur is not None: rows.append(m.group(1))
    if cur is not None and len(rows) >= 8: g.setdefault(cur, tuple(rows[:8]))
    return g
def shifts(gl):
    out = {gl}
    for s in (1, -1):
        rr = tuple((r[s:] + '.' * s) if s > 0 else ('.' * -s + r[:s]) for r in gl)
        out.add(rr)
    return out
u = hexfont(sys.argv[1]); asc = range(0x21, 0x7F)
names = {'font-pc8': 'IBM PC 8x8 (CGA)'}
for p in sys.argv[2:]:
    r = txtfont(p); common = [c for c in asc if c in r and c in u]
    same = [c for c in common if u[c] == r[c]]
    shifted = [c for c in common if u[c] != r[c] and u[c] in shifts(r[c])]
    allc = [c for c in u if c in r]; same_all = [c for c in allc if u[c] == r[c]]
    print('%-18s refGlyphs=%4d asciiCompared=%2d identical=%2d shifted1px=%2d  allCompared=%4d identicalAll=%4d  ascii-identical: %s' % (
        os.path.basename(p), len(r), len(common), len(same), len(shifted), len(allc), len(same_all), ''.join(chr(c) for c in same)))
