-- Calls from Java's case batches (S10 plan P14.4 (d)-(e), P14.5, P14.6): NoRendererTest's region rows and validation
-- parity, GlParityTest's parity on a host that renders, and ArgumentRowsTest. Case batch i is Lua source returning a
-- function of (E, call, wait), E being the char path's table. call(i, name, ...) puts ("args", i, name, ...), the
-- arguments as OC converted them, then runs gpu[name](...) under pcall, puts ("call", i, ...) with what pcall returned
-- and returns it, so a batch can keep a handle or a frame id for a later call; wait(f) is harness.lua's waitFrame.
-- Java replays each call on a DeviceRig with those arguments and compares the two results
local function call(i, name, ...)
  h.put("args", i, name, ...)
  local result = table.pack(pcall(gpu[name], ...))
  h.put("call", i, table.unpack(result, 1, result.n))
  return table.unpack(result, 1, result.n)
end

local i = 1
while true do
  local batch = caseBatch(i)
  if not batch then break end
  batch(CHAR, call, waitFrame)
  i = i + 1
  computer.pullSignal(0)
end
h.put("batches", i - 1)
