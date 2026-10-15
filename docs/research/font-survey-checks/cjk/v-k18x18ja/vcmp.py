"""Independent re-check of 18x18ja glyph provenance.
usage: python -I -X utf8 vcmp.py <outdir> <18x18ja.bdf> name=kind:codec:path ...
kind: ucs (Unicode-encoded BDF), gl (94x94 GL BDF, needs codec), hex (unifont hex)
codec: euc_kr | euc_jp | euc_jp0212 | gb2312 | -
"""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf  # noqa: E402

outdir = sys.argv[1]
tgt, _ = vbdf.read_bdf(sys.argv[2])
sources = collections.OrderedDict()
for spec in sys.argv[3:]:
    name, rest = spec.split('=', 1)
    kind, codec, path = rest.split(':', 2)
    if kind == 'hex':
        g = vbdf.read_hex(path)
        unm = 0
    else:
        g, meta = vbdf.read_bdf(path)
        if kind == 'gl':
            if codec == 'euc_jp0212':
                g, unm = vbdf.to_unicode(g, 'euc_jp', b'\x8f')
            else:
                g, unm = vbdf.to_unicode(g, codec)
        else:
            unm = 0
    sources[name] = g
    print('source %-10s glyphs %6d unmapped %d' % (name, len(g), unm))

PAD = (1, 0)  # 1 px padding on the left; baseline-relative rows coincide


def padded(ink, d=PAD):
    return frozenset((x + d[0], y + d[1]) for x, y in ink)


shape_index = {}
for name, g in sources.items():
    idx = collections.defaultdict(list)
    for cp, ink in g.items():
        if ink:
            idx[vbdf.norm(ink)[0]].append(cp)
    shape_index[name] = idx


def classify(cp, ink, name):
    g = sources[name]
    if not ink:
        return 'blank', None
    s = g.get(cp)
    if s is not None and s:
        if padded(s) == ink:
            return 'exact', (0, 0)
        nt, ot = vbdf.norm(ink)
        ns, os_ = vbdf.norm(s)
        if nt == ns:
            return 'shift', (ot[0] - os_[0] - PAD[0], ot[1] - os_[1] - PAD[1])
    nt = vbdf.norm(ink)[0]
    if nt in shape_index[name]:
        return 'elsewhere', tuple(shape_index[name][nt][:3])
    return 'none', None


def hamming(cp, ink, name):
    s = sources[name].get(cp)
    if s is None:
        return None
    return len(padded(s) ^ ink)


out = open(os.path.join(outdir, 'vcmp.out'), 'w', encoding='utf-8')


def p(*a):
    print(*a)
    print(*a, file=out)


p('target glyphs', len(tgt))
hangul = [cp for cp in tgt if 0xAC00 <= cp <= 0xD7A3]
p('Hangul syllables in 18x18ja:', len(hangul))
for name in sources:
    c = collections.Counter()
    offs = collections.Counter()
    hd = []
    for cp in hangul:
        k, info = classify(cp, tgt[cp], name)
        c[k] += 1
        if k == 'shift':
            offs[info] += 1
        if k not in ('exact', 'shift'):
            h = hamming(cp, tgt[cp], name)
            if h is not None:
                hd.append(h)
    p('  vs %-10s %s offsets %s mean-hamming(non-ident) %s' % (
        name, dict(c), dict(offs),
        round(sum(hd) / len(hd), 1) if hd else '-'))

# shift rule for hanglg16
if 'hanglg16' in sources:
    rule = collections.Counter()
    for cp in hangul:
        k, info = classify(cp, tgt[cp], 'hanglg16')
        s = sources['hanglg16'][cp]
        xs = [x for x, _ in s]
        margins = (min(xs), 15 - max(xs))
        rule[(k, margins[1] == 0)] += 1
    p('  hanglg16 shift rule (class, source ink touches col 15):', dict(rule))

# whole-font attribution, several priority orders
names = [n for n in sources if n not in ('hanglm16', 'ko18', 'unifont', 'unifont_jp', 'gb16st', 'gb16fs_intl')]
p('attribution candidates:', names)
per = {}
for cp, ink in tgt.items():
    per[cp] = {n: classify(cp, ink, n) for n in names}


def block(cp):
    if 0xAC00 <= cp <= 0xD7A3:
        return 'Hangul syllables'
    if 0x3130 <= cp <= 0x318F:
        return 'Hangul compat jamo'
    if 0x3200 <= cp <= 0x32FF:
        return 'U+32xx'
    if 0x3300 <= cp <= 0x33FF:
        return 'U+33xx'
    if 0xF900 <= cp <= 0xFAFF:
        return 'CJK compat ideographs'
    if 0x4E00 <= cp <= 0x9FFF:
        return 'CJK unified'
    if 0x2000 <= cp <= 0x2BFF:
        return 'symbols U+2000-2BFF'
    if cp < 0x370:
        return 'Latin'
    if 0xFF00 <= cp <= 0xFFEF:
        return 'fullwidth'
    return 'other'


for order in (['j90_16', 'jiskan16', 'jksp16', 'gb16fs', 'hanglg16'],
              ['hanglg16', 'j90_16', 'jiskan16', 'jksp16', 'gb16fs']):
    order = [n for n in order if n in names]
    tot = collections.Counter()
    blk = collections.Counter()
    for cp, d in per.items():
        if not tgt[cp]:
            tot['blank'] += 1
            continue
        who = None
        for n in order:
            if d[n][0] in ('exact', 'shift'):
                who = n
                break
        if who is None:
            for n in order:
                if d[n][0] == 'elsewhere':
                    who = n + '(elsewhere)'
                    break
        who = who or 'none'
        tot[who] += 1
        if who.startswith('hanglg16'):
            blk[block(cp)] += 1
    p('order', order, dict(tot), 'sum', sum(tot.values()))
    p('   hanglg16 by block:', dict(blk))

# glyphs that match hanglg16 (same cp, exact/shift) and NO other candidate in any way
excl = collections.Counter()
any_h = 0
lst = []
for cp, d in per.items():
    if not tgt[cp]:
        continue
    if d.get('hanglg16', ('none',))[0] in ('exact', 'shift'):
        any_h += 1
        others = [n for n in names if n != 'hanglg16' and d[n][0] != 'none']
        if not others:
            excl[block(cp)] += 1
            lst.append(cp)
p('glyphs matching hanglg16 at same cp:', any_h,
  ' matching no other candidate anywhere:', sum(excl.values()), dict(excl))
with open(os.path.join(outdir, 'daewoo_exclusive.txt'), 'w') as f:
    for cp in sorted(lst):
        f.write('U+%04X\n' % cp)

# baseline: CJK unified vs unrelated fonts
for name in ('unifont', 'unifont_jp', 'gb16st', 'hanglm16'):
    if name not in sources:
        continue
    c = collections.Counter()
    for cp, ink in tgt.items():
        if 0x4E00 <= cp <= 0x9FFF:
            c[classify(cp, ink, name)[0]] += 1
    p('baseline CJK unified vs', name, dict(c))
out.close()
