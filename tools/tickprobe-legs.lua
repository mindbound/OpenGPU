-- tools/tickprobe-legs.lua -- drive ingame/tickprobe.lua's wakeLabel (loaded from the file's own text by
-- its signature, so a shifted line number cannot load junk) against an exact model of the engine chain:
--   sleepUntil (tickprobe.lua:169-173)
--   -> event.pull -> event.pullFiltered (openos lib/event.lua:119-156)
--   -> computer.pullSignal dispatch (lib/event.lua:33-84, no handlers with finite timeouts)
--   -> machine.lua pullSignal (machine.lua:1414-1422)
--   -> coroutine.yield(x) -> NativeLuaArchitecture.scala:259 (x*20).toInt
--   -> Machine.scala:1011-1015 ticks>0 ? Sleeping(remainIdle=ticks) : Yielded(12 ms)
--   uptime() = Machine.upTime() = uptime/20.0 (Machine.scala:174-183), uptime a Long.
-- Poll legs advance the tick by one (a 12 ms re-run cannot skip a 50 ms tick) and the machine
-- loop re-checks `uptime() >= deadline` at every layer, exactly as the Lua files do.

local PROBE = (...) or "C:/Users/astro/Downloads/OpenGPU/ingame/tickprobe.lua"
local src = {}
do
  local f = assert(io.open(PROBE, "r"))
  for line in f:lines() do src[#src + 1] = line end
  f:close()
end
local function span(a, b)
  local t = {}
  for i = a, b do t[#t + 1] = src[i] end
  return table.concat(t, "\n")
end
-- Locate wakeLabel by its own text so a shifted line number cannot silently load junk.
local startL, endL
for i, l in ipairs(src) do
  if l:match("^local function wakeLabel%(now, t%)") then startL = i end
  if startL and not endL and i > startL and l:match("^end%s*$") then endL = i end
end
assert(startL and endL, "wakeLabel not found")
local chunk = span(startL, endL) .. "\nreturn wakeLabel"
local wakeLabel = assert(load(chunk, "wakeLabel@tickprobe"))()
io.write(string.format("loaded wakeLabel from lines %d-%d of %s under %s\n", startL, endL, PROBE, _VERSION))

-- ---------------------------------------------------------------------------- engine model
local K            -- Machine.uptime (Long)
local function uptime() return K / 20 end   -- Machine.scala:182 uptime / 20.0 (Long -> Double / 20.0)

local legs         -- per sleep: list of {kind="S"|"P", ticks=n}
local polled

local function toInt(x)   -- Scala Double.toInt: truncation toward zero; NaN -> 0
  if x ~= x then return 0 end
  if x >= 0 then return math.floor(x) else return -math.floor(-x) end
end

-- NativeLuaArchitecture.scala:259 + Machine.scala:1011-1015 + the resume.
local function engine_yield(x)
  local ticks = toInt(x * 20)
  if ticks > 0 then
    K = K + ticks                 -- Sleeping: remainIdle=ticks; resumes with uptime = K+ticks
    legs[#legs + 1] = { kind = "S", ticks = ticks }
  else
    polled = true                 -- Yielded: re-run every 12 ms; the run that sees the next
    K = K + 1                     -- update sees uptime = K+1 (12 ms < 50 ms: no skipped tick)
    legs[#legs + 1] = { kind = "P", ticks = 0 }
  end
  return nil                      -- no signal
end

-- machine.lua:1414-1422
local function machine_pullSignal(timeout)
  local deadline = uptime() + (type(timeout) == "number" and timeout or math.huge)
  repeat
    local signal = engine_yield(deadline - uptime())
    if signal ~= nil then return signal end
  until uptime() >= deadline
end

-- lib/event.lua:33-84 with no handlers holding a finite timeout
local function dispatch(seconds)
  seconds = seconds or math.huge
  local deadline = uptime() + seconds
  repeat
    local closest = deadline
    local signal = machine_pullSignal(closest - uptime())
    if signal then return signal end
  until uptime() >= deadline
end

-- lib/event.lua:130-156 (event.pull(number) -> pullFiltered(number, nil))
local function pullFiltered(seconds)
  local deadline = uptime() + (seconds or math.huge)
  repeat
    local waitTime = deadline - uptime()
    if waitTime < 0 then break end
    local signal = dispatch(waitTime)
    if signal ~= nil then return signal end
  until true   -- signal.n == 0
end

-- tickprobe.lua:169-173
local function sleepUntil(d)
  legs, polled = {}, false
  local guard = 0
  repeat
    pullFiltered(d - uptime())
    guard = guard + 1
    assert(guard < 1000, "runaway sleepUntil")
  until uptime() >= d
end

-- ---------------------------------------------------------------------------- comparison
local total, mism, mismSP, mismTick, mismFirst, polledSeen, sSeen = 0, 0, 0, 0, 0, 0, 0
local examples = {}
local firstGapArtifacts = {}   -- per (mode, sleepT): the first gap of a trial started at K0+1

local function compare(now, t, ctx)
  -- the label, on the same doubles the engine will see
  local wl, nt, d = wakeLabel(now, t)
  if t <= 0 then return "N" end
  -- label's own predicted final tick when S: re-run the label loop to read k (not exported)
  local kLab = math.floor(now * 20 + 0.5)
  local u = now
  local labelFirst
  while u < d do
    local n = math.floor((d - u) * 20)
    if labelFirst == nil then labelFirst = n end
    if n < 1 then break end
    kLab = kLab + n
    u = kLab / 20
  end
  -- the engine
  K = math.floor(now * 20 + 0.5)
  sleepUntil(d)
  local engineKind = polled and "P" or "S"
  local engineFirst = legs[1] and legs[1].ticks or -1
  total = total + 1
  if polled then polledSeen = polledSeen + 1 else sSeen = sSeen + 1 end
  local bad = false
  if wl ~= engineKind then mismSP = mismSP + 1; bad = true end
  if wl == "S" and engineKind == "S" and K ~= kLab then mismTick = mismTick + 1; bad = true end
  if nt ~= engineFirst and not (wl == "P" and engineFirst == 0 and nt == 0) then
    if nt ~= engineFirst then mismFirst = mismFirst + 1; bad = true end
  end
  if bad then
    mism = mism + 1
    if #examples < 12 then
      examples[#examples + 1] = string.format("%s now=%.17g t=%.17g label=%s n=%s kLab=%d | engine=%s first=%d K=%d legs=%d",
          ctx, now, t, wl, tostring(nt), kLab, engineKind, engineFirst, K, #legs)
    end
  end
  return wl
end

-- ---------------------------------------------------------------------------- generators
local origins = {}
for _, s in ipairs({ 3, 7, 40, 100, 129, 200, 255, 300, 511, 600, 1023, 1500, 2047, 2100, 3000, 4095, 4100, 6000, 8191, 9000, 20000 }) do
  origins[#origins + 1] = s * 20
  origins[#origins + 1] = s * 20 + 7
end

local L = 400   -- intervals per trial

local function chaseTrial(sleepT, bias, K0, startOffset, ctx)
  local origin = K0 / 20
  K = K0 + startOffset
  local firstGap
  local prevK = K
  for i = 1, L do
    local now = K / 20
    local t = origin + i * sleepT - bias - now
    local wl = compare(now, t, ctx)
    if t <= 0 then
      -- "N": no sleep, next write same tick
    end
    if i == 1 then firstGap = K - prevK end
    prevK = K
  end
  return firstGap
end

local function exactTrial(sleepT, K0, startOffset, ctx)
  local nTicks = math.floor(sleepT * 20 + 0.5)
  local originTick = math.floor((K0 / 20) * 20 + 0.5)
  K = K0 + startOffset
  local prevK = K
  local firstGap
  for i = 1, L do
    local now = K / 20
    local t = (originTick + i * nTicks) / 20 - now
    compare(now, t, ctx)
    if i == 1 then firstGap = K - prevK end
    prevK = K
  end
  return firstGap
end

local function naiveTrial(sleepT, K0, ctx)
  K = K0
  for i = 1, L do
    local now = K / 20
    compare(now, sleepT, ctx)
  end
end

for _, sleepT in ipairs({ 0.05, 0.1, 0.2, 0.25, 0.249, 0.06, 0.03 }) do
  for _, bias in ipairs({ 0, 0.001 }) do
    for _, K0 in ipairs(origins) do
      for _, off in ipairs({ 0, 1 }) do
        local ctx = string.format("chase t=%g b=%g K0=%d off=%d", sleepT, bias, K0, off)
        local fg = chaseTrial(sleepT, bias, K0, off, ctx)
        local key = string.format("chase %g bias %g off %d", sleepT, bias, off)
        firstGapArtifacts[key] = firstGapArtifacts[key] or {}
        firstGapArtifacts[key][fg] = (firstGapArtifacts[key][fg] or 0) + 1
      end
    end
  end
end
for _, sleepT in ipairs({ 0.05, 0.1, 0.2, 0.25 }) do
  for _, K0 in ipairs(origins) do
    for _, off in ipairs({ 0, 1 }) do
      local ctx = string.format("exact t=%g K0=%d off=%d", sleepT, K0, off)
      local fg = exactTrial(sleepT, K0, off, ctx)
      local key = string.format("exact %g off %d", sleepT, off)
      firstGapArtifacts[key] = firstGapArtifacts[key] or {}
      firstGapArtifacts[key][fg] = (firstGapArtifacts[key][fg] or 0) + 1
    end
  end
end
for _, sleepT in ipairs({ 0.05, 0.1, 0.25, 0.03, 0.06 }) do
  for _, K0 in ipairs(origins) do
    naiveTrial(sleepT, K0, string.format("naive t=%g K0=%d", sleepT, K0))
  end
end
-- random pairs to reach the count
math.randomseed(12345)
local want = 2000000
local rnd = 0
while total < want do
  local Kr = math.random(20, 20 * 40000)
  local now = Kr / 20
  local t = math.random() * 0.6 + 1e-6
  compare(now, t, string.format("random K=%d", Kr))
  rnd = rnd + 1
end

io.write(string.format("pairs %d (random %d) | engine S %d P %d | mismatches %d (S/P %d, S-tick %d, first-leg %d)\n",
    total, rnd, sSeen, polledSeen, mism, mismSP, mismTick, mismFirst))
for _, e in ipairs(examples) do io.write("  ", e, "\n") end
local keys = {}
for k in pairs(firstGapArtifacts) do keys[#keys + 1] = k end
table.sort(keys)
io.write("first gap of a trial, by (mode, sleep, start offset from origin tick):\n")
for _, k in ipairs(keys) do
  local parts = {}
  local gs = {}
  for g in pairs(firstGapArtifacts[k]) do gs[#gs + 1] = g end
  table.sort(gs)
  for _, g in ipairs(gs) do parts[#parts + 1] = string.format("%d=%d", g, firstGapArtifacts[k][g]) end
  io.write(string.format("  %-28s %s\n", k, table.concat(parts, " ")))
end
