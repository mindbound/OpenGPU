# Checks 24 §3.7 / 26 R8's 565 quantize q=(c*m+127)//255 against bit-replication expand.
import struct
def q(c, m): return (c * m + 127) // 255
def e5(v): return (v << 3) | (v >> 2)
def e6(v): return (v << 2) | (v >> 4)
ok5 = all(q(e5(v), 31) == v for v in range(32))
ok6 = all(q(e6(v), 63) == v for v in range(64))
print("round-trip 5-bit:", ok5, " 6-bit:", ok6)
# Is q monotone and does it equal round-to-nearest of c*m/255 (ties?)
import fractions
ties = [(c, m) for m in (31, 63) for c in range(256) if (c * m) % 255 * 2 == 255]
print("exact .5 ties:", ties)
rn = all(q(c, m) == int(fractions.Fraction(c * m, 255) + fractions.Fraction(1, 2)) for m in (31, 63) for c in range(256))
print("q == floor(c*m/255 + 1/2) for all c:", rn)
# Does expand(q(c)) differ from naive scaling round(v*255/m)?
d5 = [v for v in range(32) if e5(v) != round(v * 255 / 31)]
d6 = [v for v in range(64) if e6(v) != round(v * 255 / 63)]
print("bit-replication != round(v*255/m): 5-bit", d5, " 6-bit", d6)
# float path: unorm conversion of 5-bit 565 texel v -> f = v/31 (GL 2.3 eq) then to 8-bit round(f*255)
# GL_R8 index recovery: floor((i/255)*255 + 0.5) in float32
def f32(x): return struct.unpack('<f', struct.pack('<f', x))[0]
okr8 = all(int(f32(f32(f32(i / 255.0) * 255.0) + 0.5) // 1) == i for i in range(256))
print("GL_R8 index recovery exact (float32 model):", okr8)
# packing
def pack(r, g, b): return (q(r, 31) << 11) | (q(g, 63) << 5) | q(b, 31)
print("pack(255,255,255)=%04X pack(0,0,0)=%04X pack(255,0,0)=%04X" % (pack(255,255,255), pack(0,0,0), pack(255,0,0)))
# OC default 8-bit palette index 0 (PackedColor.scala:136-141)
print("OC hybrid palette greys:", [hex(0xFF * (i + 1) // 17) for i in range(16)][:3], "...")
