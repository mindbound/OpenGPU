-- The scenes of SceneOracleTest and LuaFboTest, and their pass under NoRenderHost (S10 plan P14.8; amendments F9):
-- case batch 1 is the scene, Lua source returning a function of (S, boot) that draws through S, boot being the
-- machine's boot of this run (2 after a reboot). With a renderer every present must give a frame id, and S.wait(k)
-- waits for the newest one by polling. With the "await" parameter "park" (LuaFboTest) each present holds the host's
-- pumps, so no frame renders before Java's wait for it, and S.wait(k) parks for that bounded wait first (23:188 step
-- 4; S10 plan P10.2); the scene's own holds are then no-ops. Under NoRenderHost (getCaps().renderer == "none") every
-- present must give nil, "no renderer", and S.wait(k) does nothing
local rendering = gpu.getCaps().renderer ~= "none"
local parking = h.param("await") == "park"
local T = {}
local S = { E = CHAR }

function S.create(name, w, ht)
  local t, why = gpu.createTexture(w, ht)
  if not t then error("createTexture(" .. name .. "): " .. tostring(why), 0) end
  T[name] = t
  h.put("created", name, t)
end

function S.tex(name) return T[name] end

-- The data comes from ogpu_harness's blob of that name
function S.update(name, x, y, w, ht, blob)
  local ok, why = gpu.updateTexture(T[name], x, y, w, ht, h.blob(blob))
  if not ok then error("updateTexture(" .. name .. "): " .. tostring(why), 0) end
end

function S.free(name)
  local ok, why = gpu.free(T[name])
  if not ok then error("free(" .. name .. "): " .. tostring(why), 0) end
end

-- A segment of the records, joined in runs of at most 4 KB first (28:745)
function S.segment(records)
  return HEADER .. concat(joinRuns(records)) .. END
end

function S.submit(s)
  local ok, why = gpu.submit(s)
  if not ok then error("submit: " .. tostring(why), 0) end
end

function S.present()
  if parking then h.hold(true) end
  local id, status = present()
  h.put("presented", id, status)
  if rendering then
    if not id then error("present: " .. tostring(status), 0) end
  elseif id or status ~= "no renderer" then
    error("present without a renderer gave " .. tostring(id) .. ", " .. tostring(status), 0)
  end
end

-- Await k: Java releases the pumps when its wait for the frame begins, and the script then polls until the device has
-- folded the frame, so the next read shows it
function S.wait(k)
  if parking then
    h.put("await", k, H.presented)
    park(k)
    waitFrame(H.presented)
  elseif rendering then
    waitFrame(H.presented)
  end
end

S.hold = function(on) if not parking then h.hold(on) end end
S.readAll = readAll

function S.reboot()
  h.put("reboot", h.boot())
  computer.shutdown(true)
end

local scene = caseBatch(1)
scene(S, h.boot())
