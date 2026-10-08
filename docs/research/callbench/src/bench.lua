-- callbench EEPROM program: runs as the BIOS inside OC's real machine.lua
-- sandbox (no OpenOS, no require).  One workload per boot; the harness
-- substitutes %%WORKLOAD%%, %%TOTAL%% and %%JIT%% before planting it.
-- Every call below is a real component call through
--   libcomponent.invoke -> machine.lua invoke() -> component.invoke (JNI)
--   -> Machine.invoke -> Component.invoke -> CallbackWrapper -> Bench.<method>
local addr = component.list("bench")()
if not addr then error("no bench component visible") end
local bench = component.proxy(addr)
local invoke = component.invoke
local clock, uptime, pullSignal = os.clock, computer.uptime, computer.pullSignal
local floor = math.floor

local W = "%%WORKLOAD%%"
local TOTAL = %%TOTAL%%
local JIT = "%%JIT%%"

local function report(s) invoke(addr, "report", s) end

if JIT == "off" then report("jit request off -> " .. tostring(invoke(addr, "jit", false))) end
report("FINGERPRINT sandbox _VERSION=" .. tostring(_VERSION) .. " | raw: " .. tostring(invoke(addr, "fingerprint")) ..
  " | freeKB=" .. floor(computer.freeMemory() / 1024) .. " totalKB=" .. floor(computer.totalMemory() / 1024))
do
  local rem, max = invoke(addr, "budget")
  report(string.format("budget remaining=%s max=%s", tostring(rem), tostring(max)))
end

local S = string.rep("x", 100000)   -- the 100 KB string
local T = {}                        -- the 1000-entry table
for i = 1, 1000 do T[i] = i end

local function stats(times)
  table.sort(times)
  local sum = 0
  for _, v in ipairs(times) do sum = sum + v end
  return times[1], times[math.ceil(#times / 2)], times[#times], sum / #times
end

-- Unlimited (limit = Integer.MAX_VALUE) calls never exhaust the budget, so a
-- batch runs inside ONE resume.  Java stamps nanoTime() on every call;
-- finish() returns the first->last interval, averaged over n-1 round trips.
-- os.clock() (Machine.cpuTime, counted only while the worker runs) is the
-- Lua-side cross-check over the same batch.
local function unlimited(label, total, B, f)
  local jt, lt = {}, {}
  local reps = floor(total / B)
  for r = 1, reps do
    pullSignal(0)                   -- yield: fresh resume, fresh deadline
    invoke(addr, "begin", false)
    local c0 = clock()
    f(B)
    local c1 = clock()
    local n, ns = invoke(addr, "finish")
    if n ~= B then report(string.format("!! label=%s batch=%d but entity saw n=%d", label, B, n)) end
    jt[#jt + 1] = ns / (n - 1) / 1000
    lt[#lt + 1] = (c1 - c0) * 1e6 / B
  end
  local jmin, jmed, jmax, jmean = stats(jt)
  local lmin, lmed, lmax, lmean = stats(lt)
  report(string.format("U label=%s n=%d batch=%d reps=%d java_us min=%.3f med=%.3f max=%.3f mean=%.3f | lua_us min=%.3f med=%.3f max=%.3f | calls_per_s_med=%.0f freeKB=%d",
    label, reps * B, B, reps, jmin, jmed, jmax, jmean, lmin, lmed, lmax, 1e6 / jmed, floor(computer.freeMemory() / 1024)))
end

-- Limited (limit = 256) calls stall on the budget: the kernel re-issues the
-- over-budget call as a synchronized call that runs on the next tick.  The
-- entity records the server tick of every call; the summary says how many
-- landed in each full tick.
local function limited(label, n, f)
  pullSignal(0)
  invoke(addr, "begin", true)
  local u0, c0 = uptime(), clock()
  f(n)
  local u1, c1 = uptime(), clock()
  local cnt, ns, summary = invoke(addr, "finish")
  local ticks = floor((u1 - u0) * 20 + 0.5)
  report(string.format("L label=%s n=%d java_wall_ms=%.1f lua_cpu_ms=%.1f uptime_ticks=%d calls_per_tick_by_uptime=%.1f calls_per_s=%.0f | %s",
    label, n, ns / 1e6, (c1 - c0) * 1e3, ticks, n / math.max(ticks, 1), cnt / (ns / 1e9), summary))
end

local function f_local(n) local function g() end for i = 1, n do g() end end
local function f_invoke_noop(n) for i = 1, n do invoke(addr, "noop") end end
local function f_proxy_noop(n) local noop = bench.noop for i = 1, n do noop() end end
local function f_add(n) for i = 1, n do invoke(addr, "add", i, 2) end end
local function f_sink(n) for i = 1, n do invoke(addr, "sink", S) end end
local function f_echo(n) for i = 1, n do local r = invoke(addr, "echo", S) end end
local function f_sinkTable(n) for i = 1, n do invoke(addr, "sinkTable", T) end end
local function f_echoTable(n) for i = 1, n do local r = invoke(addr, "echoTable", T) end end
local function f_noop256(n) for i = 1, n do invoke(addr, "noop256") end end
local function f_add256(n) for i = 1, n do invoke(addr, "add256", i, 2) end end
local function f_echo256(n) for i = 1, n do local r = invoke(addr, "echo256", S) end end
local function f_echoTable256(n) for i = 1, n do local r = invoke(addr, "echoTable256", T) end end
local function f_sync(n) for i = 1, n do invoke(addr, "sync") end end
-- the bare JNI upcall floor: computer.uptime() is a pushScalaFunction with no
-- kernel wrapper, no Machine.invoke, no budget, no argument conversion
local function f_jni_uptime(n) for i = 1, n do uptime() end end
local function f_jni_freemem(n) local fm = computer.freeMemory for i = 1, n do fm() end end
-- a yield per iteration: computer.pullSignal(0) -> coroutine.yield -> Sleeping -> resumed by the next tick
local function f_yield0(n) for i = 1, n do pullSignal(0) end end

-- sanity: the echoes really round-trip (each is one extra call, not timed)
do
  local r = invoke(addr, "echo", S)
  assert(type(r) == "string" and #r == #S, "echo mismatch")
  local t = invoke(addr, "echoTable", T)
  assert(type(t) == "table" and #t == 1000 and t[1000] == 1000, "echoTable mismatch")
  assert(invoke(addr, "add", 40, 2) == 42, "add mismatch")
  assert(invoke(addr, "sink", S) == 100000, "sink mismatch")
  assert(invoke(addr, "sinkTable", T) == 1000, "sinkTable mismatch")
  report("sanity ok")
end

if W == "local_call" then unlimited("local_call", TOTAL, 10000, f_local)
elseif W == "invoke_noop" then unlimited("invoke_noop", TOTAL, 10000, f_invoke_noop)
elseif W == "proxy_noop" then unlimited("proxy_noop", TOTAL, 10000, f_proxy_noop)
elseif W == "invoke_add" then unlimited("invoke_add_scalar", TOTAL, 10000, f_add)
elseif W == "sink_100KB" then unlimited("sink_100KB", TOTAL, 2000, f_sink)
elseif W == "echo_100KB" then unlimited("echo_100KB", TOTAL, 2000, f_echo)
elseif W == "sinkTable_1000" then unlimited("sinkTable_1000", TOTAL, 2000, f_sinkTable)
elseif W == "echoTable_1000" then unlimited("echoTable_1000", TOTAL, 2000, f_echoTable)
elseif W == "lim_noop256" then limited("lim_noop256", 8192, f_noop256)
elseif W == "lim_add256" then limited("lim_add256", 8192, f_add256)
elseif W == "lim_echo256" then limited("lim_echo256_100KB", 4096, f_echo256)
elseif W == "lim_echoTable256" then limited("lim_echoTable256_1000", 2048, f_echoTable256)
elseif W == "sync_noop" then limited("sync_noop_NOTdirect", 20, f_sync)
elseif W == "jni_uptime" then unlimited("jni_uptime_bare_upcall", TOTAL, 10000, f_jni_uptime)
elseif W == "jni_freemem" then unlimited("jni_freeMemory_bare_upcall", TOTAL, 10000, f_jni_freemem)
elseif W == "yield0" then limited("yield0_pullSignal0_per_iteration", 60, f_yield0)
else report("!! unknown workload " .. W) end
report("DONE")
while true do pullSignal(math.huge) end
