-- NoRendererTest's scripted parts (RM:65; 28:106, :204; S10 plan P14.4 (a)-(c), (f)) on NoRenderHost, picked by the
-- "case" parameter; "colour" is a valid colour of the device's format. The region rows and the validation parity run
-- through calls.lua. (a) and (f) wait in park(1) after their record, so Java sees the device before the shutdown's
-- computer.stopped resets it
local case = h.param("case")
local caps = gpu.getCaps()
h.put("caps", caps.renderer, caps.tier, caps.width, caps.height)

if case == "present" then
  -- (a) A valid frame, with a free deferred while it is open: present discards the frame and appends the free, and the
  -- slot is free at once (28:106)
  local tex, whyTex = gpu.createTexture(16, 16)
  local submitted, why = gpu.submit(HEADER .. fill(0, 0, 8, 8, h.param("colour")) .. END)
  local freed, whyFree = gpu.free(tex)
  local id, status = gpu.present()
  h.put("present", tex, whyTex, submitted, why, freed, whyFree, id, status)
  park(1)
elseif case == "readAll" then
  -- (b), (c) The whole surface filled and presented first, so that a host that rendered would show it (S10 amendments
  -- I16); then the surface in readAll's chunks
  local submitted, why = gpu.submit(HEADER .. fill(0, 0, caps.width, caps.height, h.param("colour")) .. END)
  local id, status = gpu.present()
  h.put("present", submitted, why, id, status)
  readAll("screen")
elseif case == "h23" then
  -- (f) H23 (28:1074; 24:127): with live and deferred textures at the cap and no frame in flight, createTexture is
  -- "busy" until a present; createTextureRetry presents ("no renderer", which appends the deferred free) and retries
  local tex = {}
  for k = 1, caps.maxTextures do
    local t, why = gpu.createTexture(1, 1)
    if not t then error("createTexture " .. tostring(k) .. ": " .. tostring(why), 0) end
    tex[k] = t
  end
  assert(gpu.submit(HEADER .. fill(0, 0, 1, 1, h.param("colour")) .. END))
  assert(gpu.free(tex[1]))
  local none, busy = gpu.createTexture(1, 1)
  local t, why = createTextureRetry(1, 1)
  if not t then error("createTextureRetry: " .. tostring(why), 0) end
  h.put("h23", none, busy, t, H.presented, H.confirmed)
  park(1)
else
  error("no_renderer.lua: no case " .. tostring(case), 0)
end
