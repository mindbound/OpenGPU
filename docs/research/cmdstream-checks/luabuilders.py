# Transliterates note 28 §9.2's Lua record builders (floor and floored % as in Lua) and checks them
# against struct-packed layouts, including negative and edge values. TEXT has its font byte (29 F4, 2026-10-15).
import math, struct, random
floor = math.floor
def char(*a):
    assert all(0 <= v <= 255 and v == int(v) for v in a), a
    return bytes(int(v) for v in a)

def clear(c):
    c = floor(c)
    c0 = c % 256; c = floor(c / 256); c1 = c % 256; c = floor(c / 256)
    return char(0x01, c0, c1, c % 256, floor(c / 256))

def fill(x, y, w, h, c):
    x = floor(x) % 65536; y = floor(y) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
    c = floor(c)
    c0 = c % 256; c = floor(c / 256); c1 = c % 256; c = floor(c / 256)
    return char(0x30, x % 256, floor(x / 256), y % 256, floor(y / 256),
                w % 256, floor(w / 256), h % 256, floor(h / 256), c0, c1, c % 256, floor(c / 256))

def blit(tex, sx, sy, w, h, dx, dy, flags):
    tex = floor(tex)
    t0 = tex % 256; tex = floor(tex / 256); t1 = tex % 256; tex = floor(tex / 256)
    sx = floor(sx) % 65536; sy = floor(sy) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
    dx = floor(dx) % 65536; dy = floor(dy) % 65536
    return char(0x31, t0, t1, tex % 256, floor(tex / 256),
                sx % 256, floor(sx / 256), sy % 256, floor(sy / 256), w % 256, floor(w / 256),
                h % 256, floor(h / 256), dx % 256, floor(dx / 256), dy % 256, floor(dy / 256), flags)

def text(x, y, fg, bg, flags, font, s):
    x = floor(x) % 65536; y = floor(y) % 65536; fg = floor(fg); bg = floor(bg); n = len(s)
    f0 = fg % 256; fg = floor(fg / 256); f1 = fg % 256; fg = floor(fg / 256)
    b0 = bg % 256; bg = floor(bg / 256); b1 = bg % 256; bg = floor(bg / 256)
    return char(0x33, x % 256, floor(x / 256), y % 256, floor(y / 256),
                f0, f1, fg % 256, floor(fg / 256), b0, b1, bg % 256, floor(bg / 256),
                flags, font, n % 256, floor(n / 256)) + s

P = lambda fmt, *a: struct.pack('<' + fmt, *a)
rnd = random.Random(28)
i16s = [-32768, -1, 0, 1, 319, 320, 32767]
u32s = [0, 1, 255, 0xFF000000, 0xFFFFFFFF, 0x08000000, 0x7FFFFFFF]
for _ in range(20000):
    x, y, w, h, dx, dy = (rnd.choice(i16s + [rnd.randint(-32768, 32767)]) for _ in range(6))
    c, t = rnd.choice(u32s + [rnd.randint(0, 2**32 - 1)]), rnd.choice(u32s)
    fl, fo = rnd.randint(0, 255), rnd.randint(0, 255)
    assert clear(c) == P('BI', 1, c)
    assert fill(x, y, w, h, c) == P('BhhhhI', 0x30, x, y, w, h, c)
    assert blit(t, x, y, w, h, dx, dy, fl) == P('BIhhhhhhB', 0x31, t, x, y, w, h, dx, dy, fl)
    s = bytes(rnd.randint(0x20, 0x7E) for _ in range(rnd.randint(0, 40)))
    assert text(x, y, c, t, fl, fo, s) == P('BhhIIBBH', 0x33, x, y, c, t, fl, fo, len(s)) + s
frame = b'OGPU\x01\x00\x00\x00' + clear(0) + fill(10, 20, 100, 50, 196) + blit(0x08000000, 0, 0, 16, 16, 150, 40, 1) + text(8, 180, 255, 0, 1, 0, b'Hi') + b'\xff'
print('builders match struct layouts; worked frame', len(frame), 'B:', frame.hex(' ').upper())
