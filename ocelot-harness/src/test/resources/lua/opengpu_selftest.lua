-- OpenGPU's M0 self-test (S13 decisions D1, option (b); RM:40; 23 §5.1 cases 1 and 2): a program on the card's public
-- API alone. It runs under OpenOS in game, and in :ocelot-harness through selftest_run.lua, where SelfTestOracleTest
-- and SelfTestGlTest gate it in CI. For each OpenGPU card, in address order, it:
--  - draws 28 §9.4's worked frame plus three records, with a 16 x 16 sprite whose 12 x 12 middle it updates, and frees
--    the sprite while the frame is open (S13 plan P3.3-P3.5);
--  - presents the frame, waits for its "done" and reads the whole surface back in chunks under maxReadbackBytes, T2 in
--    2 and T3 in 8 (P3.6, P3.7);
--  - compares each chunk's Adler-32 with EXPECT, which SelfTestOracleTest derives from the tier-E oracle (P3.8, P4);
--  - sweeps every callback's results as OC converts them: value counts, types and number kinds (P5; S13 amendments
--    F3, F6, I2, I8).
-- Then it prints "opengpu-selftest PASS", or "opengpu-selftest FAIL <k>" with k the number of failed checks. It writes
-- no file and prints no time (amendments F1, F5). 28 §9.1's rules apply here as on the harness's own Lua
-- (LuaPortabilityTest).
--
-- Usage: opengpu_selftest.lua [bind <display> [<display>]]
-- Each <display> is a unique prefix of an opengpu_screen's address, and no two name the same display. Card k, in
-- address order, binds to the k-th one named, so its picture can be looked at; that display then remembers the card
-- (S11 decisions D15). With no display named, nothing is bound (amendments F7).
local component = component or require("component")
local computer = computer or require("computer")
local print = print
if not print then error("opengpu_selftest.lua prints: run it under OpenOS or through selftest_run.lua", 0) end

-- BEGIN 28 §9.2's reference record builders, verbatim (BuildersVerbatimTest.theSelfTestsBuildersAre28sBlock)
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
-- END 28 §9.2's reference record builders

-- BEGIN expected digests: per surface format and size, the Adler-32 of each readPixels chunk of the tier-E oracle's
-- image of this program's frame, top chunk first, as SelfTestOracleTest derives them on every build (S13 plan P4.3,
-- P4.4). After a deliberate change to the frame, that test's failure message gives these lines; they are copied here
-- by hand
local EXPECT = {}
EXPECT["index8 320x200"] = { 0xBA78F81B, 0xB7B46B19 }
EXPECT["rgb565 640x400"] = { 0xE4EF6B6B, 0x9B455538, 0x02CC106B, 0x7BC1317A, 0x02CC106B, 0x02CC106B, 0x02CC106B,
  0xADBF2686 }
-- END expected digests

local byte, sub, mathType = string.byte, string.sub, math.type

-- The frame's operands by surface format, never by tier (amendments I3): index8 colours on T2, and on T3 rgb565 ones
-- with alpha 0xFF (28:842) and channels other than 0x00 and 0xFF, so the device's rounding q is used (28:495). The
-- clear colour is none of the others and not 0, a new device's cleared surface (24:129; 28:1127), so the CLEAR, the
-- opaque BLIT's zero texels and border, KEY0's skipped texels and "Hi"'s TRANSPARENT_BG each change bytes (S13 review
-- tests-2)
local COLOURS = {
  index8 = { clear = 74, fill = 196, corner = 46, fg = 255, bg = 16, hi = 255, hiBg = 0 },
  rgb565 = { clear = 0xFF603060, fill = 0xFFFF8000, corner = 0xFF3366CC, fg = 0xFFE0C0A0, bg = 0xFF102030,
             hi = 0xFFFFFFFF, hiBg = 0xFF000000 },
}
local BYTES_PER_PIXEL = { index8 = 1, rgb565 = 2 }
-- The sprite's texel step: index 60 on index8, 565 value 0x4A69 on rgb565 (P3.5)
local SPRITE_STEP = { index8 = 60, rgb565 = 0x4A69 }

-- getCaps' 15 keys from Device (28:675-692), and per tier the values of tier, width, height, format and
-- maxReadbackBytes that 28 §8.1 documents
local CORE_KEYS = { "tier", "width", "height", "format", "commandFormat", "maxSubmitBytes", "maxFrameBytes",
  "maxFrameRecords", "maxFrameFill", "maxTextBytes", "maxUnconfirmedFrames", "maxTextureSize", "maxTextures",
  "vramBytes", "maxReadbackBytes" }
local TIERS = {
  [2] = { width = 320, height = 200, format = "index8", maxReadbackBytes = 32768 },
  [3] = { width = 640, height = 400, format = "rgb565", maxReadbackBytes = 65536 },
}

-- The M0 callbacks in 28:93-102's order, and the result shapes the sweep covers: the six of CallResult.toLua() as OC
-- converts them, an argument error and the opengpu_frame signal (P5.4). The sweep line names them in this order
local CALLBACKS = { "bind", "getCaps", "submit", "present", "readPixels", "createTexture", "updateTexture", "free" }
local SHAPES = { "map", "{TRUE}", "{Integer}", "{Long}", "{byte[],Long}", "{null,String}", "argument-error", "signal" }

-- The kind of number OC's pushValue gives integral results on this architecture, chosen by its name and never by a
-- probe (P3.12; 28:803-809): an integer subtype on 5.3 and 5.4, an integral number on 5.2, LuaJ and LuaJIT, and on
-- any other a number whose kind is not checked
local ARCH = computer.getArchitecture()
local KIND, KIND_NAME = "unchecked", "number"
if ARCH == "Lua 5.3" or ARCH == "Lua 5.4" then
  KIND, KIND_NAME = "integer", "integer"
elseif ARCH == "Lua 5.2" or ARCH == "LuaJ" or ARCH == "LuaJIT" then
  KIND, KIND_NAME = "integral", "integral number"
end

local function ofKind(v)
  if type(v) ~= "number" then return false end
  if KIND == "integer" then return mathType ~= nil and mathType(v) == "integer" end
  if KIND == "integral" then return v == floor(v) end
  return true
end

-- An integral number as digits on every runtime (5.3's tostring gives a float "2.0"), anything else by tostring
local function num(v)
  if type(v) == "number" and v == floor(v) then return tostring(floor(v)) end
  return tostring(v)
end

-- "0x" and 8 upper-case hex digits of 0 <= v < 2^32, by arithmetic, with no %x or %d (28:742)
local HEX = "0123456789ABCDEF"
local function hex8(v)
  local digits = {}
  for k = 8, 1, -1 do
    local d = v % 16
    digits[k] = sub(HEX, d + 1, d + 1)
    v = floor(v / 16)
  end
  return "0x" .. concat(digits)
end

local function short(address)
  return sub(address, 1, 8)
end

local function kindOf(v)
  if type(v) ~= "number" then return type(v) end
  if mathType then return mathType(v) end
  if v == floor(v) then return "integral" end
  return "fractional"
end

local function printable(s)
  for i = 1, #s do
    local c = byte(s, i)
    if c < 32 or c > 126 then return false end
  end
  return true
end

-- One value for a FAIL line: a number with its kind, a short printable string quoted, any other string by its length
local function show(v)
  if type(v) == "number" then return kindOf(v) .. " " .. num(v) end
  if type(v) == "string" then
    if #v <= 200 and printable(v) then return "\"" .. v .. "\"" end
    return "a string of " .. num(#v) .. " B"
  end
  return tostring(v)
end

-- The values t[from] to t[t.n], with their count
local function values(t, from)
  local parts = {}
  for i = from, t.n do parts[#parts + 1] = show(t[i]) end
  return "count " .. num(t.n - from + 1) .. ": " .. concat(parts, ", ")
end

-- What a call gave, from its pcall's pack
local function got(r)
  if not r[1] then return "raised " .. show(r[2]) end
  return values(r, 2)
end

-- gpu[name](...) under pcall, packed, so that a callback that raises is a result like any other
local function call(gpu, name, ...)
  return table.pack(pcall(gpu[name], ...))
end

-- Adler-32 of s with the modulo taken once at the end (P4.2): a and b stay below 2^53 for a chunk of 65 280 B, so the
-- value is exact on doubles and on integers, with + and % only. 16 bytes per string.byte call, added unrolled
local function adler32(s)
  local a, b, i, n = 1, 0, 1, #s
  while i + 15 <= n do
    local v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16 = byte(s, i, i + 15)
    a = a + v1; b = b + a; a = a + v2; b = b + a; a = a + v3; b = b + a; a = a + v4; b = b + a
    a = a + v5; b = b + a; a = a + v6; b = b + a; a = a + v7; b = b + a; a = a + v8; b = b + a
    a = a + v9; b = b + a; a = a + v10; b = b + a; a = a + v11; b = b + a; a = a + v12; b = b + a
    a = a + v13; b = b + a; a = a + v14; b = b + a; a = a + v15; b = b + a; a = a + v16; b = b + a
    i = i + 16
  end
  while i <= n do
    a = a + byte(s, i); b = b + a
    i = i + 1
  end
  return (b % 65521) * 65536 + a % 65521
end

-- ---- The sweep (P5): each case checks a call's value count, its values' types and number kinds, and the values the
-- spec fixes; message texts only where they are API, in the retries (amendments F3)

local function sweep(gpu)
  return { gpu = gpu, cases = 0, covered = {}, shapes = {}, fails = {} }
end

-- One case of `shape` on callback `name` (nil for the signal), failed when `wrong` says what is wrong
local function record(S, shape, name, label, wrong)
  S.cases = S.cases + 1
  S.shapes[shape] = true
  if name then S.covered[name] = true end
  if wrong then S.fails[#S.fails + 1] = label .. ": " .. wrong end
end

-- {TRUE}
local function isTrue(r)
  if r[1] and r.n == 2 and r[2] == true then return nil end
  return "got " .. got(r) .. ", want true"
end

-- {Integer}: a texture handle, type 1 in bits 27-30 and below 2^31 (28:518-528)
local function isHandle(r)
  local v = r[2]
  if r[1] and r.n == 2 and ofKind(v) and v > 0 and v < 0x80000000 and floor(v / 0x8000000) == 1 then return nil end
  return "got " .. got(r) .. ", want one " .. KIND_NAME .. " texture handle"
end

-- {Long}: a frame id above every one seen before
local function isFrameId(r, seen)
  local v = r[2]
  if r[1] and r.n == 2 and ofKind(v) and v > seen then return nil end
  return "got " .. got(r) .. ", want one " .. KIND_NAME .. " frame id above " .. num(seen)
end

-- {byte[],Long}: the region's bytes and frame f
local function isChunk(r, size, f)
  local data, id = r[2], r[3]
  if r[1] and r.n == 3 and type(data) == "string" and #data == size and ofKind(id) and id == f then return nil end
  return "got " .. got(r) .. ", want a string of " .. num(size) .. " B and frame id " .. num(f)
end

-- {null,String}: a program tests only for nil (28:347)
local function isFailure(r)
  if r[1] and r.n == 3 and r[2] == nil and type(r[3]) == "string" then return nil end
  return "got " .. got(r) .. ", want nil and a message"
end

-- An argument error, raised by OC with level 0, so with no position (amendments I8)
local function isArgumentError(r)
  if not r[1] and r.n == 2 and type(r[2]) == "string" and sub(r[2], 1, 12) == "bad argument" then return nil end
  return "got " .. (r[1] and values(r, 2) or "an error " .. show(r[2])) .. ", want an error beginning \"bad argument\""
end

-- The opengpu_frame signal of frame f: the card's address, f and "done" (28:110-112). awaitFrame keeps only this card's
-- "done" of f or later, so this case can fail on the count and the id alone; a signal with another address or status
-- leaves the wait to its bound, and the wait's FAIL names the last such signal (S13 review tests-4)
local function isSignal(e, address, f)
  if e.n == 4 and e[2] == address and ofKind(e[3]) and e[3] == f and e[4] == "done" then return nil end
  return "got " .. values(e, 2) .. ", want the card's address, frame id " .. num(f) .. " and \"done\""
end

-- getCaps' table (F6). Every card has the 15 keys and renderer, "host-gl" or "none". The root's card, the proxy with
-- bind, also has readback = true, graphics exactly when renderer is "none", and hostRenderer whenever it renders; the
-- harness's entity has no readback and no graphics, and hostRenderer when its rig names one. No other key
local function capsWrong(caps, card)
  local wrong, allowed = {}, { renderer = true, hostRenderer = true }
  for _, key in ipairs(CORE_KEYS) do
    allowed[key] = true
    local v = caps[key]
    if key == "format" then
      if type(v) ~= "string" then wrong[#wrong + 1] = "format is " .. show(v) end
    elseif not ofKind(v) then
      wrong[#wrong + 1] = key .. " is " .. show(v) .. ", want an " .. KIND_NAME
    end
  end
  local renderer, hostRenderer = caps.renderer, caps.hostRenderer
  if renderer ~= "host-gl" and renderer ~= "none" then wrong[#wrong + 1] = "renderer is " .. show(renderer) end
  if hostRenderer ~= nil and type(hostRenderer) ~= "string" then
    wrong[#wrong + 1] = "hostRenderer is " .. show(hostRenderer)
  end
  if card then
    allowed.readback, allowed.graphics = true, true
    if caps.readback ~= true then wrong[#wrong + 1] = "readback is " .. show(caps.readback) end
    if renderer == "none" then
      if type(caps.graphics) ~= "string" then wrong[#wrong + 1] = "graphics is " .. show(caps.graphics) end
    else
      if caps.graphics ~= nil then wrong[#wrong + 1] = "graphics is " .. show(caps.graphics) end
      if type(hostRenderer) ~= "string" then wrong[#wrong + 1] = "hostRenderer is " .. show(hostRenderer) end
    end
  end
  local unknown = {}
  for key in pairs(caps) do
    if not allowed[key] then unknown[#unknown + 1] = show(key) end
  end
  table.sort(unknown)
  if #unknown > 0 then wrong[#wrong + 1] = "unknown keys " .. concat(unknown, ", ") end
  local documented = TIERS[caps.tier]
  if not documented then
    wrong[#wrong + 1] = "tier is " .. show(caps.tier)
  else
    for _, key in ipairs({ "width", "height", "format", "maxReadbackBytes" }) do
      if caps[key] ~= documented[key] then
        wrong[#wrong + 1] = key .. " is " .. show(caps[key]) .. " at tier " .. num(caps.tier) .. ", want " ..
          show(documented[key])
      end
    end
  end
  if #wrong > 0 then return concat(wrong, "; ") end
end

-- ---- Frames (P3.6, P3.10)

-- The newest confirmed frame id, from a one-pixel read (24:129); a result that is not a string and a number is the
-- caller's FAIL, which names the call and what came back (amendments I7)
local function confirmed(gpu)
  local r = call(gpu, "readPixels", 0, 0, 1, 1)
  if r[1] and r.n == 3 and type(r[2]) == "string" and type(r[3]) == "number" then return r[3] end
  return nil, "readPixels(0, 0, 1, 1) gave " .. got(r) .. ", want a string and a number"
end

-- Up to 2 s for a status of this card, after a "busy" or "host render stalled" that an earlier program's frames in
-- flight can cause (28:105-107)
local function awaitStatus(gpu)
  for _ = 1, 40 do
    local e = table.pack(computer.pullSignal(0.05))
    if e[1] == "opengpu_frame" and e[2] == gpu.address then return end
  end
end

-- Waits for frame f, after harness.lua's waitFrame: polls the confirmed id with computer.pullSignal(0.05) between
-- polls, and takes this card's opengpu_frame signals of f or later from what the pulls return. It ends when the id is
-- at least f and the "done" has come, which in game is up to a server tick after the confirmation (S11 decisions D16).
-- A "dropped" is counted, not final: its frame still lands (28:112). The other card's signals and those of earlier
-- frames are skipped, and the last one skipped is named if the wait runs out. At most 400 polls, about 20 s. OpenOS's
-- pullSignal still returns each signal it dispatches
local function awaitFrame(gpu, f)
  local done, dropped, other = nil, 0, nil
  for _ = 1, 400 do
    local id, why = confirmed(gpu)
    if not id then return nil, why end
    if id >= f and done then return done, dropped end
    local e = table.pack(computer.pullSignal(0.05))
    if e[1] == "opengpu_frame" then
      local mine = e[2] == gpu.address and type(e[3]) == "number" and e[3] >= f
      if mine and e[4] == "done" then
        done = done or e
      elseif mine and e[4] == "dropped" then
        dropped = dropped + 1
      else
        other = e
      end
    end
  end
  return nil, "not confirmed with its \"done\" after 400 polls" ..
    (other and "; the last other opengpu_frame signal: " .. values(other, 2) or "")
end

-- createTexture(16, 16) under H23 (28:1074), as harness.lua's createTextureRetry: on "busy", a present seals whatever
-- frame is open, so that its deferred frees can fold, and after a status of this card it tries once more
local function createSprite(S)
  local r = call(S.gpu, "createTexture", 16, 16)
  if r[1] and r[2] == nil and r[3] == "busy" then
    call(S.gpu, "present")
    awaitStatus(S.gpu)
    r = call(S.gpu, "createTexture", 16, 16)
  end
  local wrong = isHandle(r)
  record(S, "{Integer}", "createTexture", "createTexture(16, 16)", wrong)
  -- A number of the wrong kind or count fails the sweep's case only; the pixel check goes on with it
  if r[1] and type(r[2]) == "number" then return r[2] end
  return nil, "createTexture(16, 16) " .. wrong
end

-- present() with P3.10's retries: on "busy" or "host render stalled" it waits for a status and presents again, at most
-- 5 times. The open frame stays through both (28:105-107)
local function present(S, seen)
  local r
  for attempt = 1, 6 do
    r = call(S.gpu, "present")
    local again = r[1] and r[2] == nil and (r[3] == "busy" or r[3] == "host render stalled")
    if not again or attempt == 6 then break end
    awaitStatus(S.gpu)
  end
  local wrong = isFrameId(r, seen)
  record(S, "{Long}", "present", "present()", wrong)
  if r[1] and type(r[2]) == "number" then return r[2] end
  return nil, "present() " .. wrong
end

-- ---- The pattern (P3.4, P3.5) and its check (P3.7, P3.8)

-- The sprite's 12 x 12 region, rows top first: texel (i, j) is ((i + 2j) % 4) steps, one byte or a LE u16, so zeros
-- occur, which KEY0 skips
local function spriteRegion(format)
  local step, two, out = SPRITE_STEP[format], BYTES_PER_PIXEL[format] == 2, {}
  for j = 0, 11 do
    for i = 0, 11 do
      local v = ((i + 2 * j) % 4) * step
      if two then out[#out + 1] = char(v % 256, floor(v / 256)) else out[#out + 1] = char(v) end
    end
  end
  return concat(out)
end

-- One segment of seven records, 119 B: 28 §9.4's frame (CLEAR, FILL_RECT 10,20,100,50, TEXT "Hi" at 8,180 with
-- TRANSPARENT_BG) with its BLIT moved to straddle the rectangle's right edge, plus a FILL_RECT clipped at the far
-- corner, the sprite again opaque, and TEXT "OpenGPU" with an opaque background inside the rectangle. A known limit:
-- at T3 the rows 102-152 and 204-356 hold the clear colour alone, so their four chunks have one digest, and a read of
-- other rows of that band would give the same bytes (S13 review tests-3)
local function segment(c, w, h, tex)
  return concat({ HEADER, clear(c.clear), fill(10, 20, 100, 50, c.fill), fill(w - 20, h - 10, 40, 20, c.corner),
    blit(tex, 0, 0, 16, 16, 100, 40, 1), blit(tex, 0, 0, 16, 16, 130, 40, 0),
    text(14, 26, c.fg, c.bg, 0, 0, "OpenGPU"), text(8, 180, c.hi, c.hiBg, 1, 0, "Hi"), END })
end

-- Reads the whole surface of frame f in floor(maxReadbackBytes / (width * bpp))-row chunks (28:690, :704; harness.lua's
-- readAll), each one a {byte[],Long} case, hashed and dropped at once, so that one chunk at most is live (28:727-732),
-- and yields after each, which keeps every slice short. Gives the digests and each chunk's rows
local function readBack(S, caps, bpp, f)
  local w, h = caps.width, caps.height
  local rows = floor(caps.maxReadbackBytes / (w * bpp))
  local digests, spans, y = {}, {}, 0
  while y < h do
    local n = math.min(rows, h - y)
    local label = "readPixels(0, " .. num(y) .. ", " .. num(w) .. ", " .. num(n) .. ")"
    local r = call(S.gpu, "readPixels", 0, y, w, n)
    local wrong = isChunk(r, w * n * bpp, f)
    record(S, "{byte[],Long}", "readPixels", label, wrong)
    -- Bytes that are not the region's, or of another frame, end the pixel check; a frame id of the wrong kind or an
    -- extra value fails the sweep's case only
    local data = r[2]
    if not (r[1] and type(data) == "string" and #data == w * n * bpp and r[3] == f) then
      return nil, label .. " " .. wrong
    end
    digests[#digests + 1] = adler32(data)
    spans[#spans + 1] = num(y) .. "-" .. num(y + n - 1)
    data, r = nil, nil
    computer.pullSignal(0)
    y = y + n
  end
  return digests, spans
end

-- The card's pixel check: its line after "pixels", and whether it passed. A card whose renderer is "none" FAILs here,
-- naming getCaps' graphics (amendments F4)
local function pixels(S, caps)
  local gpu, format = S.gpu, caps.format
  if caps.renderer == "none" then
    return "FAIL no renderer: " .. (type(caps.graphics) == "string" and caps.graphics or "no reason given"), false
  end
  local bpp, colours = BYTES_PER_PIXEL[format], COLOURS[format]
  if not bpp then return "FAIL format " .. show(format) .. " has no pattern", false end
  local w, h, max = caps.width, caps.height, caps.maxReadbackBytes
  if type(w) ~= "number" or type(h) ~= "number" or type(max) ~= "number" or w < 1 or h < 1 or max < w * bpp then
    return "FAIL surface " .. show(w) .. " x " .. show(h) .. " with maxReadbackBytes " .. show(max), false
  end
  local seen, why = confirmed(gpu)
  if not seen then return "FAIL " .. why, false end
  local tex
  tex, why = createSprite(S)
  if not tex then return "FAIL " .. why, false end
  record(S, "{TRUE}", "updateTexture", "updateTexture(sprite, 2, 2, 12, 12, region)",
    isTrue(call(gpu, "updateTexture", tex, 2, 2, 12, 12, spriteRegion(format))))
  local s = segment(colours, w, h, tex)
  if #s ~= 119 then return "FAIL the segment is " .. num(#s) .. " B, want 119", false end
  record(S, "{TRUE}", "submit", "submit(frame)", isTrue(call(gpu, "submit", s)))
  -- Freed while the frame is open: its TEX_FREE follows the frame's FRAME, so the frame still shows it (28 §6.4, O4)
  record(S, "{TRUE}", "free", "free(sprite)", isTrue(call(gpu, "free", tex)))
  local f
  f, why = present(S, seen)
  if not f then return "FAIL " .. why, false end
  local done, dropped = awaitFrame(gpu, f)
  if not done then return "FAIL frame " .. num(f) .. " " .. dropped, false end
  record(S, "signal", nil, "opengpu_frame of frame " .. num(f), isSignal(done, gpu.address, f))
  local digests, spans = readBack(S, caps, bpp, f)
  if not digests then return "FAIL frame " .. num(f) .. " " .. spans, false end
  local line = { "frame", num(f), "chunks", num(#digests) }
  for k = 1, #digests do line[#line + 1] = hex8(digests[k]) end
  -- Keyed by size too, so a surface the constants were not derived for names itself (P3.8; S13 review spec-4)
  local want, verdict = EXPECT[format .. " " .. num(w) .. "x" .. num(h)], "PASS"
  if not want then
    verdict = "FAIL no expected digests for " .. format .. " " .. num(w) .. "x" .. num(h)
  else
    for k = 1, #digests do
      if digests[k] ~= want[k] then
        verdict = "FAIL chunk " .. num(k) .. " rows " .. spans[k] .. " got " .. hex8(digests[k]) .. " want " ..
          (want[k] and hex8(want[k]) or "none")
        break
      end
    end
    if verdict == "PASS" and #want ~= #digests then
      verdict = "FAIL " .. num(#digests) .. " chunks, want " .. num(#want)
    end
  end
  line[#line + 1] = verdict
  if dropped > 0 then line[#line + 1] = "dropped " .. num(dropped) end
  return concat(line, " "), verdict == "PASS"
end

-- The cases that need no frame: a second texture's create, two content errors and its free, a second free, and the
-- content and argument errors of 28:336-345 (P5.2). The texture is freed with no frame open, so the BLIT naming it
-- after the free is STALE_HANDLE (28:316, :329)
local function errorCases(S, caps)
  local gpu = S.gpu
  local bpp = BYTES_PER_PIXEL[caps.format] or 1
  local w = type(caps.width) == "number" and caps.width or 1
  local h = type(caps.height) == "number" and caps.height or 1
  local max = type(caps.maxReadbackBytes) == "number" and caps.maxReadbackBytes or 1
  local r = call(gpu, "createTexture", 2, 2)
  record(S, "{Integer}", "createTexture", "createTexture(2, 2)", isHandle(r))
  local t = type(r[2]) == "number" and r[2] or 0x0800FFFF
  record(S, "{null,String}", "updateTexture", "updateTexture(t, 0, 0, 2, 2, 3 B)",
    isFailure(call(gpu, "updateTexture", t, 0, 0, 2, 2, "\0\0\0")))
  record(S, "{null,String}", "updateTexture", "updateTexture(t, 1, 1, 2, 2, region)",
    isFailure(call(gpu, "updateTexture", t, 1, 1, 2, 2, string.rep("\0", 4 * bpp))))
  record(S, "{TRUE}", "free", "free(t)", isTrue(call(gpu, "free", t)))
  record(S, "{null,String}", "free", "free(t) again", isFailure(call(gpu, "free", t)))
  record(S, "{null,String}", "createTexture", "createTexture(0, 1)", isFailure(call(gpu, "createTexture", 0, 1)))
  record(S, "{null,String}", "submit", "submit(a BLIT of t)",
    isFailure(call(gpu, "submit", HEADER .. blit(t, 0, 0, 2, 2, 0, 0, 0) .. END)))
  record(S, "{null,String}", "submit", "submit(3 B)", isFailure(call(gpu, "submit", "\1\2\3")))
  record(S, "{null,String}", "readPixels", "readPixels(0, height, 1, 1)",
    isFailure(call(gpu, "readPixels", 0, h, 1, 1)))
  local rows = floor(max / (w * bpp))
  record(S, "{null,String}", "readPixels", "readPixels(0, 0, width, rows + 1)",
    isFailure(call(gpu, "readPixels", 0, 0, w, rows + 1)))
  record(S, "argument-error", "readPixels", "readPixels(\"x\", 0, 1, 1)",
    isArgumentError(call(gpu, "readPixels", "x", 0, 1, 1)))
  record(S, "argument-error", "submit", "submit({})", isArgumentError(call(gpu, "submit", {})))
  record(S, "argument-error", "createTexture", "createTexture(16)", isArgumentError(call(gpu, "createTexture", 16)))
end

-- The sweep's line: PASS or FAIL, the case count, the callbacks covered in CALLBACKS' order, a callback the card does
-- not have as <name>:absent (the harness's bind), and the shapes covered in SHAPES' order, so that Java compares one
-- string (amendments I2)
local function sweepLine(S)
  local names, shapes = {}, {}
  for _, name in ipairs(CALLBACKS) do
    if S.gpu[name] == nil then
      names[#names + 1] = name .. ":absent"
    elseif S.covered[name] then
      names[#names + 1] = name
    end
  end
  for _, shape in ipairs(SHAPES) do
    if S.shapes[shape] then shapes[#shapes + 1] = shape end
  end
  local line = "sweep " .. (#S.fails == 0 and "PASS " or "FAIL ") .. num(S.cases) .. " cases; callbacks " ..
    concat(names, " ") .. "; shapes " .. concat(shapes, " ")
  if S.notRun then line = line .. "; not run: " .. S.notRun end
  return line
end

local function addresses(kind)
  local out = {}
  for address in pairs(component.list(kind, true)) do out[#out + 1] = address end
  table.sort(out)
  return out
end

local function main(...)
  local args = table.pack(...)
  local failures = 0
  local function failed(line)
    print(line)
    failures = failures + 1
  end
  -- Exact names: without `true`, component.list("opengpu") would match every opengpu_screen too (P2.4)
  local cards = addresses("opengpu")
  print("opengpu-selftest " .. ARCH .. " (" .. tostring(_VERSION) .. "), numbers " .. KIND .. ", " .. num(#cards) ..
    " card(s), free memory " .. num(computer.freeMemory()))
  -- The displays named on the command line, each resolved before any card runs (amendments F7). Two names of one
  -- display FAIL too: both cards would bind it, the last bind would win (24:642), and the first card's picture would
  -- not be seen (S13 review spec-2)
  local named = {}
  if args.n > 0 and (args[1] ~= "bind" or args.n < 2) then
    failed("FAIL usage: opengpu_selftest.lua [bind <display> [<display>]]")
  elseif args.n > 0 then
    local displays, given = addresses("opengpu_screen"), {}
    for i = 2, args.n do
      local name, hits = tostring(args[i]), {}
      for _, address in ipairs(displays) do
        if sub(address, 1, #name) == name then hits[#hits + 1] = address end
      end
      if #hits ~= 1 then
        failed("FAIL bind " .. name .. ": it begins " .. num(#hits) .. " display addresses, want 1")
      elseif given[hits[1]] then
        failed("FAIL bind " .. name .. ": the same display as " .. given[hits[1]])
      else
        named[#named + 1] = hits[1]
        given[hits[1]] = name
      end
    end
    if #named > #cards then
      failed("FAIL bind: " .. num(#named) .. " displays named for " .. num(#cards) .. " cards")
    end
  end
  if #cards == 0 then failed("FAIL no opengpu card in this computer") end
  if failures == 0 then
    for k, address in ipairs(cards) do
      local gpu, tag = component.proxy(address), "card " .. num(k) .. " "
      local S = sweep(gpu)
      local r = call(gpu, "getCaps")
      local caps = (r[1] and r.n == 2 and type(r[2]) == "table") and r[2] or {}
      local wrong = (caps ~= r[2] and "got " .. got(r) .. ", want one table") or capsWrong(caps, gpu.bind ~= nil)
      record(S, "map", "getCaps", "getCaps()", wrong)
      -- bind's two errors whenever the card has bind; {TRUE} only for a display named (F7). A proxy without bind binds
      -- nothing, so its card line says unbound
      local display = gpu.bind ~= nil and named[k] or nil
      if gpu.bind ~= nil then
        record(S, "{null,String}", "bind", "bind(\"not-an-address\")", isFailure(call(gpu, "bind", "not-an-address")))
        record(S, "{null,String}", "bind", "bind(computer.address())",
          isFailure(call(gpu, "bind", computer.address())))
        if display then
          wrong = isTrue(call(gpu, "bind", display))
          record(S, "{TRUE}", "bind", "bind(" .. short(display) .. ")", wrong)
          if wrong then display = nil end
        else
          S.notRun = "bind {TRUE}"
        end
      end
      -- The bound display's address, so the picture can be found (amendments I5)
      local hostRenderer = caps.hostRenderer ~= nil and tostring(caps.hostRenderer) or "-"
      print(tag .. short(address) .. " T" .. num(caps.tier) .. " " .. tostring(caps.format) .. " " .. num(caps.width) ..
        "x" .. num(caps.height) .. " display " .. (display and short(display) or "unbound") .. " renderer " ..
        tostring(caps.renderer) .. " hostRenderer " .. hostRenderer)
      local line, passed = pixels(S, caps)
      if passed then print(tag .. "pixels " .. line) else failed(tag .. "pixels " .. line) end
      errorCases(S, caps)
      if #S.fails == 0 then print(tag .. sweepLine(S)) else failed(tag .. sweepLine(S)) end
      for _, fail in ipairs(S.fails) do print(tag .. "sweep case " .. fail) end
    end
  end
  if failures == 0 then
    print("opengpu-selftest PASS")
  else
    print("opengpu-selftest FAIL " .. num(failures))
  end
end

main(...)
