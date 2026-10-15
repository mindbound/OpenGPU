"""Compare 18x18ja.bdf (inner 16x16 after removing a 1-px pad) with candidate
16x16 source fonts, per Unicode code point and by bitmap identity.

Usage:
  python -I -X utf8 compare18ja.py key=path [key=path ...]
Keys: t18ja (18x18ja.bdf), t18ko (18x18ko.bdf), hanglg16, hanglm16, jiskan16,
      j90_16, jksp16, gb16fs, gb16st, unihex (unifont_all .hex.gz),
      unijp (unifont_jp .bdf.gz)
"""
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdflib  # noqa: E402


def gl_to_unicode(enc, codec):
    """Map a 94x94 GL code (0x2121..0x7E7E) to a Unicode string via codec."""
    hi, lo = enc >> 8, enc & 0xFF
    if not (0x21 <= hi <= 0x7E and 0x21 <= lo <= 0x7E):
        return None
    if codec == 'jisx0212':
        b = bytes([0x8F, hi | 0x80, lo | 0x80])
        c = 'euc_jp'
    else:
        b = bytes([hi | 0x80, lo | 0x80])
        c = codec
    try:
        s = b.decode(c)
    except UnicodeDecodeError:
        return None
    if len(s) != 1:
        return None
    return ord(s)


def block(cp):
    if 0xAC00 <= cp <= 0xD7A3:
        return 'Hangul syllables'
    if 0x3131 <= cp <= 0x318E:
        return 'Hangul compat jamo'
    if 0x3200 <= cp <= 0x32FF:
        return 'Encl. CJK letters/months'
    if 0x4E00 <= cp <= 0x9FFF:
        return 'CJK unified'
    if 0xF900 <= cp <= 0xFAFF:
        return 'CJK compat ideographs'
    if 0x3040 <= cp <= 0x30FF:
        return 'Kana'
    if 0x3000 <= cp <= 0x303F:
        return 'CJK symbols/punct'
    if 0xFF00 <= cp <= 0xFFEF:
        return 'Fullwidth forms'
    if 0x2E80 <= cp <= 0x2FFF:
        return 'Radicals/IDC'
    if 0x3300 <= cp <= 0x33FF:
        return 'CJK compatibility'
    if 0x0370 <= cp <= 0x03FF:
        return 'Greek'
    if 0x0400 <= cp <= 0x04FF:
        return 'Cyrillic'
    if cp < 0x0370:
        return 'Latin etc. <U+0370'
    if 0x2000 <= cp <= 0x2BFF:
        return 'Symbols U+2000-2BFF'
    return 'other U+%04X block' % (cp & 0xFF00)


def main():
    paths = dict(a.split('=', 1) for a in sys.argv[1:])
    fbb, props, t, _ = bdflib.read_bdf(paths['t18ja'])
    print('18x18ja FBB', fbb, 'glyphs', len(t))
    inner = {}
    nonblank_border = []
    for cp, rows in t.items():
        i, blank = bdflib.inner16_of_18(rows)
        inner[cp] = i
        if not blank:
            nonblank_border.append(cp)
    print('glyphs with ink in the 1-px border:', len(nonblank_border))
    bc = collections.Counter(block(cp) for cp in t)
    print('18x18ja inventory by block:')
    for k, v in sorted(bc.items(), key=lambda x: -x[1]):
        print('   %-28s %6d' % (k, v))
    bbc = collections.Counter(block(cp) for cp in nonblank_border)
    print('  border-ink glyphs by block:', dict(bbc))

    # load sources
    srcs = {}
    spec = [('hanglg16', 'euc_kr'), ('hanglm16', 'euc_kr'), ('jiskan16', 'euc_jp'),
            ('j90_16', 'euc_jp'), ('jksp16', 'jisx0212'), ('gb16fs', 'gb2312'),
            ('gb16st', 'gb2312')]
    for key, codec in spec:
        if key not in paths:
            continue
        _, _, g, _ = bdflib.read_bdf(paths[key])
        m = {}
        unmapped = 0
        for enc, rows in g.items():
            u = gl_to_unicode(enc, codec)
            if u is None:
                unmapped += 1
                continue
            m[u] = rows
        srcs[key] = m
        print('source %-9s glyphs %5d mapped %5d unmapped %d' % (key, len(g), len(m), unmapped))

    if 't18ko' in paths:
        _, _, g, _ = bdflib.read_bdf(paths['t18ko'])
        srcs['18x18ko-inner'] = {cp: bdflib.inner16_of_18(r)[0] for cp, r in g.items()}
    if 'unihex' in paths:
        srcs['unifont-hex'] = bdflib.read_hex(paths['unihex'])
    if 'unijp' in paths:
        fb, _, g, _ = bdflib.read_bdf(paths['unijp'])
        # keep only 16-wide glyphs rendered in a 16-wide canvas
        if fb[0] == 16 and fb[1] == 16:
            srcs['unifont_jp'] = g
        else:
            print('unifont_jp FBB', fb, '-> skipped')

    # per-source, per-block identity at the same code point
    print()
    print('PER-CODE-POINT COMPARISON (target inner 16x16 vs source 16x16)')
    print('  identical / compared, plus identical-up-to-translation(+-2px) and mean Hamming distance of non-identical')
    blocks_of_interest = None
    for key, m in srcs.items():
        per = collections.defaultdict(lambda: [0, 0, 0, 0])  # compared, identical, ident-shift, sumdist
        for cp, srows in m.items():
            if cp not in inner:
                continue
            b = block(cp)
            tr = inner[cp]
            per[b][0] += 1
            if tr == srows:
                per[b][1] += 1
            else:
                d, dx, dy = bdflib.best_shift_hamming(srows, tr)
                if d == 0:
                    per[b][2] += 1
                per[b][3] += bdflib.hamming(srows, tr)
        print(' source', key)
        for b, (n, ide, ids, sd) in sorted(per.items(), key=lambda x: -x[1][0]):
            nd = n - ide
            print('   %-28s compared %5d identical %5d (%.1f%%)  shift-identical %4d  mean-dist(non-ident) %s' % (
                b, n, ide, 100.0 * ide / n, ids, ('%.1f' % (sd / nd)) if nd else '-'))

    # attribution: for each target glyph, which sources match exactly at same cp
    print()
    print('ATTRIBUTION of each 18x18ja glyph to sources with an identical glyph at the same code point')
    order = [k for k in ('j90_16', 'jiskan16', 'jksp16', 'gb16fs', 'hanglg16', 'hanglm16', 'gb16st')
             if k in srcs]
    att = collections.defaultdict(collections.Counter)
    unmatched = collections.defaultdict(list)
    for cp, tr in inner.items():
        hits = tuple(k for k in order if srcs[k].get(cp) == tr)
        b = block(cp)
        att[b][hits if hits else ('NONE',)] += 1
        if not hits:
            unmatched[b].append(cp)
    for b in sorted(att, key=lambda x: -sum(att[x].values())):
        print('  ', b, sum(att[b].values()))
        for hits, n in att[b].most_common():
            print('       %-40s %6d' % ('+'.join(hits), n))

    # bitmap-set membership (mapping-independent)
    print()
    print('BITMAP-SET MEMBERSHIP (target glyph bitmap found anywhere in source, any code point)')
    for key in order + [k for k in srcs if k not in order]:
        s = set(srcs[key].values())
        per = collections.Counter()
        tot = collections.Counter()
        for cp, tr in inner.items():
            if not any(tr):
                continue
            b = block(cp)
            tot[b] += 1
            if tr in s:
                per[b] += 1
        print('  source %-14s ' % key + '; '.join('%s %d/%d' % (b, per[b], tot[b]) for b in
              ('Hangul syllables', 'Hangul compat jamo', 'Encl. CJK letters/months', 'CJK unified',
               'Symbols U+2000-2BFF', 'Kana')))

    # dump unmatched lists for the Korean-relevant blocks
    print()
    for b in ('Hangul syllables', 'Hangul compat jamo', 'Encl. CJK letters/months'):
        lst = unmatched.get(b, [])
        print('unmatched in %s: %d %s' % (b, len(lst), ' '.join('U+%04X' % c for c in sorted(lst)[:60])))
    if 'outdir' in paths:
        with open(os.path.join(paths['outdir'], 'unmatched.txt'), 'w', encoding='utf-8') as f:
            for b, lst in sorted(unmatched.items()):
                f.write('%s\t%d\t%s\n' % (b, len(lst), ' '.join('U+%04X' % c for c in sorted(lst))))


if __name__ == '__main__':
    main()
