-- 28 §9.4's worked frame on a new device (S10 plan P14.3), as 28:819-827 writes it for T2 and with 28:842's operands
-- for T3, but for its first line: the harness's gpu proxy stands for component.opengpu, which the bare BIOS
-- environment has no library for. The sprite comes from ogpu_harness, 256 B of index8 or 512 B of rgb565. Under
-- NoRenderHost the present returns nil, "no renderer"; the frame's bytes, its handle and both results are put
local sprite = h.blob("sprite")
local tex = gpu.createTexture(16, 16)            -- 0x08000000 on a new device
gpu.updateTexture(tex, 0, 0, 16, 16, sprite)     -- sprite: rows top first
local s
if h.param("tier") == 2 then
  s = concat({HEADER, clear(0), fill(10, 20, 100, 50, 196),
              blit(tex, 0, 0, 16, 16, 150, 40, 1), text(8, 180, 255, 0, 1, 0, "Hi"), END})
else
  s = concat({HEADER, clear(0xFF000000), fill(10, 20, 100, 50, 0xFFFF8000),
              blit(tex, 0, 0, 16, 16, 150, 40, 1), text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi"), END})
end
assert(#s == 64)
local submitted, why = gpu.submit(s)
local frameId, status = gpu.present()
h.put("frame", tex, s, submitted, why, frameId, status)
