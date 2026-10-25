-- ChargesTest (05:134; 23:159; S10 plan P14.7; amendments F12, I2), picked by the "case" parameter. Each case waits in
-- park(1) until Java's first tick by hand, so what follows starts on the call budget that tick refilled
-- (Machine.scala:497-498, :530-537), and Java ticks by hand after that, one tick per synchronized retry
local case = h.param("case")

if case == "readAll" then
  -- (a) A frame of 16 bands, confirmed first, so every chunk differs; then "reads" T3 readAlls in one burst of direct
  -- calls
  local caps = gpu.getCaps()
  local parts = { HEADER }
  for b = 0, 15 do
    parts[#parts + 1] = fill(0, b * 25, caps.width, 25, 0xFF000000 + b * 0x00100804)
  end
  parts[#parts + 1] = END
  assert(gpu.submit(concat(parts)))
  local f = assert(present())
  waitFrame(f)
  h.put("ready", f)
  park(1)
  for k = 1, h.param("reads") do readAll("burst" .. tostring(k)) end
  h.put("burstDone")
elseif case == "createTexture" then
  -- (b) createTexture(1024, 1024) on T3: c = 8 > k = 1.5, bitblt's two-phase pattern (28:710-713)
  park(1)
  local tex, why = gpu.createTexture(1024, 1024)
  h.put("created", tex, why)
else
  error("charges.lua: no case " .. tostring(case), 0)
end
