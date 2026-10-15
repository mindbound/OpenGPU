"""29 §8 F5: OC's character widths against musl's table and the misc-fixed faces (own code, MIT).

Reads OC's FontUtils.scala and font.hex and the BDFs as data; nothing from them is executed.
argv: FontUtils.scala  font.hex  12x13ja.bdf  <narrow face BDFs...>
"""
import sys, re, unicodedata
from collections import Counter

fu, hexpath, ja = sys.argv[1], sys.argv[2], sys.argv[3]
faces = sys.argv[4:]
src = open(fu, encoding='utf-8').read()

def arr(name):
    m = re.search(r'val %s = Array\[Int\]\((.*?)\)' % name, src, re.S)
    return [int(x) for x in re.findall(r'\d+', m.group(1))]
table, wtable = arr('table'), arr('wtable')

def c_wcwidth(c):  # port of OC's copy of musl src/ctype/wcwidth.c (FontUtils.scala:205-221)
    if c < 0xFF:
        if ((c + 1) & 0x7F) >= 0x21: return 1
        return -1 if c > 0 else 0
    if (c & 0xfffeffff) < 0xfffe:
        if (table[table[c >> 8] * 32 + ((c & 0xFF) >> 3)] >> (c & 7)) & 1: return 0
        if (wtable[wtable[c >> 8] * 32 + ((c & 0xFF) >> 3)] >> (c & 7)) & 1: return 2
        return 1
    if (c & 0xfffe) == 0xfffe: return -1
    if 0x20000 <= c < 0x40000: return 2
    if c == 0xe0001 or 0xe0020 <= c < 0xe007f or 0xe0100 <= c < 0xe01ef: return 0
    return 1

LIMIT = 0x110000
musl = [c_wcwidth(c) for c in range(LIMIT)]
musl_wide = {c for c in range(LIMIT) if musl[c] == 2}
oc_wide = set(musl_wide)
hexw = {}
for line in open(hexpath, encoding='utf-8'):
    line = line.rstrip('\n')
    if ':' not in line: continue
    cp = int(line[:line.index(':')], 16)
    n = len(line) - line.index(':') - 1
    if cp >= LIMIT: continue
    hexw[cp] = n
    if n == 64: oc_wide.add(cp)
    elif n == 32: oc_wide.discard(cp)

def block(c):
    try: nm = unicodedata.name(chr(c))
    except ValueError: nm = '<unnamed>'
    return nm

def ranges(s):
    s = sorted(s); out = []; i = 0
    while i < len(s):
        j = i
        while j + 1 < len(s) and s[j + 1] == s[j] + 1: j += 1
        out.append((s[i], s[j])); i = j + 1
    return out

def page_counts(s):
    cnt = Counter((c >> 8) for c in s)
    return ', '.join('U+%04Xxx %d' % (p, n) for p, n in sorted(cnt.items()))

print('musl: wide %d, zero %d, nonprintable %d (of U+0000-10FFFF)' % (
    len(musl_wide), sum(1 for w in musl if w == 0), sum(1 for w in musl if w == -1)))
print('OC effective wide (musl, then font.hex 64 -> wide, 32 -> narrow): %d' % len(oc_wide))
added, removed = oc_wide - musl_wide, musl_wide - oc_wide
print('font.hex makes wide where musl says 1 or less: %d' % len(added))
print('   by page:', page_counts(added))
for a, b in ranges(added)[:60]:
    print('   U+%04X-%04X  %s .. %s  musl=%s' % (a, b, block(a), block(b), sorted(set(musl[c] for c in range(a, b + 1)))))
print('font.hex makes narrow where musl says 2: %d' % len(removed))
print('   by page:', page_counts(removed))
for a, b in ranges(removed)[:60]:
    print('   U+%04X-%04X  %s .. %s' % (a, b, block(a), block(b)))
# Python's own East Asian Width as a third opinion
eaw = {c for c in range(LIMIT) if unicodedata.east_asian_width(chr(c)) in ('W', 'F')}
print('Python %s EAW W/F: %d; musl-not-EAW %d; EAW-not-musl %d' % (unicodedata.unidata_version, len(eaw), len(musl_wide - eaw), len(eaw - musl_wide)))

def bdf(path):
    g = {}; cp = None; dw = None
    for line in open(path, encoding='latin-1'):
        if line.startswith('ENCODING'):
            cp = int(line.split()[1])
        elif line.startswith('DWIDTH'):
            dw = int(line.split()[1])
        elif line.startswith('ENDCHAR'):
            if cp is not None and cp >= 0: g[cp] = dw
            cp = None
    return g

jag = bdf(ja)
print('\n12x13ja: %d glyphs; covers %d of musl-wide BMP codepoints (%d); OC-wide BMP covered %d of %d' % (
    len(jag), len(set(jag) & {c for c in musl_wide if c < 0x10000}), len([c for c in musl_wide if c < 0x10000]),
    len(set(jag) & {c for c in oc_wide if c < 0x10000}), len([c for c in oc_wide if c < 0x10000])))
for f in faces:
    g = bdf(f)
    name = f.replace('\\', '/').split('/')[-1]
    w2 = [c for c in g if musl[c] == 2]
    z = [c for c in g if musl[c] == 0]
    ocw = [c for c in g if c in oc_wide and musl[c] != 2]
    print('\n%s: %d glyphs; at musl-wide codepoints %d; at musl-zero-width codepoints %d; at codepoints only font.hex makes wide %d' % (
        name, len(g), len(w2), len(z), len(ocw)))
    if w2: print('   musl-wide:', ' '.join('U+%04X' % c for c in sorted(w2)[:40]), '...' if len(w2) > 40 else '')
    if ocw: print('   font.hex-wide:', page_counts(ocw))
