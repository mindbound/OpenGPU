"""S8 U3: font 0's glyph table (S8 plan section 3.14) re-derived from 6x13.bdf without the Java converter's code.

Usage: python -I -X utf8 font0_table.py 6x13.bdf
       python -I -X utf8 font0_table.py --self-test
Reads the BDF 2.1 file as data only and checks its SHA-256 (29:381). Applies section 3.14's checks, with amendment F's
rule that an ENCODING -1 glyph fails rather than being skipped: FONTBOUNDINGBOX 6 13 0 -2, FONT_ASCENT 11 and
FONT_DESCENT 2; per glyph DWIDTH 6 0, 0 <= bbxX, bbxX + bbxW <= 6, -2 <= bbxY, bbxY + bbxH <= 11, bbxH one-byte rows,
no bit beyond bbxW, and a unique encoding that is a Unicode scalar value; U+FFFD present. Places bitmap row i (top
first) at cell row 11 - (bbxY + bbxH) + i as row >> bbxX, MSB = column 0 (28:628). Builds section 3.14's body,
little-endian: u32 n; n u32 codepoints ascending; n x 13 row bytes; the 13 missing-glyph bytes, U+FFFD's (28:632).
Prints the body's SHA-256, n and the body's CRC32 (zlib's, the same function as java.util.zip.CRC32), section 3.14's
coverage counts, the glyphs at C0 and C1 controls (28:647), and q1_font0.py's checks at 6x13 (28 §7.1, §7.3), then
ALL PASS, or the failures with exit status 1. As information only, the whole file under this script's reading of
section 3.14's header: each line ends in LF, then one empty line, and source-id is the text after "COMMENT ".
--self-test runs a hand-made two-glyph BDF through the same code, checks the placement for bbxX 0 and 1 and bbxY -2
and 0 against rows written out by hand, and checks that each rule rejects a file that breaks it.
"""
import hashlib
import re
import struct
import sys
import zlib

SOURCE_SHA256 = 'd00cf1cbaed055bb91d11c80e063d5d2d3b66552a7f2ead7161ee3780f9123f0'  # 29:381
CW, CH, ASCENT, DESCENT, MISSING, N_FONT0 = 6, 13, 11, 2, 0xFFFD, 4121           # 28:628, :632
SUBSET = [c for a, b in ((0x20, 0x7E), (0xA0, 0xFF), (0x2500, 0x259F)) for c in range(a, b + 1)]
GREEK = [c for a, b in ((0x391, 0x3A1), (0x3A3, 0x3A9), (0x3B1, 0x3C9)) for c in range(a, b + 1)]
CYRILLIC = list(range(0x400, 0x460))
CONTROLS = set(range(0x00, 0x20)) | set(range(0x7F, 0xA0))                       # 28:647
TIERS = (('T1', 160, 100), ('T2', 320, 200), ('T3', 640, 400))
FONT_KEYS = ('CONTENTVERSION', 'FONT', 'SIZE', 'METRICSSET', 'SWIDTH', 'DWIDTH', 'SWIDTH1', 'DWIDTH1', 'VVECTOR')
GLYPH_KEYS = ('ENCODING', 'SWIDTH', 'DWIDTH', 'SWIDTH1', 'DWIDTH1', 'VVECTOR', 'BBX')
INT, HEX = re.compile(r'-?[0-9]+$'), re.compile(r'[0-9A-Fa-f]+$')


class BdfError(Exception):
    pass


def ints(tokens, no):
    if not tokens or not all(INT.match(t) for t in tokens):
        raise BdfError('line %d: expected integers, got %r' % (no, ' '.join(tokens)))
    return [int(t) for t in tokens]


def parse(text):
    """BDF 2.1 text -> (header, props, comments, glyphs); each glyph a dict of its keywords' integers and hex rows."""
    header, props, comments, glyphs, g, nprops, state = {}, {}, [], [], None, 0, 'start'
    for no, line in enumerate(text.split('\n'), 1):
        w = line.split()
        if state == 'bitmap':
            if w == ['ENDCHAR']:
                glyphs.append(g)
                g, state = None, 'glyphs'
            elif len(w) == 1 and HEX.match(w[0]):
                g['rows'].append(w[0])
            else:
                raise BdfError('line %d: a hex row or ENDCHAR expected in %s' % (no, g['name']))
            continue
        if not w:
            continue
        key = w[0]
        if state == 'start':
            if w != ['STARTFONT', '2.1']:
                raise BdfError('line %d: STARTFONT 2.1 expected' % no)
            state = 'header'
        elif key == 'COMMENT':
            comments.append(line[len('COMMENT '):])
        elif state == 'props':
            if key == 'ENDPROPERTIES':
                if len(props) != nprops:
                    raise BdfError('line %d: STARTPROPERTIES %d, %d given' % (no, nprops, len(props)))
                state = 'header'
            elif key in props:
                raise BdfError('line %d: property %s repeated' % (no, key))
            else:
                v = line.strip()[len(key):].strip()
                if v.startswith('"'):
                    if len(v) < 2 or not v.endswith('"'):
                        raise BdfError('line %d: unterminated string' % no)
                    props[key] = v[1:-1].replace('""', '"')
                else:
                    props[key] = ints([v], no)[0]
        elif state == 'header':
            if key == 'STARTPROPERTIES':
                nprops, state = ints(w[1:], no)[0], 'props'
            elif key == 'FONTBOUNDINGBOX':
                header[key] = ints(w[1:], no)
            elif key == 'CHARS':
                header[key], state = ints(w[1:], no)[0], 'glyphs'
            elif key in FONT_KEYS:
                header[key] = w[1:]
            else:
                raise BdfError('line %d: %s unexpected in the header' % (no, key))
        elif state == 'glyphs':
            if key == 'STARTCHAR':
                g, state = {'name': ' '.join(w[1:]), 'rows': []}, 'glyph'
            elif key == 'ENDFONT':
                state = 'end'
            else:
                raise BdfError('line %d: STARTCHAR or ENDFONT expected' % no)
        elif state == 'glyph':
            if key == 'BITMAP':
                state = 'bitmap'
            elif key in g:
                raise BdfError('line %d: %s repeated in %s' % (no, key, g['name']))
            elif key in GLYPH_KEYS:
                g[key] = ints(w[1:], no)
            elif key != 'ATTRIBUTES':
                raise BdfError('line %d: %s unexpected in %s' % (no, key, g['name']))
        else:
            raise BdfError('line %d: text after ENDFONT' % no)
    if state != 'end':
        raise BdfError('ENDFONT missing')
    return header, props, comments, glyphs


def place(bbx, rows):
    """Section 3.14: bitmap row i (top first) goes to cell row 11 - (bbxY + bbxH) + i as row >> bbxX."""
    w, h, x, y = bbx
    cell = bytearray(CH)
    for i, row in enumerate(rows):
        cell[ASCENT - (y + h) + i] = row >> x
    return bytes(cell)


def convert(glyphs):
    """Section 3.14's per-glyph checks; returns ({cp: 13 row bytes} of the glyphs that pass, {rule: [glyph]})."""
    table, seen = {}, set()
    bad = dict((k, []) for k in ('enc', 'neg', 'scalar', 'dup', 'dw', 'bbx', 'rows', 'bits'))
    for g in glyphs:
        enc, bbx, rows = g.get('ENCODING'), g.get('BBX'), g['rows']
        if enc is not None and enc[0] < 0:
            bad['neg'].append(g['name'])        # amendment F: fail, never skip
            continue
        if enc is None or len(enc) != 1:        # a second number belongs to ENCODING -1 only
            bad['enc'].append(g['name'])
            continue
        cp, ok = enc[0], True
        label = 'U+%04X' % cp
        if cp > 0x10FFFF or 0xD800 <= cp <= 0xDFFF:
            bad['scalar'].append(label)
            ok = False
        if cp in seen:
            bad['dup'].append(label)
            ok = False
        seen.add(cp)
        if g.get('DWIDTH') != [CW, 0]:
            bad['dw'].append(label)
            ok = False
        if bbx is None or len(bbx) != 4 or not (bbx[0] >= 0 and bbx[1] >= 0 and bbx[2] >= 0 and bbx[2] + bbx[0] <= CW
                                                and bbx[3] >= -DESCENT and bbx[3] + bbx[1] <= ASCENT):
            bad['bbx'].append(label)
            continue
        if len(rows) != bbx[1] or any(len(r) != 2 for r in rows):
            bad['rows'].append(label)
            continue
        if any(int(r, 16) & (0xFF >> bbx[0]) for r in rows):
            bad['bits'].append(label)
            continue
        if ok:
            table[cp] = place(bbx, [int(r, 16) for r in rows])
    return table, bad


def body(table):
    order = sorted(table)
    return (struct.pack('<I', len(order)) + struct.pack('<%dI' % len(order), *order)
            + b''.join(table[c] for c in order) + table[MISSING])


class Report(object):
    def __init__(self, quiet=False):
        self.quiet, self.fails = quiet, []

    def say(self, text):
        if not self.quiet:
            print(text)

    def check(self, name, ok, info=''):
        self.say('%s %s%s' % ('PASS' if ok else 'FAIL', name, (' -- %s' % (info,)) if info != '' else ''))
        if not ok:
            self.fails.append(name)
        return ok


def listed(items):
    return '%d: %s%s' % (len(items), ' '.join(items[:12]), ' ...' if len(items) > 12 else '') if items else ''


def run(data, rep, source=None):
    """Checks and builds the table from the BDF's bytes; source names the file for font 0's run, None in the self-test.
    Returns (table, body) or (None, None) when a structural check fails."""
    sha = hashlib.sha256(data).hexdigest()
    if source is not None:
        rep.say('== %s: %d B' % (source, len(data)))
        rep.check('source SHA-256 = 29:381\'s', sha == SOURCE_SHA256, sha)
    rep.check('ASCII with LF line ends (amendment F)', all(b < 0x80 for b in data) and b'\r' not in data)
    before = len(rep.fails)   # the converter's own checks follow; the table is built only if they all pass
    try:
        header, props, comments, glyphs = parse(data.decode('latin-1'))
    except BdfError as e:
        rep.check('BDF 2.1 structure', False, str(e))
        return None, None
    rep.check('BDF 2.1 structure, CHARS = glyphs', header.get('CHARS') == len(glyphs),
              '%s / %d' % (header.get('CHARS'), len(glyphs)))
    ids = [c for c in comments if '$Id' in c]
    rep.say('source-id [%s]; copyright [%s]' % (ids[0] if ids else '', props.get('COPYRIGHT', '')))
    rep.check('FONTBOUNDINGBOX 6 13 0 -2', header.get('FONTBOUNDINGBOX') == [CW, CH, 0, -DESCENT],
              header.get('FONTBOUNDINGBOX'))
    rep.check('FONT_ASCENT 11', props.get('FONT_ASCENT') == ASCENT, props.get('FONT_ASCENT'))
    rep.check('FONT_DESCENT 2', props.get('FONT_DESCENT') == DESCENT, props.get('FONT_DESCENT'))
    table, bad = convert(glyphs)
    for key, name in (('neg', 'no ENCODING -1 (amendment F)'), ('enc', 'every glyph has one ENCODING'),
                      ('scalar', 'every encoding a Unicode scalar value'), ('dup', 'encodings unique'),
                      ('dw', 'every glyph DWIDTH 6 0'),
                      ('bbx', 'every BBX inside the cell: 0 <= x, x + w <= 6, -2 <= y, y + h <= 11'),
                      ('rows', 'every glyph has bbxH one-byte rows'), ('bits', 'no bit beyond bbxW in a row')):
        rep.check(name, not bad[key], listed(bad[key]))
    rep.check('U+FFFD present', MISSING in table)
    if len(rep.fails) > before:
        return None, None
    out = body(table)
    if source is None:
        return table, out
    rep.say('body: n %d, %d B (17 n + 17), first U+%04X, last U+%04X' % (len(table), len(out), min(table), max(table)))
    rep.say('body SHA-256 %s' % hashlib.sha256(out).hexdigest())
    rep.say('body CRC32 0x%08x (%d)' % (zlib.crc32(out) & 0xFFFFFFFF, zlib.crc32(out) & 0xFFFFFFFF))
    for cp in (MISSING, 0x41):
        rep.say('U+%04X rows: %s' % (cp, ' '.join('%02X' % b for b in table[cp])))
    forms = {}
    for g in glyphs:
        forms[tuple(g['BBX'])] = forms.get(tuple(g['BBX']), 0) + 1
    rep.say('BBX forms (w h x y: glyphs): %s' % ', '.join('%d %d %d %d: %d' % (k + (forms[k],)) for k in sorted(forms)))
    rep.check('n = 4121 (28:632)', len(table) == N_FONT0, len(table))
    font0_counts(table, rep)
    q1_checks(header, props, glyphs, table, rep)
    head = ''.join(line + '\n' for line in (
        'OpenGPU glyph table 1', 'font 0', 'cell 6x13', 'glyphs %d' % len(table), 'missing U+FFFD',
        'source %s' % source, 'source-sha256 %s' % sha, 'source-id %s' % (ids[0] if ids else ''),
        'copyright %s' % props.get('COPYRIGHT', ''), 'licence public domain; credited in OpenGPU\'s NOTICE')) + '\n'
    whole = head.encode('ascii') + out + struct.pack('<I', zlib.crc32(out) & 0xFFFFFFFF)
    rep.say('(info) whole file under this reading of the header: %d B (header %d B), SHA-256 %s'
            % (len(whole), len(head), hashlib.sha256(whole).hexdigest()))
    return table, out


def font0_counts(t, rep):
    """Section 3.14's expected counts (28:632, :1053; 29:291-296) and the control glyphs (28:647, amendment F)."""
    inked = lambda cps, blanks=(): sum(1 for c in cps if c in t and (any(t[c]) or c in blanks))
    for name, cps, blanks in (
            ('U+0020-007E, U+00A0-00FF, U+2500-259F inked, blanks counted (q1_font0.py:83)', SUBSET, (0x20, 0xA0)),
            ('box drawing U+2500-257F inked (29:293)', range(0x2500, 0x2580), ()),
            ('blocks U+2580-259F inked (29:293)', range(0x2580, 0x25A0), ()),
            ('Greek letters inked', GREEK, ()), ('Cyrillic U+0400-045F inked', CYRILLIC, ())):
        n = inked(cps, blanks)
        rep.check(name, n == len(cps), '%d/%d' % (n, len(cps)))
    rep.say('U+0020 and U+00A0 blank: %s, %s' % (not any(t.get(0x20, b'x')), not any(t.get(0xA0, b'x'))))
    pua = sorted(c for c in t if 0xE000 <= c <= 0xF8FF or c >= 0xF0000)
    gaps = ['U+%04X' % c for c in range(pua[0], pua[-1]) if c not in t] if pua else []
    rep.check('107 private-use glyphs from U+E000 (28:1053)', len(pua) == 107,
              '%d, U+%04X-U+%04X, %d inked, gaps %s' % (len(pua), pua[0], pua[-1], inked(pua), listed(gaps) or 'none')
              if pua else 0)
    ctl = sorted(c for c in t if c in CONTROLS)
    rep.check('glyphs at C0/C1 controls: U+0000 only (amendment F)', ctl == [0],
              listed(['U+%04X' % c for c in ctl]))


def q1_checks(header, props, glyphs, t, rep):
    """q1_font0.py's checks, recomputed at the font's own metrics, with 28's and section 3.14's numbers."""
    cw, ch = header['FONTBOUNDINGBOX'][:2]
    asc, desc = props['FONT_ASCENT'], props['FONT_DESCENT']
    rep.say('q1_font0.py: cell %dx%d, ascent %d, descent %d, %d glyphs' % (cw, ch, asc, desc, len(t)))
    rep.check('ascent + descent = cell height', asc + desc == ch)
    rep.check('every advance = cell width', all(g['DWIDTH'][0] == cw for g in glyphs))
    rep.check('every glyph box inside the cell', all(0 <= g['BBX'][2] and g['BBX'][2] + g['BBX'][0] <= cw and
                                                     -desc <= g['BBX'][3] and g['BBX'][3] + g['BBX'][1] <= asc
                                                     for g in glyphs))
    rep.check('U+FFFD present', MISSING in t)
    face, sub, slots = len(t) * cw * ch, (len(SUBSET) + 1) * cw * ch, (1024 // cw) * (1024 // ch)
    rep.check('atlas at 1 B per texel: face 321438 B, subset + U+FFFD 27456 B, 13260 cells a 1024^2 page (28:632)',
              (face, sub, slots) == (321438, 27456, 13260), '%d, %d, %d' % (face, sub, slots))
    per_row = 1024 // cw
    w, h = per_row * cw, -(-(len(t) + 1) // per_row) * ch
    rep.check('atlas of n + 1 cells, %d a row: 1020x325, 331500 B (section 3.14)' % per_row,
              (w, h, w * h) == (1020, 325, 331500), '%dx%d, %d B' % (w, h, w * h))
    rep.check('fill charge 78 px per codepoint (28:655)', cw * ch == 78, cw * ch)
    lines = ', '.join('%s %dx%d' % (n, W // cw, H // ch) for n, W, H in TIERS)
    rep.check('cells per line x lines (28:655; 29:294)', lines == 'T1 26x7, T2 53x15, T3 106x30', lines)
    mask = (0xFF << (8 - cw)) & 0xFF                                           # 28:633's synthetic G at the cell
    box = bytes([mask] + [0x80 | (0x80 >> (cw - 1))] * (ch - 2) + [mask])
    drawn = [bytes(((7 * cp + 29 * r) % 256) & mask for r in range(ch))
             for cp in list(range(0x21, 0x7F)) + list(range(0xA1, 0x100))]
    rep.say('synthetic G %dx%d: mask 0x%02X, box rows 0 and %d = 0x%02X, rows 1-%d = 0x%02X'
            % (cw, ch, mask, ch - 1, box[0], ch - 2, box[1]))
    rep.check('  189 drawn, pairwise distinct, none equal to the box or the blank',
              len(drawn) == 189 and len(set(drawn)) == 189 and box not in drawn and bytes(ch) not in drawn)
    rep.check('  every one of the %d bit columns set somewhere' % cw,
              all(any(row & (0x80 >> b) for gl in drawn for row in gl) for b in range(cw)))
    rep.check('  no bit set outside the %d columns' % cw, not any(row & ~mask & 0xFF for gl in drawn + [box] for row in gl))


SELF_BDF = '''STARTFONT 2.1
COMMENT $Id: selftest.bdf,v 1 $
FONTBOUNDINGBOX 6 13 0 -2
STARTPROPERTIES 3
FONT_ASCENT 11
FONT_DESCENT 2
COPYRIGHT "Own  work, ""quoted""."
ENDPROPERTIES
CHARS 2
STARTCHAR uniFFFD
ENCODING 65533
DWIDTH 6 0
BBX 5 4 0 -2
BITMAP
F8
88
A8
F8
ENDCHAR
STARTCHAR A
ENCODING 65
SWIDTH 480 0
DWIDTH 6 0
BBX 4 5 1 0
BITMAP
60
90
F0
90
90
ENDCHAR
ENDFONT
'''
# Written out by hand: A (bbxX 1, bbxY 0) at rows 6-10 shifted one column right; U+FFFD (bbxX 0, bbxY -2) at rows 9-12.
SELF_A = bytes.fromhex('000000000000' '3048784848' '0000')
SELF_FFFD = bytes.fromhex('000000000000000000' 'F888A8F8')
SELF_BODY = bytes.fromhex('02000000' '41000000' 'FDFF0000') + SELF_A + SELF_FFFD + SELF_FFFD
MUTANTS = (  # (old, new, the check that must fail)
    ('ENCODING 65\n', 'ENCODING -1\n', 'no ENCODING -1 (amendment F)'),
    ('ENCODING 65\n', 'ENCODING -1 65\n', 'no ENCODING -1 (amendment F)'),
    ('ENCODING 65\n', 'ENCODING 65 66\n', 'every glyph has one ENCODING'),
    ('ENCODING 65\n', 'ENCODING 55296\n', 'every encoding a Unicode scalar value'),
    ('ENCODING 65\n', 'ENCODING 1114112\n', 'every encoding a Unicode scalar value'),
    ('ENCODING 65\n', 'ENCODING 65533\n', 'encodings unique'),
    ('ENCODING 65533\n', 'ENCODING 65534\n', 'U+FFFD present'),
    ('DWIDTH 6 0\nBBX 4', 'DWIDTH 5 0\nBBX 4', 'every glyph DWIDTH 6 0'),
    ('DWIDTH 6 0\nBBX 4', 'DWIDTH 6 1\nBBX 4', 'every glyph DWIDTH 6 0'),
    ('BBX 4 5 1 0', 'BBX 4 5 3 0', 'every BBX inside the cell: 0 <= x, x + w <= 6, -2 <= y, y + h <= 11'),
    ('BBX 4 5 1 0', 'BBX 4 5 1 7', 'every BBX inside the cell: 0 <= x, x + w <= 6, -2 <= y, y + h <= 11'),
    ('BBX 5 4 0 -2', 'BBX 5 4 0 -3', 'every BBX inside the cell: 0 <= x, x + w <= 6, -2 <= y, y + h <= 11'),
    ('BBX 5 4 0 -2', 'BBX 5 4 -1 -2', 'every BBX inside the cell: 0 <= x, x + w <= 6, -2 <= y, y + h <= 11'),
    ('F0\n90\n90\nENDCHAR', 'F0\n90\nENDCHAR', 'every glyph has bbxH one-byte rows'),
    ('F0\n90\n90\nENDCHAR', 'F0\n90\n0090\nENDCHAR', 'every glyph has bbxH one-byte rows'),
    ('60\n90\nF0', '68\n90\nF0', 'no bit beyond bbxW in a row'),
    ('FONTBOUNDINGBOX 6 13 0 -2', 'FONTBOUNDINGBOX 6 13 0 -3', 'FONTBOUNDINGBOX 6 13 0 -2'),
    ('FONT_ASCENT 11', 'FONT_ASCENT 12', 'FONT_ASCENT 11'),
    ('FONT_DESCENT 2', 'FONT_DESCENT 3', 'FONT_DESCENT 2'),
    ('CHARS 2', 'CHARS 3', 'BDF 2.1 structure, CHARS = glyphs'),
    ('\nENDFONT\n', '\n', 'BDF 2.1 structure'),
    ('BITMAP\n60', 'BITMAP\nzz', 'BDF 2.1 structure'),
    ('ENDCHAR\nENDFONT', 'ENDCHAR\nENDFONT', None))  # unchanged: passes


def self_test():
    fails = []
    def expect(name, ok):
        if not ok:
            fails.append(name)
    for bbx, rows, want in (([4, 5, 1, 0], [0x60, 0x90, 0xF0, 0x90, 0x90], SELF_A),       # bbxX 1, bbxY 0
                            ([5, 4, 0, -2], [0xF8, 0x88, 0xA8, 0xF8], SELF_FFFD),         # bbxX 0, bbxY -2
                            ([3, 2, 1, -2], [0xE0, 0xA0], bytes(11) + b'\x70\x50'),      # bbxX 1, bbxY -2
                            ([6, 1, 0, 0], [0xFC], bytes(10) + b'\xFC\x00\x00'),          # bbxX 0, bbxY 0
                            ([6, 13, 0, -2], list(range(0x04, 0x38, 4)), bytes(range(0x04, 0x38, 4)))):
        expect('place %r' % (bbx,), place(bbx, rows) == want)
    rep = Report(quiet=True)
    table, out = run(SELF_BDF.encode('ascii'), rep)
    expect('two-glyph BDF passes: %r' % (rep.fails,), not rep.fails and table is not None)
    expect('A placed by hand', table is not None and table.get(0x41) == SELF_A)
    expect('U+FFFD placed by hand', table is not None and table.get(MISSING) == SELF_FFFD)
    expect('body = hand-assembled bytes', out == SELF_BODY)
    expect('copyright unquoted', parse(SELF_BDF)[1]['COPYRIGHT'] == 'Own  work, "quoted".')
    for old, new, rule in MUTANTS:
        text = SELF_BDF.replace(old, new, 1)
        rep = Report(quiet=True)
        run(text.encode('ascii'), rep)
        if rule is None:
            expect('unmutated passes', not rep.fails)
        else:
            expect('%r -> %r fails %r (failed: %r)' % (old, new, rule, rep.fails), text != SELF_BDF and rule in rep.fails)
    n = 5 + 5 + len(MUTANTS)
    print('SELF-TEST %s: %d cases%s' % ('PASS' if not fails else 'FAIL', n, ''.join('\nFAIL ' + f for f in fails)))
    return not fails


def main(argv):
    if argv == ['--self-test']:
        return 0 if self_test() else 1
    if len(argv) != 1:
        print('Usage: python -I -X utf8 font0_table.py 6x13.bdf | --self-test')
        return 2
    path = argv[0]
    with open(path, 'rb') as f:
        data = f.read()
    rep = Report()
    run(data, rep, path.replace('\\', '/').split('/')[-1])
    print('ALL PASS' if not rep.fails else 'FAILED %d: %s' % (len(rep.fails), '; '.join(rep.fails)))
    return 0 if not rep.fails else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
