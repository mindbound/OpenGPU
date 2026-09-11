-- What share of idle waits costs TWO ticks, in a pure lower-half binade, under the family of
-- models FIELD-TEST-UI1 run 2 left open? This is the derivation of record for run 3's register.
--
-- THE FAMILY. A timed pull issued while the machine's tick counter reads n returns after
--     k(n) = min{ k >= 1 : (n+k)/20.0 >= n/20.0 + T }
-- ticks (see tools/tickmap.lua). What differs between models is WHICH n the next wait starts at:
--
--   model A (uniform)  the starting tick is drawn uniformly, independently each time. Registered
--                      as 40 % two-tick waits in a lower-half binade.
--   model B (chain)    the next pull is issued inside the tick the last wait ended in, so
--                      n -> n + k(n) exactly. A deterministic orbit.
--   model B(eps)       the chain, but a pass whose work spills past the tick boundary costs one
--                      extra tick: n -> n + k(n) + 1 with probability eps. Run 2 bounded
--                      eps <~ 0.10 and best-fit it near 0.02-0.04; B is the eps = 0 member.
--
-- Run 2 could not separate A from B because it crossed a binade boundary and 82 % of its waits
-- fell where every model predicts zero. A run held inside [128, 256) s (or [2048, 4096) s)
-- separates them by ~26 points, which is what this prints.

local T = 0.05

local function k(n)
  local deadline = n / 20.0 + T
  local i = 1
  while (n + i) / 20.0 < deadline do i = i + 1 end
  return i
end

-- Deterministic: no Math.random, so the same numbers come out of every run (the workflow rule
-- about reproducibility applies to registers too), so the spill is RANDOM but seeded, and every
-- figure below is a mean over many seeds with its spread.
--
-- The first version of this tool applied the spill on a fixed stride (every 1/eps-th pass). That
-- is not the model: k(n) repeats with period 3 in a lower-half binade (1,2,2), so a fixed stride
-- phase-locks to it and produced a NON-MONOTONE band (66.7 / 67.8 / 65.9 / 64.9) that is an
-- artifact of the stride, not a property of the spill. Caught in review, 2026-09-11.
local function chain(lo, hi, eps, seed)
  math.randomseed(seed)
  local n, n1 = math.floor(lo * 20), math.floor(hi * 20)
  local waits, two = 0, 0
  while n < n1 do
    local ki = k(n)
    waits = waits + 1
    if ki >= 2 then two = two + 1 end
    n = n + ki
    if eps > 0 and math.random() < eps then n = n + 1 end
  end
  return waits, two
end

-- Mean share and its spread over TRIALS seeds, plus the mean wait count.
local TRIALS = 200
local function chainBand(lo, hi, eps)
  if eps == 0 then
    local w, two = chain(lo, hi, 0, 1)
    return 100 * two / w, 0, w, 100 * two / w, 100 * two / w
  end
  local sum, sumsq, wsum, lo_, hi_ = 0, 0, 0, math.huge, -math.huge
  for s = 1, TRIALS do
    local w, two = chain(lo, hi, eps, s * 7919)
    local share = 100 * two / w
    sum, sumsq, wsum = sum + share, sumsq + share * share, wsum + w
    if share < lo_ then lo_ = share end
    if share > hi_ then hi_ = share end
  end
  local mean = sum / TRIALS
  return mean, math.sqrt(math.max(0, sumsq / TRIALS - mean * mean)), wsum / TRIALS, lo_, hi_
end

local function uniform(lo, hi)          -- model A as REGISTERED: the raw tick composition
  local n0, n1 = math.floor(lo * 20), math.floor(hi * 20)
  local two, all = 0, 0
  for n = n0, n1 - 1 do
    all = all + 1
    if k(n) >= 2 then two = two + 1 end
  end
  return two / all
end

local RANGES = {
  { 130, 250, "the window run 3 should use: inside [128, 256)" },
  { 2050, 2170, "the same class one binade up: inside [2048, 4096)" },
  { 260, 380, "an UPPER-half binade, for contrast: inside [256, 512)" },
  { 600, 720, "a polling binade, where nothing sleeps: inside [512, 2048)" },
}

for _, r in ipairs(RANGES) do
  local lo, hi, what = r[1], r[2], r[3]
  print(("=== uptime [%d, %d) s -- %s"):format(lo, hi, what))
  print(("  model A, uniform (as registered):        %5.1f %% of waits are 2-tick"):format(100 * uniform(lo, hi)))
  for _, eps in ipairs({ 0, 0.02, 0.04, 0.10 }) do
    local mean, sd, w, mn, mx = chainBand(lo, hi, eps)
    print(("  model B, chain, spill eps = %.2f:  %5.1f %% +/- %.1f (range %.1f-%.1f over %d seeds), %.0f waits")
      :format(eps, mean, sd, mn, mx, eps == 0 and 1 or TRIALS, w))
  end
  print("")
end

-- The population run 3 will actually have. A 60 s wall minute with a ~10 s menu pause advances
-- ~50 s of UPTIME (the pause freezes the tick counter), and a sleeping binade runs ~12 waits per
-- second of uptime -- so ~600 waits, not the ~1000 a wall minute suggests. That distinction cost
-- an earlier register a wrong standard error.
local waitsPerS = 20 / (1 + 0.667)
local n = math.floor(waitsPerS * 50 + 0.5)
print(("Run 3's 0.05 arm: ~%.1f waits/s of uptime x ~50 s of uptime = ~%d waits."):format(waitsPerS, n))
print(("Binomial standard error at p = 0.667 on n = %d is %.1f points; at p = 0.400 it is %.1f."):format(
    n, 100 * math.sqrt(0.667 * 0.333 / n), 100 * math.sqrt(0.4 * 0.6 / n)))
print("So the A-versus-B gap (25 points) is ~13 standard errors, and the spill band is under two:")
print("the SHARE decides A against B, and eps needs the tick budget, not the share.")
