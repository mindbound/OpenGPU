-- 28 §9.2's reference record builders, verbatim (28:760-793 at HEAD 1d143c4): clear, fill, blit and text, with HEADER
-- and END. The harness's encoder is this block; BuildersVerbatimTest compares everything after these comment lines with
-- the Lua code block under 28's "9.2 Reference record builders" heading, so an edit to either fails the test.

local char, floor, concat = string.char, math.floor, table.concat
local HEADER, END = "OGPU\1\0\0\0", "\255"

local function clear(c)
  c = floor(c)
  local c0 = c % 256; c = floor(c / 256); local c1 = c % 256; c = floor(c / 256)
  return char(0x01, c0, c1, c % 256, floor(c / 256))
end

local function fill(x, y, w, h, c)
  x = floor(x) % 65536; y = floor(y) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
  c = floor(c)
  local c0 = c % 256; c = floor(c / 256); local c1 = c % 256; c = floor(c / 256)
  return char(0x30, x % 256, floor(x / 256), y % 256, floor(y / 256),
              w % 256, floor(w / 256), h % 256, floor(h / 256), c0, c1, c % 256, floor(c / 256))
end

local function blit(tex, sx, sy, w, h, dx, dy, flags)
  tex = floor(tex); flags = floor(flags) % 256
  local t0 = tex % 256; tex = floor(tex / 256); local t1 = tex % 256; tex = floor(tex / 256)
  sx = floor(sx) % 65536; sy = floor(sy) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
  dx = floor(dx) % 65536; dy = floor(dy) % 65536
  return char(0x31, t0, t1, tex % 256, floor(tex / 256), sx % 256, floor(sx / 256), sy % 256, floor(sy / 256),
              w % 256, floor(w / 256), h % 256, floor(h / 256), dx % 256, floor(dx / 256), dy % 256, floor(dy / 256), flags)
end

local function text(x, y, fg, bg, flags, font, s)
  x = floor(x) % 65536; y = floor(y) % 65536; fg = floor(fg); bg = floor(bg); flags = floor(flags) % 256; font = floor(font) % 256
  local n = #s
  local f0 = fg % 256; fg = floor(fg / 256); local f1 = fg % 256; fg = floor(fg / 256)
  local b0 = bg % 256; bg = floor(bg / 256); local b1 = bg % 256; bg = floor(bg / 256)
  return char(0x33, x % 256, floor(x / 256), y % 256, floor(y / 256), f0, f1, fg % 256, floor(fg / 256),
              b0, b1, bg % 256, floor(bg / 256), flags, font, n % 256, floor(n / 256)) .. s
end
