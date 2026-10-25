-- The harness's Lua side (S10 plan P8.3-P8.5), loaded after ogpu_builders.lua and before the test's own file, in one
-- chunk: the two components, the boot record, a present that remembers its frame id, waitFrame, readAll,
-- createTextureRetry and putChunked. The same chunk runs on 5.2, 5.3, 5.4, LuaJ and OC-LuaJIT, so 28 §9.1's rules
-- apply here too (LuaPortabilityTest)
local h = component.proxy(component.list("ogpu_harness")())
local gpu = component.proxy(component.list("opengpu")())

-- What booted, for the rig's architecture check (S10 plan P12)
h.put("arch", computer.getArchitecture(), _VERSION)

-- The newest frame id present returned, and the newest confirmed one readPixels showed
local H = { presented = 0, confirmed = 0 }

local function bytesPerPixel(caps)
  if caps.format == "rgb565" then return 2 end
  return 1
end

-- gpu.present(), remembering the frame id it returned
local function present()
  local id, status = gpu.present()
  if id then H.presented = id end
  return id, status
end

-- The newest confirmed frame id, from a one-pixel read (24:129)
local function frameNow()
  local data, id = gpu.readPixels(0, 0, 1, 1)
  if not data then error("readPixels(0, 0, 1, 1): " .. tostring(id), 0) end
  if id > H.confirmed then H.confirmed = id end
  return id
end

-- Polls until frame f is confirmed, with computer.pullSignal(0.05) between the polls, which also takes any
-- opengpu_frame signal waiting (S10 plan P8.4; decisions D7 F11). It gives up after 400 polls; Java's bounded wait is
-- the authority
local function waitFrame(f)
  local polls = 0
  while frameNow() < f do
    polls = polls + 1
    if polls >= 400 then
      local msg = "frame " .. tostring(f) .. " not confirmed after 400 polls"
      h.finish(false, msg)
      error(msg, 0)
    end
    computer.pullSignal(0.05)
  end
end

-- The whole surface in floor(maxReadbackBytes / (width * bpp))-row chunks, each put with its first row, its row count
-- and its frame id rather than joined in Lua (28:690; S10 plan P8.4): T2 in 2 calls, T3 in 8
local function readAll(tag)
  local caps = gpu.getCaps()
  local stride = caps.width * bytesPerPixel(caps)
  local rows = math.floor(caps.maxReadbackBytes / stride)
  local y, chunks = 0, 0
  while y < caps.height do
    local n = math.min(rows, caps.height - y)
    local data, id = gpu.readPixels(0, y, caps.width, n)
    if not data then error("readPixels(0, " .. tostring(y) .. ", ...): " .. tostring(id), 0) end
    h.put("rows", tag, y, n, id, data)
    y = y + n
    chunks = chunks + 1
  end
  h.put("rowsEnd", tag, chunks)
  return chunks
end

-- createTexture under 28's H23 rule (28:1074; 24:127; S10 plan P8.5): a "busy" with a frame in flight waits for that
-- frame, one with none in flight presents first, and either retries once. Only present, the "no renderer" discard or
-- a reset clears the second kind, and no frame status follows it
local function createTextureRetry(w, ht, format)
  local tex, err = gpu.createTexture(w, ht, format)
  if tex or err ~= "busy" then return tex, err end
  frameNow()
  if H.presented > H.confirmed then
    waitFrame(H.presented)
  else
    present()
  end
  return gpu.createTexture(w, ht, format)
end

-- A long string put in 16 KB parts, numbered from 1, then name .. "End" with the part count and the length
local function putChunked(name, s)
  local size, i, part = 16384, 1, 0
  while i <= #s do
    part = part + 1
    h.put(name, part, string.sub(s, i, i + size - 1))
    i = i + size
  end
  h.put(name .. "End", part, #s)
end

-- The pieces joined in order with table.concat in runs of at most 4 KB, 28:745's rule for the encoder (S10 amendments
-- F17); a piece longer than that is a run of its own
local function joinRuns(pieces)
  local runs, run, size = {}, {}, 0
  for k = 1, #pieces do
    local p = pieces[k]
    if size > 0 and size + #p > 4096 then
      runs[#runs + 1] = table.concat(run)
      run, size = {}, 0
    end
    run[#run + 1] = p
    size = size + #p
  end
  if size > 0 then runs[#runs + 1] = table.concat(run) end
  return runs
end

-- The pieces' runs put in order, each as (name, key, its number from 1, its bytes), then name .. "End" with the key and
-- the run count
local function putJoined(name, key, pieces)
  local runs = joinRuns(pieces)
  for k = 1, #runs do h.put(name, key, k, runs[k]) end
  h.put(name .. "End", key, #runs)
end

-- Puts ("park", n), then waits in synchronized calls, one per tick, until Java lets the script go: while it waits Java
-- can look at the device, or tick by hand from a known state (S10 amendments I2)
local function park(n)
  h.put("park", n)
  repeat h.sync() until h.go()
end

-- 28 §9.2's char path as a table, with HEADER, END and string.rep, for the code case batches load, which sees no local
-- of this chunk
local CHAR = { clear = clear, fill = fill, blit = blit, text = text, HEADER = HEADER, END = END, rep = string.rep }

-- Case batch i's source loaded as text (28:744) and run, which gives the batch's function; nil past the last batch
local function caseBatch(i)
  local src = h.cases(i)
  if not src then return nil end
  local f, e = load(src, "=cases" .. tostring(i), "t")
  if not f then error("cases(" .. tostring(i) .. "): " .. tostring(e), 0) end
  return f()
end
