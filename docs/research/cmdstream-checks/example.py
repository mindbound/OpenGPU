# Builds note 28's worked M0 frame with the v1 layouts and prints its bytes, plus a few derived numbers.
# 2026-10-15: TEXT carries a u8 font at offset 14 (29 F4), so its record is 17 + n B.
import struct

def u8(v): return struct.pack('<B', v)
def i16(v): return struct.pack('<h', v)
def u16(v): return struct.pack('<H', v)
def u32(v): return struct.pack('<I', v)

def handle(t, s, g): return (t << 27) | (s << 16) | g

HEADER = b'OGPU' + u8(1) + u8(0) + u16(0)
def clear(c): return u8(0x01) + u32(c)
def fill(x, y, w, h, c): return u8(0x30) + i16(x) + i16(y) + i16(w) + i16(h) + u32(c)
def blit(t, sx, sy, w, h, dx, dy, fl): return u8(0x31) + u32(t) + i16(sx) + i16(sy) + i16(w) + i16(h) + i16(dx) + i16(dy) + u8(fl)
def text(x, y, fg, bg, fl, font, s):
    b = s.encode('utf-8')
    return u8(0x33) + i16(x) + i16(y) + u32(fg) + u32(bg) + u8(fl) + u8(font) + u16(len(b)) + b
END = u8(0xFF)

tex = handle(1, 0, 0)
recs = [('header', HEADER), ('CLEAR', clear(0)), ('FILL_RECT', fill(10, 20, 100, 50, 196)),
        ('BLIT', blit(tex, 0, 0, 16, 16, 150, 40, 1)), ('TEXT', text(8, 180, 255, 0, 1, 0, 'Hi')), ('END', END)]
off = 0
for name, b in recs:
    print('%-9s @%3d %2d B: %s' % (name, off, len(b), ' '.join('%02X' % x for x in b)))
    off += len(b)
print('total', off, 'bytes; texture handle 0x%08X' % tex)
print('max handle 0x%08X' % handle(15, 2047, 65535))

# 565 quantize of the T3 variant's colours
def q(c, m): return (c * m + 127) // 255
def p565(argb): r, g, b = (argb >> 16) & 255, (argb >> 8) & 255, argb & 255; return (q(r, 31) << 11) | (q(g, 63) << 5) | q(b, 31)
for c in (0xFF000000, 0xFFFF8000, 0xFFFFFFFF, 0xFF336699):
    print('q565(0x%08X) = 0x%04X' % (c, p565(c)))

# budget arithmetic used in section 8
for name, n in (('64 KB', 64 * 1024), ('128 KB', 128 * 1024), ('256 KB', 256 * 1024)):
    print('submit %s: len/1MB = %.4f' % (name, n / (1024 * 1024)))
for name, n in (('16x16 idx8', 256), ('64x64 565', 8192), ('640x400 565', 512000), ('1024x1024 565', 2 * 1024 * 1024)):
    print('upload %s: %d B, len/256KB = %.4f' % (name, n, n / (256 * 1024)))
# fill caps
for t, (w, h) in (('T1', (160, 100)), ('T2', (320, 200)), ('T3', (640, 400))):
    print(t, 'surface px', w * h, '16x =', 16 * w * h, 'readback bytes', w * h * (2 if t == 'T3' else 1))
