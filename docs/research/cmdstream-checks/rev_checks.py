# Reviser's checks for note 28 (final): numbers and rules the revision adds or changes.
# Run with: python -I rev_checks.py
# 2026-10-15: TEXT carries a u8 font at offset 14 (29 F4): 17 + n B, UNKNOWN_FONT after the flags check.
# 2026-10-15 (S6): BAD_UTF8 and CONTROL_CHAR come from one scan (28:648); before, a control ahead of bad UTF-8 gave BAD_UTF8.
import math, struct

ok_all = True
def check(name, cond, detail=''):
    global ok_all
    ok_all &= bool(cond)
    print(('PASS ' if cond else 'FAIL ') + name + ((' : ' + str(detail)) if detail != '' else ''))

# ---------------------------------------------------------------- Lua builders, both number models
class LuaErr(Exception): pass

def lua(model):
    def floor(v):
        f = math.floor(v)
        return int(f) if model == 'int' else float(f)
    def div(a, b): return float(a) / float(b)
    def mod(a, b): return a - math.floor(a / b) * b
    def char(*args):
        out = []
        for i, v in enumerate(args):
            if model == 'int' and isinstance(v, float) and v != int(v):
                raise LuaErr('arg %d: number has no integer representation' % (i + 1))
            v = int(math.trunc(v))
            if not 0 <= v <= 255: raise LuaErr('arg %d: value out of range (%d)' % (i + 1, v))
            out.append(v)
        return bytes(out)
    def split32(c):
        c = floor(c); c0 = mod(c, 256); c = floor(div(c, 256)); c1 = mod(c, 256); c = floor(div(c, 256))
        return c0, c1, mod(c, 256), floor(div(c, 256))
    def lo_hi(v): return mod(v, 256), floor(div(v, 256))
    def i16(v): return lo_hi(mod(floor(v), 65536))
    def clear(c): return char(0x01, *split32(c))
    def fill(x, y, w, h, c): return char(0x30, *i16(x), *i16(y), *i16(w), *i16(h), *split32(c))
    def blit(t, sx, sy, w, h, dx, dy, flags):
        flags = mod(floor(flags), 256)  # revision: flags floored like every operand
        return char(0x31, *split32(t), *i16(sx), *i16(sy), *i16(w), *i16(h), *i16(dx), *i16(dy), flags)
    def text(x, y, fg, bg, flags, font, s):
        flags = mod(floor(flags), 256); font = mod(floor(font), 256); n = len(s)
        return char(0x33, *i16(x), *i16(y), *split32(fg), *split32(bg), flags, font, *lo_hi(n)) + s
    return dict(clear=clear, fill=fill, blit=blit, text=text)

HEADER, END = b'OGPU\x01\x00\x00\x00', b'\xff'
EXPECTED_T2 = ('4F 47 50 55 01 00 00 00 01 00 00 00 00 30 0A 00 14 00 64 00 32 00 C4 00 00 00 '
               '31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 '
               '33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 FF')
def hexs(b): return ' '.join('%02X' % x for x in b)
for model in ('dbl', 'int'):
    B = lua(model)
    s = HEADER + B['clear'](0) + B['fill'](10, 20, 100, 50, 196) + B['blit'](0x08000000, 0, 0, 16, 16, 150, 40, 1) \
        + B['text'](8, 180, 255, 0, 1, 0, b'Hi') + END
    check('T2 worked example, %s model, floored flags and font' % model, hexs(s) == EXPECTED_T2 and len(s) == 64, len(s))
    s3 = HEADER + B['clear'](0xFF000000) + B['fill'](10, 20, 100, 50, 0xFFFF8000) + B['blit'](0x08000000, 0, 0, 16, 16, 150, 40, 1) \
        + B['text'](8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, b'Hi') + END
    check('T3 variant is 64 B too, %s model' % model, len(s3) == 64, hexs(s3[8:13]) + ' | ' + hexs(s3[22:26]))
    # flags = 1.5: floored -> 1 on both models (the draft's unfloored flags raised on 5.3)
    check('blit flags 1.5 floors to 1, %s' % model, B['blit'](0x08000000, 0, 0, 1, 1, 0, 0, 1.5)[-1] == 1)
    # out-of-range: i16 wraps, u32 raises
    check('i16 -1 wraps to FF FF, %s' % model, B['fill'](-1, 0, 0, 0, 0)[1:3] == b'\xff\xff')
    for c in (-1, 2 ** 32):
        try:
            B['clear'](c); raised = False
        except LuaErr:
            raised = True
        check('u32 colour %d raises in string.char, %s' % (c, model), raised)

# ---------------------------------------------------------------- colours, quantize, palette
def q(c, m): return (c * m + 127) // 255
def sv565(c): return (q(c >> 16 & 255, 31) << 11) | (q(c >> 8 & 255, 63) << 5) | q(c & 255, 31)
def expand565(v):
    r, g, b = v >> 11, (v >> 5) & 63, v & 31
    return (r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2)
check('sv(0xFF000000) = 0x0000', sv565(0xFF000000) == 0)
check('sv(0xFFFF8000) = 0xFC00', sv565(0xFFFF8000) == 0xFC00, hex(sv565(0xFFFF8000)))
check('sv(0xFFFFFFFF) = 0xFFFF', sv565(0xFFFFFFFF) == 0xFFFF)
check('q(expand(v)) = v for all 65536', all(sv565(0xFF000000 | (lambda e: e[0] << 16 | e[1] << 8 | e[2])(expand565(v))) == v for v in range(65536)))
greys = [(255 * (k + 1) + 20) // 41 for k in range(40)]
check('grey anchors: entry 216 = 6, entry 255 = 249', greys[0] == 6 and greys[39] == 249, (greys[0], greys[39]))
check('greys strictly increasing, none equal to a cube level', all(a < b for a, b in zip(greys, greys[1:])) and not set(greys) & {0, 51, 102, 153, 204, 255})
cube0 = (51 * 0, 51 * 0, 51 * 0)
check('palette entry 0 is black', cube0 == (0, 0, 0))

# ---------------------------------------------------------------- caps in bytes, fill, RAM
caps = dict(submit=65536, frame=(65536, 131072, 262144), vram=(262144, 1048576, 4194304), readback=(16384, 32768, 65536))
check('frame caps = 64/128/256 KiB', caps['frame'] == (64 * 1024, 128 * 1024, 256 * 1024))
check('VRAM caps = 256 KiB/1 MiB/4 MiB', caps['vram'] == (256 * 1024, 1 << 20, 4 << 20))
surf = ((160, 100, 1), (320, 200, 1), (640, 400, 2))
check('fill caps = 16 surfaces', [16 * w * h for w, h, _ in surf] == [256000, 1024000, 4096000])
check('readback calls for a full surface: T1 1, T2 2, T3 8',
      [math.ceil(w * h * b / r) for (w, h, b), r in zip(surf, caps['readback'])] == [1, 2, 8])
headroom = 353894
lo, hi = 2 * 65536, 3 * 65536
print('INFO 64 KiB segment build: %d-%d B real = %.0f-%.0f KB apparent (/1.8) = %.0f-%.0f %% of %d B'
      % (lo, hi, lo / 1.8 / 1000, hi / 1.8 / 1000, 100 * lo / headroom, 100 * hi / headroom, headroom))
print('INFO 64 KiB string apparent: %.1f KB; 16 KiB readback apparent: %.1f KB' % (65536 / 1.8 / 1000, 16384 / 1.8 / 1000))

# ---------------------------------------------------------------- budget charges with the revised order
def submit_charge(n, t): return 1 / 32 * 2 ** -t + min(n, 65536) / (1 << 20)
check('submit charge <= 0.094 for any length (min with the cap)', max(submit_charge(n, 0) for n in (0, 65536, 10 << 20)) <= 0.094,
      submit_charge(10 << 20, 0))
def pause_s(c, k):
    over = c - k
    return ((over - k) / k) / 20 if over > k else 0.0
for name, t, bpp, side in (('T2', 1, 1, 1024), ('T3', 2, 2, 1024)):
    c = side * side * bpp / 262144; k = (t + 1) * 0.5
    print('INFO max createTexture %s: c = %.3g, k = %.2g, pause = %.3f s' % (name, c, k, pause_s(c, k)))
# what an unchecked createTexture(1e9, 1e9) would do at T3: Machine.pause ticks = (seconds*20).toInt saturates
w = h = check_integer_early = int(1e9)
c_dbl = w * h * 2 / 262144
ticks = min(int(pause_s(c_dbl, 1.5) * 20), 2 ** 31 - 1)
print('INFO unchecked createTexture(1e9,1e9) at T3: c = %.3g, pause ticks = %d (%.0f days)' % (c_dbl, ticks, ticks / 20 / 86400))
prod = (w * h * 2) & 0xFFFFFFFF
prod = prod - 2 ** 32 if prod >= 2 ** 31 else prod
print('INFO the same product in Java int: %d B, charge %.3g' % (prod, prod / 262144))
c = 512000 / 262144
check('full T3 surface upload: c = 1.95, one-tick stall, no pause', round(c, 2) == 1.95 and c - 1.5 > 0 and pause_s(c, 1.5) == 0.0)
check('2 MiB upload at T3: pause 0.17 s', round(pause_s(8.0, 1.5), 2) == 0.17)

# ---------------------------------------------------------------- callback range checks in Java int
INT_MAX, INT_MIN = 2 ** 31 - 1, -2 ** 31
def check_integer(v):  # OC ArgumentsImpl.checkInteger for a Double: NaN throws, saturate, else truncate toward zero
    if v != v: raise ValueError('NaN')
    if v > INT_MAX: return INT_MAX
    if v < INT_MIN: return INT_MIN
    return int(v)
def region_ok(x, y, w, h, TW, TH):  # the revised rule, every operation safe in 32-bit int
    return 0 <= x <= TW and 1 <= w <= TW - x and 0 <= y <= TH and 1 <= h <= TH - y
TW = TH = 16
cases = [((1e10, 0, 1, 1), False), ((1, 0, INT_MAX, 1), False), ((0, 0, 16, 16), True), ((15, 15, 1, 1), True),
         ((16, 0, 1, 1), False), ((-1, 0, 1, 1), False), ((0, 0, 0, 1), False), ((16.7, 0, 0.5, 1), False), ((3.9, 0, 1.2, 1), True)]
for args, want in cases:
    a = [check_integer(v) for v in args]
    check('region %s -> %s' % (args, want), region_ok(*a, TW, TH) == want, a)

# ---------------------------------------------------------------- tail accounting for resource records
for name, v in zip(('T1', 'T2', 'T3'), caps['vram']):
    print('INFO %s: at most %d resource records of 64 B in the tail' % (name, v // 64))

# ---------------------------------------------------------------- synthetic glyph table for CI
def synth(cp):
    if cp in (0x20, 0xA0): return bytes(16)
    if 0x21 <= cp <= 0x7E or 0xA1 <= cp <= 0xFF: return bytes(((7 * cp + 29 * r) & 0xFF) for r in range(16))
    return None
MISSING = bytes([0xFF] + [0x81] * 14 + [0xFF])
gl = [synth(cp) for cp in list(range(0x21, 0x7F)) + list(range(0xA1, 0x100))]
check('synthetic glyphs: 189 drawn, pairwise distinct, none equal to the missing glyph or blank',
      len(gl) == 189 and len(set(gl)) == 189 and MISSING not in gl and bytes(16) not in gl, len(gl))
check('synthetic glyphs set every bit column somewhere', all(any(g[r] >> (7 - b) & 1 for g in gl for r in range(16)) for b in range(8)))

# ---------------------------------------------------------------- the naive validator, from the error table
SIZES = {0x01: 5, 0x30: 13, 0x31: 18, 0x33: 17}
NAMES = {0x01: 'CLEAR', 0x30: 'FILL_RECT', 0x31: 'BLIT', 0x33: 'TEXT', 0xFF: 'END'}
def colour_ok(c, fmt): return c <= 255 if fmt == 'index8' else (c >> 24) == 0xFF
def validate(s, fmt='index8', W=320, H=200, live=None, frame_bytes=0, frame_cap=131072):
    live = live or {}
    if len(s) > 65536: return ('OVER_SUBMIT_BYTES', 0, 'submit')
    if frame_bytes + len(s) > frame_cap: return ('OVER_FRAME_BYTES', 0, 'submit')
    if len(s) < 8: return ('TRUNCATED_HEADER', 0, 'header')
    if s[0:4] != b'OGPU': return ('BAD_MAGIC', 0, 'header')
    if s[4] != 1: return ('BAD_VERSION', 4, 'header')
    if s[5] != 0: return ('BAD_FLAGS', 5, 'header')
    if s[6] or s[7]: return ('BAD_RESERVED', 6, 'header')
    p = 8
    while True:
        if p == len(s): return ('MISSING_END', p, 'END')
        op = s[p]
        if op == 0xFF:
            return ('OK', p, 'END') if p + 1 == len(s) else ('TRAILING_BYTES', p, 'END')
        if op not in SIZES: return ('UNKNOWN_OPCODE', p, '0x%02X' % op)
        nm, size = NAMES[op], SIZES[op]
        if p + size > len(s): return ('TRUNCATED_RECORD', p, nm)
        if op == 0x33:
            n = struct.unpack_from('<H', s, p + 15)[0]
            if n > 1024: return ('TEXT_TOO_LONG', p, nm)
            if p + 17 + n > len(s): return ('TRUNCATED_RECORD', p, nm)
            size += n
        if op == 0x01 and not colour_ok(struct.unpack_from('<I', s, p + 1)[0], fmt): return ('BAD_COLOUR', p, nm)
        if op == 0x30 and not colour_ok(struct.unpack_from('<I', s, p + 9)[0], fmt): return ('BAD_COLOUR', p, nm)
        if op == 0x31:
            h = struct.unpack_from('<I', s, p + 1)[0]
            if not (0 < h < 2 ** 31 and h >> 27): return ('BAD_HANDLE', p, nm)
            if (h >> 27) >= 8: return ('UNKNOWN_TYPE', p, nm)
            if (h >> 27) != 1: return ('WRONG_TYPE', p, nm)
            if h not in live: return ('STALE_HANDLE', p, nm)
            if s[p + 17] & 0xFE: return ('BAD_FLAGS_FIELD', p, nm)
        if op == 0x33:
            fg, bg = struct.unpack_from('<II', s, p + 5)
            if not colour_ok(fg, fmt) or not colour_ok(bg, fmt): return ('BAD_COLOUR', p, nm)
            if s[p + 13] & 0xFE: return ('BAD_FLAGS_FIELD', p, nm)
            if s[p + 14] != 0: return ('UNKNOWN_FONT', p, nm)  # M0 defines font 0 only
            raw, bad = s[p + 17:p + size], None
            try:
                txt = raw.decode('utf-8')
            except UnicodeDecodeError as e:  # one left-to-right scan (28:648): a control before the bad sequence decides
                txt, bad = raw[:e.start].decode('utf-8'), e.start
            if any(ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F for ch in txt): return ('CONTROL_CHAR', p, nm)
            if bad is not None: return ('BAD_UTF8', p, nm)
        p += size

B = lua('int')
live = {0x08000000: (16, 16)}
good = HEADER + B['clear'](0) + B['fill'](10, 20, 100, 50, 196) + B['blit'](0x08000000, 0, 0, 16, 16, 150, 40, 1) \
       + B['text'](8, 180, 255, 0, 1, 0, b'Hi') + END
check('naive validator accepts the worked example', validate(good, live=live)[0] == 'OK')
check('header only -> MISSING_END at 8', validate(HEADER, live=live) == ('MISSING_END', 8, 'END'))
check('END then a byte -> TRAILING_BYTES at the END', validate(HEADER + END + b'\x00', live=live) == ('TRAILING_BYTES', 8, 'END'))
fr = B['fill'](0, 0, 1, 1, 5)
check('FILL_RECT one byte short + END, index8 -> BAD_COLOUR (END read as colour)',
      validate(HEADER + fr[:-1] + END, live=live) == ('BAD_COLOUR', 8, 'FILL_RECT'))
check('same on rgb565 -> MISSING_END (0xFF is a valid alpha)',
      validate(HEADER + B['fill'](0, 0, 1, 1, 0xFF000005)[:-1] + END, fmt='rgb565', live=live) == ('MISSING_END', 21, 'END'))
check('FILL_RECT two bytes short + END -> TRUNCATED_RECORD', validate(HEADER + fr[:-2] + END, live=live) == ('TRUNCATED_RECORD', 8, 'FILL_RECT'))
t = B['text'](0, 0, 1, 0, 0, 0, b'ab')
bad_t = t[:5] + b'\x00\x01\x00\x00' + t[9:]  # fg = 0x100, n still 2
check('TEXT bad fg and tail past the end -> TRUNCATED_RECORD first (structure before fields)',
      validate(HEADER + bad_t[:-1], live=live) == ('TRUNCATED_RECORD', 8, 'TEXT'))
long_t = B['text'](0, 0, 1, 0, 0, 0, b'a' * 1025)
check('TEXT n = 1025 -> TEXT_TOO_LONG', validate(HEADER + long_t + END, live=live) == ('TEXT_TOO_LONG', 8, 'TEXT'))
check('TEXT U+000A -> CONTROL_CHAR', validate(HEADER + B['text'](0, 0, 1, 0, 0, 0, b'a\nb') + END, live=live)[0] == 'CONTROL_CHAR')
check('TEXT overlong NUL -> BAD_UTF8', validate(HEADER + B['text'](0, 0, 1, 0, 0, 0, b'\xc0\x80') + END, live=live)[0] == 'BAD_UTF8')
check('TEXT control then bad UTF-8 -> CONTROL_CHAR (one scan, the first decides)',
      validate(HEADER + B['text'](0, 0, 1, 0, 0, 0, b'\n\xc0\x80') + END, live=live)[0] == 'CONTROL_CHAR')
check('TEXT bad UTF-8 then control -> BAD_UTF8 (one scan, the first decides)',
      validate(HEADER + B['text'](0, 0, 1, 0, 0, 0, b'\xc0\x80\n') + END, live=live)[0] == 'BAD_UTF8')
for fo in (1, 255):
    check('TEXT font %d -> UNKNOWN_FONT' % fo, validate(HEADER + B['text'](0, 0, 1, 0, 0, fo, b'a') + END, live=live) == ('UNKNOWN_FONT', 8, 'TEXT'))
check('TEXT bad flags and bad font -> BAD_FLAGS_FIELD first (layout order)',
      validate(HEADER + B['text'](0, 0, 1, 0, 2, 1, b'a') + END, live=live)[0] == 'BAD_FLAGS_FIELD')
check('TEXT bad font and bad UTF-8 -> UNKNOWN_FONT first (layout order)',
      validate(HEADER + B['text'](0, 0, 1, 0, 0, 1, b'\xc0\x80') + END, live=live)[0] == 'UNKNOWN_FONT')
rec = len(B['text'](0, 0, 1, 0, 0, 0, b'a'))
check('full T3 screen of font 0: 106 x 30 one-codepoint TEXT records of 18 B = 57 240 B', 106 * 30 * rec == 57240, 106 * 30 * rec)
for h, code in ((0, 'BAD_HANDLE'), (0x80000000, 'BAD_HANDLE'), (0x00000005, 'BAD_HANDLE'), (0x48000000, 'UNKNOWN_TYPE'),
                (0x10000000, 'WRONG_TYPE'), (0x08000001, 'STALE_HANDLE'), (0x08010000, 'STALE_HANDLE')):
    check('BLIT handle 0x%08X -> %s' % (h, code), validate(HEADER + B['blit'](h, 0, 0, 1, 1, 0, 0, 0) + END, live=live)[0] == code)
check('BLIT stale handle and bad flags -> handle first (layout order)',
      validate(HEADER + B['blit'](0x08000001, 0, 0, 1, 1, 0, 0, 2) + END, live=live)[0] == 'STALE_HANDLE')
check('frame byte cap is checked before the header', validate(b'xx', frame_bytes=131071)[0] == 'OVER_FRAME_BYTES')

print('ALL PASS' if ok_all else 'SOME FAILED')
