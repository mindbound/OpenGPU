"""Independent golden model for the tier-E oracle's hand-computed vectors, 28 §11.4 (own code, MIT).

Usage: python -I -X utf8 oracle_goldens.py <goldens file> [--emit <case>]

Checks S6's golden file (plan §6.4's grammar, with amendment C's mandatory first directive
'glyphs synthetic-6x13 28:633' and its decimal-or-0x numbers) against a model written from note 28's
text alone, never from the Java oracle:
- sv and q from 28 §3.4 and 28:495, colour validity from §3.4;
- framing and layouts from §3.1, §3.2 and §5.2: every op line is encoded to v1 bytes and decoded back;
- validation as a positive case in §3.5 and §3.6's order, against a live view whose handles come from
  §6.1's FIFO allocator, so a 'texture' line must name the handle that allocator gives next;
- rects, clipping, CLEAR, FILL_RECT, BLIT with KEY0 and TEXT with TRANSPARENT_BG from §5.1-§5.7. Every
  draw is computed twice, by §5.7's ranges and per surface pixel by the membership rules of §5.1, §5.5
  and §5.6, and the two must agree in pixels and in pixel count;
- textures from §6.2-§6.4 (created zeroed, region updates rows top first in §5.7's byte layout, free),
  RESET from §2.2 and 28:496;
- the synthetic 6x13 glyph table from §7.1-§7.3 and 28:633;
- totals from §3.5: records without END; CLEAR W*H, FILL_RECT its clipped rect, BLIT its clipped i and j
  ranges with KEY0-skipped texels included, TEXT 78 per codepoint, unclipped.
Prints PASS or FAIL per case (a FAIL shows the expected, actual and diff grids and the differing
pixels), then ALL PASS. --emit <case> prints that case's derived totals and grids in its row format.

File-format reading where plan §6.4 leaves a choice: '#' starts a comment only as a line's first
non-blank character, and never inside a row block (rows may begin with a 'chars' character '#');
there are no trailing comments; a row block is the next N non-blank lines; 'chars' rows are W
characters with no separators, also for texture and update rows; 'hex' takes bytes as two hex digits
or 0x-prefixed.
"""
import struct
import sys
from collections import deque

INDEX8, RGB565 = 1, 2
FORMATS = {'index8': INDEX8, 'rgb565': RGB565}
BPP = {INDEX8: 1, RGB565: 2}
DIGITS = {INDEX8: 2, RGB565: 4}
MAXVAL = {INDEX8: 0xFF, RGB565: 0xFFFF}

GLYPHS_DIRECTIVE = ['glyphs', 'synthetic-6x13', '28:633']


# ---- colours: 28 §3.4, 28:495 ----------------------------------------------------------------

def q(v, m):
    return (v * m + 127) // 255


def colour_valid(fmt, c):
    if fmt == INDEX8:
        return 0 <= c <= 0xFF
    return (c >> 24) & 0xFF == 0xFF


def sv(fmt, c):
    if fmt == INDEX8:
        return c
    return (q((c >> 16) & 255, 31) << 11) | (q((c >> 8) & 255, 63) << 5) | q(c & 255, 31)


# ---- glyphs: 28 §7.1-§7.3, 28:633 ------------------------------------------------------------

CELL_W, CELL_H = 6, 13
CELL_FILL = CELL_W * CELL_H
BLANK = (0,) * CELL_H
MISSING = (0xFC,) + (0x84,) * (CELL_H - 2) + (0xFC,)


def glyph(cp):
    if cp in (0x20, 0xA0):
        return BLANK
    if 0x21 <= cp <= 0x7E or 0xA1 <= cp <= 0xFF:
        return tuple(((7 * cp + 29 * r) % 256) & 0xFC for r in range(CELL_H))
    return MISSING


def glyph_bit(g, r, b):
    return (g[r] >> (7 - b)) & 1          # column b = 0 is the leftmost, bit 7 (28 §5.6)


def self_check():
    expand5 = lambda v: (v << 3) | (v >> 2)
    expand6 = lambda v: (v << 2) | (v >> 4)
    assert sv(RGB565, 0xFF000000) == 0x0000          # 28 §3.4's examples
    assert sv(RGB565, 0xFFFF8000) == 0xFC00
    assert sv(RGB565, 0xFFFFFFFF) == 0xFFFF
    assert all(q(expand5(v), 31) == v for v in range(32))
    assert all(q(expand6(v), 63) == v for v in range(64))
    drawn = [glyph(cp) for cp in list(range(0x21, 0x7F)) + list(range(0xA1, 0x100))]
    assert len(drawn) == 189 and len(set(drawn)) == 189          # 28:633's checks
    assert MISSING not in drawn and BLANK not in drawn
    assert all(any(glyph_bit(g, r, b) for g in drawn for r in range(CELL_H)) for b in range(CELL_W))
    assert all(row & 0x03 == 0 for g in drawn + [MISSING] for row in g)
    assert glyph(0x20) == glyph(0xA0) == BLANK and glyph(0x20AC) == MISSING and glyph(0x1F600) == MISSING


# ---- framing and layouts: 28 §3.1, §3.2, §5.2 ------------------------------------------------

OP_CLEAR, OP_FILL, OP_BLIT, OP_TEXT, OP_END = 0x01, 0x30, 0x31, 0x33, 0xFF
FIXED = {OP_CLEAR: 5, OP_FILL: 13, OP_BLIT: 18, OP_TEXT: 17}
NAME = {OP_CLEAR: 'CLEAR', OP_FILL: 'FILL_RECT', OP_BLIT: 'BLIT', OP_TEXT: 'TEXT'}
CLASS = {OP_CLEAR: 'clear', OP_FILL: 'fill', OP_BLIT: 'copy', OP_TEXT: 'set'}
CLASSES = ('clear', 'fill', 'copy', 'set')
HEADER = b'OGPU' + bytes((1, 0, 0, 0))
KEY0 = 0x01
TRANSPARENT_BG = 0x01
TEXT_MAX = 1024
# plan §6.4's permissive FrameLimits(65536, 1 << 20, 4096, Long.MAX_VALUE): submit, frame bytes, records, fill
SUBMIT_CAP, FRAME_BYTE_CAP, FRAME_RECORD_CAP = 65536, 1 << 20, 4096


def enc_clear(c):
    return struct.pack('<BI', OP_CLEAR, c)


def enc_fill(x, y, w, h, c):
    return struct.pack('<BhhhhI', OP_FILL, x, y, w, h, c)


def enc_blit(tex, sx, sy, w, h, dx, dy, flags):
    return struct.pack('<BIhhhhhhB', OP_BLIT, tex, sx, sy, w, h, dx, dy, flags)


def enc_text(x, y, fg, bg, flags, font, data):
    return struct.pack('<BhhIIBBH', OP_TEXT, x, y, fg, bg, flags, font, len(data)) + data


# ---- handles: 28 §6.1 --------------------------------------------------------------------------

def make_handle(t, slot, gen):
    return (t << 27) | (slot << 16) | gen


def handle_type(h):
    return (h >> 27) & 0xF


def handle_slot(h):
    return (h >> 16) & 0x7FF


def handle_gen(h):
    return h & 0xFFFF


KIND = {2: 'buffer', 3: 'render target', 4: 'program', 5: 'pipeline', 6: 'kernel', 7: 'compute list'}


# ---- UTF-8: 28 §7.2, Unicode Table 3-7 ---------------------------------------------------------

def utf8_scan(b):
    """Returns (codepoints, None) or (None, (code, string byte index))."""
    cps, i, n = [], 0, len(b)
    while i < n:
        c = b[i]
        if c < 0x80:
            length, lo, hi = 1, 0, 0
        elif 0xC2 <= c <= 0xDF:
            length, lo, hi = 2, 0x80, 0xBF
        elif c == 0xE0:
            length, lo, hi = 3, 0xA0, 0xBF
        elif 0xE1 <= c <= 0xEC or 0xEE <= c <= 0xEF:
            length, lo, hi = 3, 0x80, 0xBF
        elif c == 0xED:
            length, lo, hi = 3, 0x80, 0x9F
        elif c == 0xF0:
            length, lo, hi = 4, 0x90, 0xBF
        elif 0xF1 <= c <= 0xF3:
            length, lo, hi = 4, 0x80, 0xBF
        elif c == 0xF4:
            length, lo, hi = 4, 0x80, 0x8F
        else:
            return None, ('BAD_UTF8', i)
        if i + length > n:
            return None, ('BAD_UTF8', i)
        if length > 1:
            if not lo <= b[i + 1] <= hi or any(not 0x80 <= b[i + k] <= 0xBF for k in range(2, length)):
                return None, ('BAD_UTF8', i)
        cp = c if length == 1 else c & (0xFF >> (length + 1))
        for k in range(1, length):
            cp = (cp << 6) | (b[i + k] & 0x3F)
        if cp < 0x20 or 0x7F <= cp <= 0x9F:
            return None, ('CONTROL_CHAR', i)
        cps.append(cp)
        i += length
    return cps, None


# ---- the surface byte layout: 28 §5.7 ----------------------------------------------------------

def to_bytes(fmt, vals):
    if fmt == INDEX8:
        return bytes(vals)
    return b''.join(struct.pack('<H', v) for v in vals)


def from_bytes(fmt, data):
    if fmt == INDEX8:
        return list(data)
    return [struct.unpack_from('<H', data, 2 * i)[0] for i in range(len(data) // 2)]


# ---- errors ------------------------------------------------------------------------------------

class FileError(Exception):
    def __init__(self, line, msg):
        Exception.__init__(self, 'line %d: %s' % (line, msg))


class CaseFail(Exception):
    pass


class ModelError(Exception):
    pass


# ---- the device: live view, allocator, oracle state -------------------------------------------

def fill_ranges(W, H, x, y, w, h):
    return max(x, 0), min(x + w, W), max(y, 0), min(y + h, H)


def blit_ranges(W, H, TW, TH, sx, sy, w, h, dx, dy):
    i0, i1 = max(0, -sx, -dx), min(w, TW - sx, W - dx)
    j0, j1 = max(0, -sy, -dy), min(h, TH - sy, H - dy)
    return i0, i1, j0, j1


class Device:
    def __init__(self, fmt, W, H):
        self.fmt, self.W, self.H = fmt, W, H
        self.S = [0] * (W * H)                   # 28 §5.7: zero at start
        self.T = {}                              # full handle -> [w, h, texels]
        self.queue = deque(range(2048))          # 28 §6.1: FIFO free queue, slots 0-2047 in order
        self.gen = [0] * 2048

    # -- resource records (28 §6.2-§6.4, §2.2 O6) --
    def create(self, handle, w, h, line):
        if not (1 <= w <= 1024 and 1 <= h <= 1024):
            raise CaseFail('line %d: createTexture: invalid texture size %dx%d' % (line, w, h))
        if len(self.T) >= 256:
            raise CaseFail('line %d: createTexture: too many textures' % line)
        slot = self.queue.popleft()
        given = make_handle(1, slot, self.gen[slot])
        if handle != given:
            raise CaseFail('line %d: texture 0x%08X, but the allocator gives 0x%08X (28 §6.1)' % (line, handle, given))
        self.T[handle] = [w, h, [0] * (w * h)]  # 28 §6.2: contents all zeros

    def live_or_fail(self, handle, line, what):
        err = self.handle_error(handle)
        if err:
            raise CaseFail('line %d: %s: %s' % (line, what, err))

    def handle_error(self, handle):
        if handle == 0 or handle >= 1 << 31 or handle_type(handle) == 0:
            return 'invalid handle 0x%08X (BAD_HANDLE)' % handle
        t = handle_type(handle)
        if t >= 8:
            return 'handle 0x%08X has an unknown type (UNKNOWN_TYPE)' % handle
        if t != 1:
            return 'handle 0x%08X is a %s, expected a texture (WRONG_TYPE)' % (handle, KIND[t])
        if handle not in self.T:
            return 'handle 0x%08X is not live (STALE_HANDLE)' % handle
        return None

    def update(self, handle, x, y, w, h, data, line):
        self.live_or_fail(handle, line, 'updateTexture')
        tw, th, tex = self.T[handle]
        if not (0 <= x <= tw and 1 <= w <= tw - x and 0 <= y <= th and 1 <= h <= th - y):
            raise CaseFail('line %d: updateTexture: invalid texture region' % line)
        if len(data) != w * h * BPP[self.fmt]:
            raise CaseFail('line %d: updateTexture: data must be %d bytes' % (line, w * h * BPP[self.fmt]))
        vals = from_bytes(self.fmt, data)
        for r in range(h):                       # 28 §6.3: rows top first, w texels each
            for c in range(w):
                tex[(y + r) * tw + x + c] = vals[r * w + c]

    def free(self, handle, line):
        self.live_or_fail(handle, line, 'free')
        del self.T[handle]
        self.release_slot(handle)

    def release_slot(self, handle):
        slot = handle_slot(handle)
        self.gen[slot] = (self.gen[slot] + 1) & 0xFFFF   # 28 §6.1: bump, wrapping, to the queue's tail
        self.queue.append(slot)

    def reset(self, data, line):
        if len(data) != self.W * self.H * BPP[self.fmt]:
            raise CaseFail('line %d: reset: pixels must be %d bytes' % (line, self.W * self.H * BPP[self.fmt]))
        for handle in sorted(self.T):            # 28 §2.2: every live handle freed with a generation bump
            self.release_slot(handle)
        self.T = {}
        self.S = from_bytes(self.fmt, data)

    # -- validation (28 §3.5, §3.6), positive cases --
    def validate(self, seg, frame):
        W, H, fmt = self.W, self.H, self.fmt
        n = len(seg)

        def reject(off, op, code, why):
            raise CaseFail('segment rejected: %d: %s: %s (%s)' % (off, op, why, code))

        if n > SUBMIT_CAP:
            reject(0, 'submit', 'OVER_SUBMIT_BYTES', '%d bytes exceed the %d-byte cap' % (n, SUBMIT_CAP))
        if frame['bytes'] + n > FRAME_BYTE_CAP:
            reject(0, 'submit', 'OVER_FRAME_BYTES', 'frame byte cap')
        if n < 8:
            reject(0, 'header', 'TRUNCATED_HEADER', 'segment shorter than its header')
        if seg[0:4] != b'OGPU':
            reject(0, 'header', 'BAD_MAGIC', 'bad magic')
        if seg[4] != 1:
            reject(4, 'header', 'BAD_VERSION', 'unsupported command format %d (this mod decodes 1)' % seg[4])
        if seg[5] != 0:
            reject(5, 'header', 'BAD_FLAGS', 'header flags 0x%02X' % seg[5])
        if seg[6] or seg[7]:
            reject(6, 'header', 'BAD_RESERVED', 'reserved field not 0')
        records, fill, ops, p = frame['records'], dict(frame['fill']), [], 8
        while True:
            if p == n:
                reject(p, 'END', 'MISSING_END', 'no END before the segment ends')
            op = seg[p]
            if op == OP_END:
                if p + 1 < n:
                    reject(p, 'END', 'TRAILING_BYTES', 'END is not the last byte (%d after it)' % (n - p - 1))
                break
            if op not in FIXED:
                reject(p, '0x%02X' % op, 'UNKNOWN_OPCODE', 'unknown opcode')
            name, size = NAME[op], FIXED[op]
            if p + size > n:
                reject(p, name, 'TRUNCATED_RECORD', 'record needs %d bytes, only %d left' % (size, n - p))
            if op == OP_TEXT:
                (tn,) = struct.unpack_from('<H', seg, p + 15)
                if tn > TEXT_MAX:
                    reject(p, name, 'TEXT_TOO_LONG', '%d bytes' % tn)
                size = 17 + tn
                if p + size > n:
                    reject(p, name, 'TRUNCATED_RECORD', 'record needs %d bytes, only %d left' % (size, n - p))
            if op == OP_CLEAR:
                (c,) = struct.unpack_from('<I', seg, p + 1)
                if not colour_valid(fmt, c):
                    reject(p, name, 'BAD_COLOUR', 'colour 0x%08X' % c)
                area = W * H
                ops.append((op, c))
            elif op == OP_FILL:
                x, y, w, h, c = struct.unpack_from('<hhhhI', seg, p + 1)
                if not colour_valid(fmt, c):
                    reject(p, name, 'BAD_COLOUR', 'colour 0x%08X' % c)
                x0, x1, y0, y1 = fill_ranges(W, H, x, y, w, h)
                area = max(0, x1 - x0) * max(0, y1 - y0)
                ops.append((op, x, y, w, h, c))
            elif op == OP_BLIT:
                tex, sx, sy, w, h, dx, dy, flags = struct.unpack_from('<IhhhhhhB', seg, p + 1)
                err = self.handle_error(tex)
                if err:
                    cut = err.rindex(' (')
                    reject(p, name, err[cut + 2:-1], err[:cut])
                # WRONG_FORMAT: textures are always in the surface format in M0 (28 §6.2)
                if flags & 0xFE:
                    reject(p, name, 'BAD_FLAGS_FIELD', 'flags 0x%02X' % flags)
                TW, TH = self.T[tex][0], self.T[tex][1]
                i0, i1, j0, j1 = blit_ranges(W, H, TW, TH, sx, sy, w, h, dx, dy)
                area = max(0, i1 - i0) * max(0, j1 - j0)
                ops.append((op, tex, sx, sy, w, h, dx, dy, flags))
            else:
                x, y, fg, bg, flags, font, tn = struct.unpack_from('<hhIIBBH', seg, p + 1)
                if not colour_valid(fmt, fg):
                    reject(p, name, 'BAD_COLOUR', 'fg 0x%08X' % fg)
                if not colour_valid(fmt, bg):
                    reject(p, name, 'BAD_COLOUR', 'bg 0x%08X' % bg)
                if flags & 0xFE:
                    reject(p, name, 'BAD_FLAGS_FIELD', 'flags 0x%02X' % flags)
                if font != 0:
                    reject(p, name, 'UNKNOWN_FONT', 'font %d' % font)
                cps, err = utf8_scan(seg[p + 17:p + 17 + tn])
                if err:
                    reject(p, name, err[0], 'at string byte %d' % err[1])
                area = CELL_FILL * len(cps)      # 28 §3.5, §7.3: per codepoint, unclipped
                ops.append((op, x, y, fg, bg, flags, cps))
            records += 1
            if records > FRAME_RECORD_CAP:
                reject(p, name, 'OVER_FRAME_RECORDS', 'record cap')
            fill[CLASS[op]] += area              # the fill cap is Long.MAX_VALUE here
            ops[-1] = (ops[-1], area)
            p += size
        frame['bytes'] += n
        frame['records'] = records
        frame['fill'] = fill
        return ops

    # -- the oracle (28 §5.3-§5.7), each draw twice --
    def run(self, op, area):
        code = op[0]
        if code == OP_CLEAR:
            v = sv(self.fmt, op[1])
            self.S = [v] * (self.W * self.H)
            return
        a, b = list(self.S), list(self.S)
        if code == OP_FILL:
            na, nb = self.fill_ranged(a, *op[1:]), self.fill_member(b, *op[1:])
        elif code == OP_BLIT:
            na, nb = self.blit_ranged(a, *op[1:]), self.blit_member(b, *op[1:])
        else:
            self.text_ranged(a, *op[1:])
            self.text_member(b, *op[1:])
            na = nb = CELL_FILL * len(op[6])     # unclipped, so the same rule on both sides
        if a != b:
            raise ModelError('%s: the ranged and per-pixel forms disagree on pixels' % NAME[code])
        if not (na == nb == area):
            raise ModelError('%s: pixel counts %r, %r against the validator\'s %r' % (NAME[code], na, nb, area))
        self.S = a

    # 28:499
    def fill_ranged(self, S, x, y, w, h, c):
        v, W = sv(self.fmt, c), self.W
        x0, x1, y0, y1 = fill_ranges(W, self.H, x, y, w, h)
        count = 0
        for py in range(y0, y1):
            for px in range(x0, x1):
                S[py * W + px] = v
                count += 1
        return count

    # 28 §5.1: x <= px < x + w and y <= py < y + h, clipped to the surface
    def fill_member(self, S, x, y, w, h, c):
        v, W, count = sv(self.fmt, c), self.W, 0
        for py in range(self.H):
            for px in range(W):
                if x <= px < x + w and y <= py < y + h:
                    S[py * W + px] = v
                    count += 1
        return count

    # 28:500-501
    def blit_ranged(self, S, tex, sx, sy, w, h, dx, dy, flags):
        TW, TH, texels = self.T[tex]
        W = self.W
        i0, i1, j0, j1 = blit_ranges(W, self.H, TW, TH, sx, sy, w, h, dx, dy)
        count = 0
        for j in range(j0, j1):
            for i in range(i0, i1):
                count += 1
                v = texels[(sy + j) * TW + sx + i]
                if not (flags & KEY0 and v == 0):
                    S[(dy + j) * W + dx + i] = v
        return count

    # 28 §5.5: dest (dx + i, dy + j) for 0 <= i < w, 0 <= j < h, unless the texel is outside the texture,
    # the pixel is outside the surface, or KEY0 is set and the texel is 0; walked per surface pixel
    def blit_member(self, S, tex, sx, sy, w, h, dx, dy, flags):
        TW, TH, texels = self.T[tex]
        W, count = self.W, 0
        for py in range(self.H):
            for px in range(W):
                i, j = px - dx, py - dy
                if not (0 <= i < w and 0 <= j < h):
                    continue
                tx, ty = sx + i, sy + j
                if not (0 <= tx < TW and 0 <= ty < TH):
                    continue
                count += 1
                v = texels[ty * TW + tx]
                if flags & KEY0 and v == 0:
                    continue
                S[py * W + px] = v
        return count

    # 28:502-505
    def text_ranged(self, S, x, y, fg, bg, flags, cps):
        fv, bv, W, H = sv(self.fmt, fg), sv(self.fmt, bg), self.W, self.H
        for k, cp in enumerate(cps):
            g = glyph(cp)
            for r in range(CELL_H):
                for b in range(CELL_W):
                    px, py = x + CELL_W * k + b, y + r
                    if 0 <= px < W and 0 <= py < H:
                        if glyph_bit(g, r, b):
                            S[py * W + px] = fv
                        elif not flags & TRANSPARENT_BG:
                            S[py * W + px] = bv
        return None

    # 28 §5.6: codepoint k fills [x + 6k, x + 6k + 6) x [y, y + 13), clipped, never wrapping; per pixel
    def text_member(self, S, x, y, fg, bg, flags, cps):
        fv, bv, W = sv(self.fmt, fg), sv(self.fmt, bg), self.W
        for py in range(self.H):
            for px in range(W):
                rel, r = px - x, py - y
                if not (0 <= r < CELL_H and 0 <= rel < CELL_W * len(cps)):
                    continue
                k, b = divmod(rel, CELL_W)
                if glyph_bit(glyph(cps[k]), r, b):
                    S[py * W + px] = fv
                elif not flags & TRANSPARENT_BG:
                    S[py * W + px] = bv
        return None


# ---- the golden file -----------------------------------------------------------------------------

HEXCH = '0123456789abcdefABCDEF'


def parse_int(tok, line):
    s, neg = tok, False
    if s.startswith('-'):
        neg, s = True, s[1:]
    if s[:2] in ('0x', '0X'):
        d = s[2:]
        if not d or any(ch not in HEXCH for ch in d):
            raise FileError(line, 'not a number: %r' % tok)
        v = int(d, 16)
    else:
        if not s or any(ch not in '0123456789' for ch in s):
            raise FileError(line, 'not a number: %r' % tok)
        v = int(s, 10)
    return -v if neg else v


def ranged(v, lo, hi, what, line):
    if not lo <= v <= hi:
        raise FileError(line, '%s %d is outside %d..%d' % (what, v, lo, hi))
    return v


def i16(tok, line, what):
    return ranged(parse_int(tok, line), -32768, 32767, what + ' (i16)', line)


def u8(tok, line, what):
    return ranged(parse_int(tok, line), 0, 255, what + ' (u8)', line)


def u32(tok, line, what):
    return ranged(parse_int(tok, line), 0, 0xFFFFFFFF, what + ' (u32)', line)


def parse_size(tok, line):
    parts = tok.split('x')
    if len(parts) != 2 or not all(p and all(ch in '0123456789' for ch in p) for p in parts):
        raise FileError(line, 'not a <w>x<h> size: %r' % tok)
    return int(parts[0]), int(parts[1])


def parse_string(rest, line):
    """rest starts at the opening quote; returns the string's UTF-8 bytes (surrogates kept, so they fail)."""
    if not rest.startswith('"'):
        raise FileError(line, 'text needs a quoted string')
    out, i = [], 1
    while True:
        if i >= len(rest):
            raise FileError(line, 'unterminated string')
        ch = rest[i]
        if ch == '"':
            break
        if ch == '\\':
            e = rest[i + 1:i + 2]
            if e in ('"', '\\'):
                out.append(e)
                i += 2
            elif e == 'u' and len(rest) >= i + 6 and all(c in HEXCH for c in rest[i + 2:i + 6]):
                out.append(chr(int(rest[i + 2:i + 6], 16)))
                i += 6
            elif e == 'U' and len(rest) >= i + 10 and rest[i + 2:i + 4] == '00' and all(c in HEXCH for c in rest[i + 4:i + 10]):
                v = int(rest[i + 2:i + 10], 16)
                if v > 0x10FFFF:
                    raise FileError(line, 'escape above U+10FFFF')
                out.append(chr(v))
                i += 10
            else:
                raise FileError(line, 'bad escape in string')
        else:
            out.append(ch)
            i += 1
    if rest[i + 1:].strip():
        raise FileError(line, 'text after the string')
    return ''.join(out).encode('utf-8', 'surrogatepass')


def parse_op(toks, s, line):
    kw = toks[0]
    if kw == 'clear':
        if len(toks) != 2:
            raise FileError(line, 'clear <c>')
        return enc_clear(u32(toks[1], line, 'c'))
    if kw == 'fill':
        if len(toks) != 6:
            raise FileError(line, 'fill <x> <y> <w> <h> <c>')
        x, y, w, h = (i16(t, line, n) for t, n in zip(toks[1:5], 'xywh'))
        return enc_fill(x, y, w, h, u32(toks[5], line, 'c'))
    if kw == 'blit':
        if len(toks) != 9:
            raise FileError(line, 'blit <tex> <sx> <sy> <w> <h> <dx> <dy> <flags>')
        tex = u32(toks[1], line, 'tex')
        sx, sy, w, h, dx, dy = (i16(t, line, n) for t, n in zip(toks[2:8], ('sx', 'sy', 'w', 'h', 'dx', 'dy')))
        return enc_blit(tex, sx, sy, w, h, dx, dy, u8(toks[8], line, 'flags'))
    if kw == 'text':
        q_at = s.find('"')
        if q_at < 0:
            raise FileError(line, 'text <x> <y> <fg> <bg> <flags> <font> "<string>"')
        head = s[:q_at].split()
        if len(head) != 7:
            raise FileError(line, 'text <x> <y> <fg> <bg> <flags> <font> "<string>"')
        data = parse_string(s[q_at:], line)
        if len(data) > 65535:
            raise FileError(line, 'string longer than a u16 count')
        return enc_text(i16(head[1], line, 'x'), i16(head[2], line, 'y'), u32(head[3], line, 'fg'),
                        u32(head[4], line, 'bg'), u8(head[5], line, 'flags'), u8(head[6], line, 'font'), data)
    # hex
    out = []
    for t in toks[1:]:
        if t[:2] in ('0x', '0X'):
            out.append(u8(t, line, 'byte'))
        elif len(t) == 2 and all(c in HEXCH for c in t):
            out.append(int(t, 16))
        else:
            raise FileError(line, 'hex bytes are two hex digits or 0x-prefixed: %r' % t)
    if not out:
        raise FileError(line, 'hex needs bytes')
    return bytes(out)


def parse_row(text, width, case, line):
    fmt = case['fmt']
    if case['chars'] is not None:
        if len(text) != width:
            raise FileError(line, 'row has %d characters, expected %d' % (len(text), width))
        try:
            return [case['chars'][ch] for ch in text]
        except KeyError as e:
            raise FileError(line, 'character %s is not in chars' % e)
    toks = text.split()
    if len(toks) != width:
        raise FileError(line, 'row has %d tokens, expected %d' % (len(toks), width))
    nd = DIGITS[fmt]
    for t in toks:
        if len(t) != nd or any(c not in HEXCH for c in t):
            raise FileError(line, 'row token %r is not %d hex digits' % (t, nd))
    return [int(t, 16) for t in toks]


STEP_WORDS = ('texture', 'update', 'free', 'reset', 'frame', 'totals', 'expect', 'end')
OP_WORDS = ('clear', 'fill', 'blit', 'text', 'hex')


def parse(path):
    with open(path, encoding='utf-8') as f:
        lines = f.read().splitlines()
    cases, names = [], set()
    seen_glyphs, case, pending, frame, segment = False, None, None, None, None
    for ln, raw in enumerate(lines, 1):
        s = raw.strip()
        if not s:
            continue
        if pending is not None:
            pending['rows'].append(parse_row(s, pending['width'], case, ln))
            if len(pending['rows']) == pending['n']:
                pending = None
            continue
        if s.startswith('#'):
            if case is not None:
                case['comments'] += 1
            continue
        toks = s.split()
        kw = toks[0]
        if not seen_glyphs:
            if toks != GLYPHS_DIRECTIVE:
                raise FileError(ln, "the first directive must be 'glyphs synthetic-6x13 28:633' (amendment C, finding 17)")
            seen_glyphs = True
            continue
        if case is None:
            if kw != 'case':
                raise FileError(ln, 'unknown or misplaced directive %r outside a case' % kw)
            if len(toks) != 2 or not toks[1] or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in toks[1]):
                raise FileError(ln, 'case <name> with name [a-z0-9-]+')
            if toks[1] in names:
                raise FileError(ln, 'duplicate case name %r' % toks[1])
            names.add(toks[1])
            case = dict(name=toks[1], line=ln, fmt=None, W=0, H=0, chars=None, steps=[], comments=0)
            continue
        if case['fmt'] is None:
            if kw != 'surface' or len(toks) != 3 or toks[1] not in FORMATS:
                raise FileError(ln, 'a case starts with surface <index8|rgb565> <W>x<H>')
            case['fmt'] = FORMATS[toks[1]]
            case['W'], case['H'] = parse_size(toks[2], ln)
            if not (1 <= case['W'] and 1 <= case['H']):
                raise FileError(ln, 'empty surface')
            continue
        if kw == 'chars':
            if case['steps'] or case['chars'] is not None or len(toks) < 2:
                raise FileError(ln, 'chars comes once, right after surface, with at least one mapping')
            m = {}
            for t in toks[1:]:
                if len(t) < 3 or t[1] != '=':
                    raise FileError(ln, 'chars mapping %r is not <c>=<hex>' % t)
                hx = t[2:]
                v = parse_int(hx if hx[:2] in ('0x', '0X') else '0x' + hx, ln)
                if t[0] in m or v > MAXVAL[case['fmt']]:
                    raise FileError(ln, 'chars mapping %r repeats a character or exceeds the format' % t)
                m[t[0]] = v
            case['chars'] = m
            continue
        if kw in OP_WORDS:
            if segment is None:
                raise FileError(ln, '%s outside a segment' % kw)
            segment['ops'].append(parse_op(toks, s, ln))
            continue
        if kw == 'segment':
            if frame is None or len(toks) != 1:
                raise FileError(ln, 'segment outside a frame')
            segment = dict(line=ln, ops=[])
            frame['segments'].append(segment)
            continue
        if kw not in STEP_WORDS:
            raise FileError(ln, 'unknown directive %r' % kw)
        frame = segment = None
        W, H = case['W'], case['H']
        if kw == 'end':
            if len(toks) != 1:
                raise FileError(ln, 'end takes nothing')
            cases.append(case)
            case = None
        elif kw == 'frame':
            if len(toks) != 1:
                raise FileError(ln, 'frame takes nothing')
            frame = dict(kind='frame', line=ln, segments=[])
            case['steps'].append(frame)
        elif kw == 'texture':
            if len(toks) not in (3, 4) or (len(toks) == 4 and toks[3] != 'zeros'):
                raise FileError(ln, 'texture <handle> <w>x<h> [zeros]')
            w, h = parse_size(toks[2], ln)
            step = dict(kind='texture', line=ln, handle=u32(toks[1], ln, 'handle'), w=w, h=h, rows=[])
            case['steps'].append(step)
            if len(toks) == 3:
                pending = dict(n=h, width=w, rows=step['rows'])
        elif kw == 'update':
            if len(toks) != 5:
                raise FileError(ln, 'update <handle> <x> <y> <w>x<h>')
            w, h = parse_size(toks[4], ln)
            step = dict(kind='update', line=ln, handle=u32(toks[1], ln, 'handle'), x=parse_int(toks[2], ln),
                        y=parse_int(toks[3], ln), w=w, h=h, rows=[])
            case['steps'].append(step)
            if h > 0:
                pending = dict(n=h, width=w, rows=step['rows'])
        elif kw == 'free':
            if len(toks) != 2:
                raise FileError(ln, 'free <handle>')
            case['steps'].append(dict(kind='free', line=ln, handle=u32(toks[1], ln, 'handle')))
        elif kw in ('reset', 'expect'):
            if len(toks) != 1:
                raise FileError(ln, '%s takes nothing; its %d rows follow' % (kw, H))
            step = dict(kind=kw, line=ln, rows=[])
            case['steps'].append(step)
            pending = dict(n=H, width=W, rows=step['rows'])
        elif kw == 'totals':
            want = {}
            for t in toks[1:]:
                k, _, v = t.partition('=')
                if k not in ('records',) + CLASSES or k in want or not v:
                    raise FileError(ln, 'totals records=<n> clear=<n> fill=<n> copy=<n> set=<n>')
                want[k] = parse_int(v, ln)
            if len(want) != 5:
                raise FileError(ln, 'totals needs all of records, clear, fill, copy and set')
            case['steps'].append(dict(kind='totals', line=ln, want=want))
    if pending is not None:
        raise FileError(len(lines), 'the file ends inside a row block')
    if case is not None:
        raise FileError(case['line'], 'case %r has no end' % case['name'])
    if not seen_glyphs:
        raise FileError(1, "no 'glyphs synthetic-6x13 28:633' directive")
    return cases


# ---- running a case ------------------------------------------------------------------------------

def fmt_rows(case, vals):
    W, H, fmt = case['W'], case['H'], case['fmt']
    inv = {}
    if case['chars'] is not None:
        for ch, v in case['chars'].items():
            inv.setdefault(v, ch)
    out = []
    for y in range(H):
        row = vals[y * W:(y + 1) * W]
        if case['chars'] is not None:
            out.append(''.join(inv.get(v, '?') for v in row))
        else:
            out.append(' '.join('%0*X' % (DIGITS[fmt], v) for v in row))
    return out


def unmapped(case, vals):
    if case['chars'] is None:
        return []
    known = set(case['chars'].values())
    return sorted(set(v for v in vals if v not in known))


def totals_line(t):
    return 'totals records=%d clear=%d fill=%d copy=%d set=%d' % (t['records'], t['clear'], t['fill'], t['copy'], t['set'])


def run_case(case, emit):
    fmt, W, H = case['fmt'], case['W'], case['H']
    dev = Device(fmt, W, H)
    fails, out = [], []
    last = None
    expects = 0
    pending_grid = False
    for step in case['steps']:
        k, ln = step['kind'], step['line']
        if k == 'texture':
            dev.create(step['handle'], step['w'], step['h'], ln)
            if step['rows']:                     # a full TEX_UPDATE
                vals = [v for r in step['rows'] for v in r]
                dev.update(step['handle'], 0, 0, step['w'], step['h'], to_bytes(fmt, vals), ln)
        elif k == 'update':
            vals = [v for r in step['rows'] for v in r]
            dev.update(step['handle'], step['x'], step['y'], step['w'], step['h'], to_bytes(fmt, vals), ln)
        elif k == 'free':
            dev.free(step['handle'], ln)
        elif k == 'reset':
            dev.reset(to_bytes(fmt, [v for r in step['rows'] for v in r]), ln)
            pending_grid = True
        elif k == 'frame':
            if not step['segments']:
                raise CaseFail('line %d: a frame needs at least one segment' % ln)
            frame = dict(bytes=0, records=0, fill=dict.fromkeys(CLASSES, 0))
            ops = []
            for seg in step['segments']:
                data = HEADER + b''.join(seg['ops']) + bytes((OP_END,))
                try:
                    ops.extend(dev.validate(data, frame))
                except CaseFail as e:
                    raise CaseFail('line %d: %s' % (seg['line'], e))
            for op, area in ops:
                dev.run(op, area)
            last = dict(records=frame['records'], **frame['fill'])
            pending_grid = True
            if emit:
                out.append('# frame at line %d' % ln)
                out.append(totals_line(last))
        elif k == 'totals':
            if last is None:
                raise CaseFail('line %d: totals before any frame' % ln)
            if step['want'] != last:
                fails.append('line %d: totals differ\n      file:    %s\n      derived: %s' % (ln, totals_line(step['want']), totals_line(last)))
        elif k == 'expect':
            expects += 1
            pending_grid = False
            want = [v for r in step['rows'] for v in r]
            if emit:
                out.append('expect   # line %d' % ln)
                out.extend('  ' + r for r in fmt_rows(case, dev.S))
                miss = unmapped(case, dev.S)
                if miss:
                    out.append('# values not in chars: ' + ' '.join('%0*X' % (DIGITS[fmt], v) for v in miss))
            if want != dev.S:
                diffs = [(i % W, i // W, want[i], dev.S[i]) for i in range(W * H) if want[i] != dev.S[i]]
                lines = ['line %d: expect differs at %d pixel(s)' % (ln, len(diffs)), '      expected:']
                lines += ['        ' + r for r in fmt_rows(case, want)]
                lines += ['      actual:']
                lines += ['        ' + r for r in fmt_rows(case, dev.S)]
                lines += ['      diff (X = differs):']
                for y in range(H):
                    lines.append('        ' + ''.join('X' if want[y * W + x] != dev.S[y * W + x] else '.' for x in range(W)))
                for x, y, e, a in diffs[:16]:
                    lines.append('      (%d,%d): expected %0*X, derived %0*X' % (x, y, DIGITS[fmt], e, DIGITS[fmt], a))
                if len(diffs) > 16:
                    lines.append('      ... %d more' % (len(diffs) - 16))
                fails.append('\n'.join(lines))
    if emit and (pending_grid or expects == 0):
        out.append('expect   # the surface at the case\'s end')
        out.extend('  ' + r for r in fmt_rows(case, dev.S))
        miss = unmapped(case, dev.S)
        if miss:
            out.append('# values not in chars: ' + ' '.join('%0*X' % (DIGITS[fmt], v) for v in miss))
    if expects == 0:
        fails.append('the case has no expect block')
    return fails, out


def main(argv):
    args = list(argv[1:])
    emit = None
    if '--emit' in args:
        i = args.index('--emit')
        if i + 1 >= len(args):
            print('usage: oracle_goldens.py <goldens file> [--emit <case>]')
            return 2
        emit = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        print('usage: oracle_goldens.py <goldens file> [--emit <case>]')
        return 2
    self_check()
    try:
        cases = parse(args[0])
    except FileError as e:
        print('FILE ERROR %s' % e)
        return 2
    if emit is not None:
        sel = [c for c in cases if c['name'] == emit]
        if not sel:
            print('no case %r' % emit)
            return 2
        c = sel[0]
        try:
            fails, out = run_case(c, True)
        except CaseFail as e:
            print('case %s: %s' % (c['name'], e))
            return 1
        print('case %s' % c['name'])
        print('\n'.join(out))
        for f in fails:
            print('# differs from the file: ' + f.splitlines()[0])
        return 0
    failed = 0
    for c in cases:
        try:
            fails, _ = run_case(c, False)
        except CaseFail as e:
            fails = [str(e)]
        if c['comments'] == 0:
            print('WARN %s: no \'#\' line saying why the grid is what it is' % c['name'])
        if fails:
            failed += 1
            print('FAIL %s (line %d)' % (c['name'], c['line']))
            for f in fails:
                print('    ' + f)
        else:
            print('PASS %s' % c['name'])
    if not cases:
        print('no cases')
        return 1
    if failed:
        print('%d of %d cases FAILED' % (failed, len(cases)))
        return 1
    print('ALL PASS (%d cases)' % len(cases))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except ModelError as e:
        print('MODEL ERROR (a bug in this script, not in the file): %s' % e)
        sys.exit(3)
