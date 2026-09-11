-- Grade FIELD-TEST-UI1 run 2's 0.05 arm against R1's two models, on the uptime interval the run
-- actually covered. Numbers below are READ from the exit report in the user's screenshot; nothing
-- is retyped from a summary (the screenshot->markdown audit rule).

local START, EXIT = 112.10, 133.90          -- "uptime 112.10 s" and "uptime at exit 133.90 s"
local WAITS, T1, T2 = 374, 328, 46          -- "idle waits 374", "1 tick 328, 2+ ticks 46"
local MEAN = 43.4                           -- "mean without pauses 43.4 ms over 374 waits"
local PASSES = 386
local T = 0.05

-- k(n): ticks one timed pull costs when issued while the tick counter reads n.
local function k(n)
  local deadline = n / 20.0 + T
  local i = 1
  while (n + i) / 20.0 < deadline do i = i + 1 end
  return i
end

-- MODEL A (uniform): the share of STARTING ticks in the covered range that need 2, weighted by
-- how long the machine spends in each half of the range. Waits are longer where k = 2, so weight
-- by wall time, not by tick count.
local function uniformShare(lo, hi)
  local n0, n1 = math.floor(lo * 20), math.floor(hi * 20)
  local two, all = 0, 0
  for n = n0, n1 - 1 do
    local ki = k(n)
    -- a wait starting at tick n occupies 50*ki ms of wall, so it accounts for 1/ki of the ticks
    all = all + 1 / ki
    if ki >= 2 then two = two + 1 / ki end
  end
  return two / all, all
end

-- MODEL B (chain/orbit): the machine walks n -> n + k(n), because each wait ends exactly on the
-- boundary that satisfies its deadline and the next pull is issued inside that same tick (the
-- pass's work is ~13 ms, under one tick). This is deterministic, so the run's own interval gives
-- an exact prediction rather than a distribution.
local function orbit(lo, hi)
  local n, n1 = math.floor(lo * 20), math.floor(hi * 20)
  local waits, two = 0, 0
  local twoBefore128, twoAfter128 = 0, 0
  while n < n1 do
    local ki = k(n)
    waits = waits + 1
    if ki >= 2 then
      two = two + 1
      if n / 20.0 < 128 then twoBefore128 = twoBefore128 + 1 else twoAfter128 = twoAfter128 + 1 end
    end
    n = n + ki
  end
  return waits, two, twoBefore128, twoAfter128
end

print(("RUN: 0.05 arm, uptime %.2f -> %.2f s (span %.2f s), crossing the 128 s boundary at %.1f s in")
  :format(START, EXIT, EXIT - START, 128 - START))
print(("OBSERVED: %d idle waits, 1 tick %d, 2+ ticks %d  =>  2+ share %.1f%% (%d/%d)")
  :format(WAITS, T1, T2, 100 * T2 / WAITS, T2, WAITS))
print(("          mean without pauses %.1f ms; mean k from the mean wait = (%.1f + 13.2)/50 = %.3f")
  :format(MEAN, MEAN, (MEAN + 13.2) / 50))
print("")

local us, _ = uniformShare(START, EXIT)
print(("MODEL A (uniform starting tick, wall-weighted): 2+ share %.1f%%   quotient observed/predicted %.2f")
  :format(100 * us, (T2 / WAITS) / us))

local w, two, b128, a128 = orbit(START, EXIT)
print(("MODEL B (chain/orbit n -> n + k(n)):            2+ share %.1f%% (%d of %d waits)   quotient %.2f")
  :format(100 * two / w, two, w, (T2 / WAITS) / (two / w)))
print(("          and it predicts WHERE: %d two-tick waits below 128 s, %d above"):format(b128, a128))
print(("          predicted wait count %d vs observed %d  (quotient %.2f)"):format(w, WAITS, WAITS / w))
print("")

-- What the two halves look like on their own, for the record.
for _, r in ipairs({ {START, 128}, {128, EXIT} }) do
  local ws, tw = orbit(r[1], r[2])
  local uu = select(1, uniformShare(r[1], r[2]))
  print(("  segment [%.2f, %.2f): orbit %d waits, %d two-tick (%.1f%%); uniform %.1f%%")
    :format(r[1], r[2], ws, tw, 100 * tw / ws, 100 * uu))
end
