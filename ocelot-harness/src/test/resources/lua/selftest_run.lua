-- Runs the self-test program in the harness (S13 plan P6.2): its text, opengpu_selftest.lua as committed, comes in as
-- the blob "selftest" and is loaded unchanged, as source only, in an environment over _G whose print puts each line as
-- ("print", line), since the BIOS's machine has no print. Nothing else of OpenOS is faked: the program finds the card
-- through the sandbox's component and computer. A load error or a Lua error fails the script through the BIOS
local src = h.blob("selftest")
if not src then error("no selftest blob", 0) end
local env = setmetatable({ print = function(line) h.put("print", tostring(line)) end }, { __index = _G })
local program, e = load(src, "=opengpu_selftest.lua", "t", env)
if not program then error("load: " .. tostring(e), 0) end
program()
