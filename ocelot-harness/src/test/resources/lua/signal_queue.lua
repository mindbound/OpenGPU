-- SignalQueueTest (19:202; 05:110; S10 amendments F11): opengpu_frame's layout (24:127; 28:110-112) and the profile's
-- signal queue cap, with the frame status that arrives while the queue is full refused (Machine.scala:307)
local caps = gpu.getCaps()
h.put("caps", caps.renderer, caps.tier, caps.width, caps.height, caps.format)

-- Nothing may be queued before the fill
local drained = 0
while computer.pullSignal(0) do drained = drained + 1 end
h.put("drained", drained)

-- Frame 1 while the host renders nothing: Java moves the device's clock past 1 s, so "dropped" comes first, then lets
-- the host render the frame for its "done"
local f1 = assert(present())
h.put("presented", f1)
for _ = 1, 2 do
  local name, card, id, status = computer.pullSignal(10)
  h.put("signal", name, card, id, status, math.type and math.type(id) or "none")
end

-- The queue filled with the script's own signals, then frame 2, whose "done" the full queue refuses. h.sync() yields a
-- tick without taking a signal
local n = 0
while computer.pushSignal("ogpu_fill", n) do n = n + 1 end
h.put("filled", n)
local f2 = assert(present())
h.put("presented", f2)
local polls = 0
while frameNow() < f2 do
  polls = polls + 1
  if polls > 200 then error("frame " .. tostring(f2) .. " not confirmed after 200 ticks", 0) end
  h.sync()
end
h.put("confirmed", f2, polls)
h.put("pushAfter", computer.pushSignal("ogpu_fill", -1))
