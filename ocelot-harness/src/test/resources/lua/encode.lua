-- EncoderIdentityTest's corpus (S10 plan P14.3; 28:907): case batch i is Lua source returning a function of an encoder
-- table, which returns that batch's records in order. The "char" parameter runs 28 §9.2's builders, the "pack" one
-- ogpu_pack.lua's string.pack path, each putting a batch's records joined in runs of at most 4 KB (28:745) under its
-- own name. The script yields between batches, so no slice nears brain's per-slice timeout on a slow runner (S10
-- amendments I18)
local paths = {}
if h.param("char") then paths[#paths + 1] = { "char", CHAR } end
if h.param("pack") then paths[#paths + 1] = { "pack", PACK } end
local i = 1
while true do
  local batch = caseBatch(i)
  if not batch then break end
  for p = 1, #paths do
    putJoined(paths[p][1], i, batch(paths[p][2]))
  end
  i = i + 1
  computer.pullSignal(0)
end
h.put("batches", i - 1)
