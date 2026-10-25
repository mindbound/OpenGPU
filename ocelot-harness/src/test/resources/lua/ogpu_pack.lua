-- 28 §9.3's string.pack path (28:803-813; S10 plan P8.3; decisions D4): the four records of ogpu_builders.lua through
-- string.pack's little-endian layouts, each written as 28:805 writes fill, for EncoderIdentityTest on 5.3 and 5.4, the
-- runtimes 28 §9.3's selection names; the path must give the char path's bytes for every in-range input (28:813).
-- pack calls parse on 5.2 as plain calls (28:812), but no test loads this file where string.pack is missing. The
-- default alignment is 1, so the layouts have no padding (28:813)
local spack = string.pack
local PACK = {
  clear = function(c) return spack("<BI4", 0x01, c) end,
  fill = function(x, y, w, h, c) return spack("<BhhhhI4", 0x30, x, y, w, h, c) end,
  blit = function(tex, sx, sy, w, h, dx, dy, flags)
    return spack("<BI4hhhhhhB", 0x31, tex, sx, sy, w, h, dx, dy, flags)
  end,
  text = function(x, y, fg, bg, flags, font, s)
    return spack("<BhhI4I4BBH", 0x33, x, y, fg, bg, flags, font, #s) .. s
  end,
  HEADER = HEADER,
  END = END,
  rep = string.rep,
}
