-- What does ONE timed event.pull cost, in ticks of computer.uptime()?
--
-- The shipped loop (installed OC 1.12.61, assets/opencomputers/lua/machine.lua:1414-1423):
--     local deadline = computer.uptime() + timeout
--     repeat ... coroutine.yield(deadline - computer.uptime()) ... until computer.uptime() >= deadline
-- and computer.uptime() = (tick counter) / 20.0 (Machine.upTime(), verified in the installed jar).
--
-- So with the machine at tick n when the pull is issued, the pull returns after
--     k = min{ k >= 1 : (n+k)/20.0 >= n/20.0 + T }
-- ticks. k is never 0: the deadline is strictly above the current uptime value, which only moves
-- on a tick. Whether k is 1 or 2 at T = 0.05 is decided by double rounding -- the binade effect.
--
-- This is the per-WAIT distribution, which is what uikeys bins (uikeys.lua:219-220).

local function ticksFor(n, T)
  local u0 = n / 20.0
  local deadline = u0 + T
  local k = 1
  while (n + k) / 20.0 < deadline do
    k = k + 1
    if k > 8 then return k end
  end
  return k
end

local function survey(lo, hi, T)          -- uptime seconds [lo, hi)
  local n0, n1 = math.floor(lo * 20), math.floor(hi * 20)
  local step = math.max(1, math.floor((n1 - n0) / 20000))
  local counts, total = {}, 0
  for n = n0, n1 - 1, step do
    local k = ticksFor(n, T)
    counts[k] = (counts[k] or 0) + 1
    total = total + 1
  end
  local parts = {}
  for k = 1, 8 do
    if counts[k] then
      parts[#parts + 1] = ("%d tick%s %5.1f%%"):format(k, k == 1 and " " or "s", 100 * counts[k] / total)
    end
  end
  return table.concat(parts, "   "), total
end

local RANGES = {
  {60, 128}, {128, 256}, {256, 512}, {512, 1024}, {1024, 2048},
  {2048, 4096}, {4096, 8192}, {8192, 16384}, {16384, 32768}, {32768, 65536},
}

for _, T in ipairs({ 0.02, 0.05 }) do
  print(("=== timeout %.3f s ==="):format(T))
  for _, r in ipairs(RANGES) do
    local line, n = survey(r[1], r[2], T)
    print(("  uptime [%6d,%6d)  n=%5d   %s"):format(r[1], r[2], n, line))
  end
end

-- The wall-time consequence, for the record: a wait costs (time to the next tick boundary,
-- uniform in [0,50) ms, mean 25) + (k-1)*50 ms + the pull's own cost. Run 1 measured a 37.3 ms
-- mean at 0.02, which is 25 + ~12 (executionDelay) -- consistent with k = 1 everywhere.
print("")
print("expected wall mean, ms, for a given 2-tick share s:  37.3 + 50*s")
for _, s in ipairs({ 0, 0.251, 0.4, 0.666 }) do
  print(("  s = %5.1f%%  ->  %.1f ms, %.1f passes/s"):format(100 * s, 37.3 + 50 * s,
      1000 / (37.3 + 50 * s + 13.2)))
end
