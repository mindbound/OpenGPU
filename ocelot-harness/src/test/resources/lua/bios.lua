-- The harness's EEPROM BIOS (S10 plan P8.2; 05:134), well under the EEPROM's 4 KiB: it takes the test's chunk from
-- ogpu_harness in 16 KB parts, loads it as source only (28:744; allowBytecode is false on the pack), runs it, reports
-- the result once and shuts the machine down. The loader joins the parts in one table.concat, which 28:745's 4 KB rule
-- does not cover: that rule is the encoder's (S10 amendments F17)
local h = component.proxy(component.list("ogpu_harness")())
local parts, i = {}, 1
while true do
  local p = h.script(i)
  if not p then break end
  parts[i] = p
  i = i + 1
end
local f, e = load(table.concat(parts), "=harness", "t")
if not f then
  h.finish(false, "load: " .. tostring(e))
else
  local ok, err = xpcall(f, debug.traceback)
  h.finish(ok, ok and "" or tostring(err))
end
computer.shutdown()
