# 28. Command stream v1

Drafter, 2026-10-14 (M0 step S5, RM:51), revised the same day against three critiques (fidelity, implementability, Lua and forward compatibility), each finding checked against the sources; for owner review. **Status: accepted by the owner on 2026-10-14 with the defaults of Q2–Q13** (§12), so its [proposal] items are decided, and Q1 (§7.1) was answered the same day after a font survey (29): font 0 is X11 misc-fixed 6x13; on 2026-10-15 the owner took 29's F4, a `font` byte in `TEXT` (§5.2), and deferred its F5, the advance of wide codepoints, to M1 (§4.3), where it was decided the same day (section "F5 decided"); the tags are kept as written to show where each rule came from, and what it cites as decided stays decided. It specifies the byte-string command stream v1 that 24 §2.1 carries over from 09 §3.2 (24:30), the M0 callbacks that carry it, the records and the SPI it travels through, and the minimal texture path the owner added to M0 on 2026-10-14 (24:248; RM:35). S6 (the `:core` codec, validator and tier-E Java oracle), S7 (`Device`, the `RenderHost` SPI, `NoRenderHost`, `FakeRenderHost`) and S8 (the first `:gl` pass) are built from it (RM:52-54). Inputs: 24 (the decision document), 20–23 and 25–27, 09 §3.2 where 24 §2.1 carries it, 08-C §3 (the only concrete opcode table), 04, 07, 10, 11, 12, and the code cited.

Citations: `NN §x` for report sections; `NN:L` for line L of `docs/research/NN-*.md` and `RM:L` for `docs/roadmap.md`, both in the working tree of 2026-10-14 (its uncommitted edits, those of 2026-10-15 included, move no cited line except 24's from 337 on, one below HEAD's because of 24's §9 row for this note); `OCT/` = `C:\Users\astro\Downloads\opengpu-refs\ocgtnh-master\src\main\` (tag 1.12.64-GTNH, 1e4559f); `OCLJ/` = `C:\Users\astro\Downloads\OC-LuaJIT\` (62970a0), `LJ/` = `OCLJ/prototype/watchdog/luajit/src/` (1ee778a4), and `LUA53/`, `LUA54/` = the stock sources `OCLJ/bench/vendor/lua-5.3.6/src/` and `OCLJ/bench/vendor/lua-5.4.8/src/`; `ANG8/` = `C:\Users\astro\Downloads\Angelica\` (tag 2.2.8) and `ANG32/` = `C:\Users\astro\Downloads\opengpu-refs\angelica-git` at tag 2.2.32; `GL33:L` = line L of `C:\Users\astro\Downloads\opengpu-refs\web22\gl33core.txt` (GL 3.3 core specification text); `LWJGL/` = `lwjgl-2.9.4-nightly-20150209-sources.jar` in the Gradle cache; repository paths as written; scripts in `cmdstream-checks/` next to this note (own code, MIT with the repo, Python 3 standard library only), run with `python -I`. Sizes: KB = 1 024 B and MB = 1 048 576 B; §8.1 gives every cap in bytes. Tags: **[D]** decided by the owner or 24 (directly, or by text 24 carries); **[R]** an earlier report's proposal, adopted here; **[proposal]** added by this note beyond 24 and 20–27; **[M]** measured, or verified in source or by a script; **[I]** inference; **[E]** estimate. The owner's questions are in §12, each with its default and its status of 2026-10-14.

## Summary

1. **One self-describing segment per `submit`.** Every `submit` string is a complete stream: the 8-byte `OGPU` header (magic, command-format version, flags, reserved), byte-packed little-endian records (u8 opcode, fixed operands, at most one counted tail), and a mandatory `0xFF` `END` as the last byte. The magic, the version byte, little-endian order and `0xFF` `END` are 09 §3.2's, which 24 carries [D] (09:67; 24:30). Unknown opcodes, truncation, trailing bytes and non-zero reserved fields reject the whole submit; nothing is ever skipped.
2. **Validation is whole, atomic and ordered.** A submit is validated completely before its array joins the open frame, and a rejected submit changes nothing. Checks run in one normative order with one closed set of error codes (§3.6), so two validators agree on the code and the offset. Errors return `nil, "<offset>: <OP>: <reason>"`.
3. **M0 opcodes:** `CLEAR` 0x01 (5 B), `FILL_RECT` 0x30 (13 B), `BLIT` 0x31 (18 B), `TEXT` 0x33 (17 B + string), `END` 0xFF. Their pixel results are defined per surface format, exactly enough for a tier-E oracle.
4. **Colours are one u32 whose meaning follows the surface format:** an index 0–255 on index8, `0xFFRRGGBB` on rgb565, quantized on the CPU by 24 §3.7's round-to-nearest `q`. By default M0's host writes R8UI/R16UI directly (Q10); M1's RGBA8 path gives the same bytes for every M0 stream, because `q(expand(v)) = v`.
5. **Textures are callbacks, not records.** `createTexture`, `updateTexture` and `free` produce records that are offered to the host in call order. Updates made before `present` are seen by the whole open frame, and frees wait until after it. The server's tail keeps only what follows the first unconfirmed `FRAME`: resource records ahead of it fold into the masters at once. Texture handles are G1 type 1; types 2–7 are reserved for M2–M3's kinds.
6. **Only `FRAME` records are ever skippable.** Resource, palette and dither records are separate records, so M1's frame skipping never loses an upload or a free.
7. **Lifecycle.** A computer stop or start offers `RESET` carrying the confirmed pixels, so the host and the oracle agree whatever the host had already drawn. Card removal and chunk unload `release` the host's objects; from M1 the device's state persists and replays under a new epoch (24:181). The SPI gains `open(key, surface, sink)`, which tells a host each device's surface before the first record.
8. **Versioning.** One u8 command-format version per segment, which is also the `-api` constant, the `getCaps()` key and what side files dispatch on. v1 can be edited in place until M1's release; after that, breaking changes bump the version, old decoders stay, and `submit` accepts every version the mod decodes. Additive changes (new opcodes in reserved ranges, newly defined reserved bits, relaxed checks) never bump it.
9. **TEXT** decodes UTF-8 into the cells of the built-in font its `font` byte names; M0 defines only font 0, with 6×13 cells, one per codepoint (29 F4 taken, and F5 deferred to M1 and decided for it, 2026-10-15). Its pixels are a function of the record and a glyph table whose bitmaps the protocol does not name. M0 ships font 0, X11 misc-fixed 6x13 (public domain), inside OpenGPU's jar (Q1, answered 2026-10-14); CI uses a synthetic table.
10. **Caps** [proposal, provisional until M0's measurements]:
    - 65 536 B per submit;
    - per frame: 64/128/256 KB, 4 096 records, and fill up to 16 times the surface area;
    - VRAM 256 KB/1 MB/4 MB, which also bounds the resource bytes queued in the tail;
    - textures ≤ 1 024 per side, ≤ 256 live.
    Budget charges are 09 §3.2's, carried by 24 §2.1; uploads use `bitblt`'s two-phase stall, and no charge is computed from an unchecked size.

## 1. Status and scope

### 1.1 What v1 covers

| Milestone | In v1 as specified here | Reserved only (numbers, kinds, bits; layouts decided then) |
|---|---|---|
| M0 | header, framing, error codes, version 1; `CLEAR`, `FILL_RECT`, `BLIT`, `TEXT`, `END`; the colour operand for index8 and rgb565; records `FRAME`, `TEX_CREATE`, `TEX_UPDATE`, `TEX_FREE`, `RESET`; the SPI with `open`; handle type 1 (texture); format codes 1–2; the surface byte layout | — |
| M1 | — | `SCISSOR` 0x03, `LINE` 0x32, `COPY` 0x34, circles 0x35; `PALETTE` and `DITHER` records; `INIT`; T3 alpha < 255 for blending; integer-scale `BLIT` and `TEXT`, ×1–×4 in flags bits 1–2 (tier E, 23:137; 29 F8, decided 2026-10-15); frame/canvas modes; per-class energy (24:249); capability discovery (§4.3) |
| M2 | — | 0x02 and 0x04–0x2F (viewport, depth/stencil clear, binds, target, camera, globals, uniform rows, `DRAW`, `DRAW_INDEXED`; 24:137, :251); handle types 2–5; texture formats ≥ 3 and the `BLIT` source formats they bring [D: owner, 2026-10-14, 24:248]; 16.16 or f32 operands (24:137) |
| M3 | — | 0x40–0x4F (expected to stay empty: `dispatch` is a callback, 24:139); handle types 6–7; the server-only `UPLOAD` record (24:105) |
| M4 | — | `CMDS`/`RESOURCE` packet bodies stay reserved for option (b) (24:63, :232) |

M0 builds T2 (320×200 index8) and T3 (640×400 rgb565) only [D] (24:248). T1 (160×100 index8) joins in M1 under the same index8 rules (24:249), so the codec takes the surface format and size as parameters, never a tier. M0 runs every presented frame in order, and each frame starts from the surface as the previous one left it: canvas semantics. `"superseded"` never occurs in M0, because frame mode and `setFramePolicy` are M1 [D] (24:128, :249).

### 1.2 What S6, S7, S8 and S10 build from it

| Step | Builds | From |
|---|---|---|
| S6 | `:core` codec (opcode table as data, reader, validator, the error codes of §3.6), the colour and quantize functions, the tier-E oracle in `:core`'s test fixtures, the test encoder, the synthetic glyph table | §3–§5, §7.1–7.4, §8.1 (admission caps), §11 |
| S7 | `Device` (live view, open frame, offered sequence, server tail, confirmed state, fold, lifecycle), the SPI with `open`, `NoRenderHost`, `FakeRenderHost` with its record renderer, and `Device`'s methods behind the M0 callbacks with their checks, results and charge amounts; the `@Callback` layer that applies the charges through OC's `Context` stays in the root project (24:80, :85) | §2, §3.6 (callback results), §6.1–6.5, §8.2 |
| S8 | the first `:gl` pass on `:gl-testkit`: the four ops into R8UI/R16UI, textures, glyphs, `RESET`, G8 around uploads, `GlLedger` rows, compared byte for byte with the oracle | §5 host notes, §6.6–6.7, §7.5 |
| S10 | the harness's Lua encoder and the Lua bytes == FBO bytes test (RM:65) | §9, §11.1 |

### 1.3 Conflicts this note resolves

| # | Conflict | Resolution | Status |
|---|---|---|---|
| C1 | Header: 08-C has 8 B (`OGPU`, version, flags, u16 reserved, 08-C:115); 08-B has 5 B (08-B:128) | 8 B. Not a live conflict: 09 picked 08-C's format (09:67), and 08-B is a superseded proposal | [D: 09:67 via 24:30]; the must-be-zero rules [proposal] |
| C2 | `END`: 0xFF (08-C:129) or 0x00 (08-B:131) | 0xFF; 0x00 is never assigned, so a zero-filled buffer fails at its first record | [D: 09:67] for 0xFF; [proposal] for 0x00 |
| C3 | Opcode numbers differ between 08-A, 08-B and 08-C | 08-C's (0x01, 0x30, 0x31, 0x33), which 09 carries; M1's numbers are reserved now, so no number ever moves (§4.4) | [D: 09:67]; the reservations [proposal] |
| C4 | "Every operand fixed-size" (08-C:112), yet `TEXT`, `UNIFORMS_*`, `DISPATCH` and `WRITE_BUFFER` are counted (08-C:120-128) | A fixed head per opcode plus at most one tail counted by a field of that head, checked before it is read (§3.2) | [proposal] |
| C5 | `CLEAR` with depth and mask (08-C:116) before any depth target exists | Colour only; depth/stencil clears get their own M2 opcode (layouts never change inside a version, §4.3) | [proposal: changes 08-C's layout as 09:67 carries it; Q13] |
| C6 | Colour model: u32 ARGB everywhere, quantized at present (08-C:116-125, :227), against direct index writes (20:158; 26:371) | One u32 whose meaning follows the surface format (§3.4) | [proposal; Q3] |
| C7 | 24 writes M0's 2D "into R8UI/R16UI" (24:248) but says the surfaces "are written by a quantize pass" (24:197); 20 §3 writes unblended 2D directly and sends 3D and blended T3 2D through RGBA8 (20:158-162) | M0's opaque ops write the integer surface directly, with the operand quantized on the CPU by `q`. The quantize pass is the RGBA8 path for M1's T3 blending and M2's 3D. Both give the same bytes because `q(expand(v)) = v` [M] (§3.4). Index8's direct path survives M1. T3's does not: M1's T3 draws into an RGBA8 canvas that persists between presents and is quantized once per present (26:132; 24:280), so S8's T3 op path is retargeted in M1 [I] | [proposal: chooses one reading of 24:197 for M0; Q10] |
| C8 | A T2 texture "in the surface's own format" reads as R8UI, but user-visible index8 textures are normalized `GL_R8`, a rule 26:93 and 24:249 place in M1, and R8UI surfaces stay private (24:205) | The wire format is the OpenGPU enum `index8`; the host stores it as `GL_R8` from M0, which is exact (§6.6) and leaves M1 and M2 nothing to migrate. rgb565 textures are `GL_R16UI` | [proposal: moves 24:205's rule from M1 to M0; Q11] |
| C9 | Dirty rects upload with `GL_UNPACK_ROW_LENGTH` (21:137), but G8 forces `ROW_LENGTH` to 0 (24:203) | Payloads are tightly packed regions, so `ROW_LENGTH` 0 is always correct | [D: 24:203 governs] |
| C10 | G8 forces `SWAP_BYTES`/`LSB_FIRST` "on compatibility contexts" only (24:203; 26:80) | Both contexts: GL 3.3 core defines both (GL33:6388-6389, :11104-11105), GLSM tracks the unpack pair (`ANG8/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/states/PixelUnpackState.java:35-36`), and a stray `PACK_SWAP_BYTES` would swap R16UI readback [I] | [proposal: extends 24:203; Q13] |
| C11 | "GLSM caches only unpack state" (24:203; 26:82) | Holds through 2.2.28. 2.2.32's `glPixelStorei` caches pack state too (`ANG32/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:7000-7008`); the rule (save pack state only when a readback is queued) stands | [M]; the rule [D: 24:203] |
| C12 | 21:52 and 24:170 give the SPI method the Lua callback's name, `submit` | The SPI method is `offer`, as 24's diagram already writes (24:99) | [proposal: renames 24:170's method; Q13] |
| C13 | The worker appends "an `UploadOp` or `FrameOp`" at each call (21:80), while `present` seals the open frame (24:99; 08-C:78-79) | Resource records enter at call time; a frame enters only at `present` (§2.2) | [proposal, following 24:99] |
| C14 | The worker copies the argument array (09:95; 08-C:205), or owns it without copying (21:120-128; 24:179) | No copy, and the validator never writes into the array | [D: 24:179] |
| C15 | `RESOURCE` records carry kind and generation beside the handle (08-C:212; 21:215), which G1 already embeds | The handle carries both; the content hash is left out until a consumer exists, because it is derived from the payload and so additive (§2.3) | [proposal] |
| C16 | `DISPATCH`/`WRITE_BUFFER` as stream opcodes (08-C:127-128; 09:67) or `dispatch` as a callback (24:139) | No compute opcode in v1; 0x40–0x4F stay reserved | [D: 24:139] |
| C17 | Everything Lua encodes is an integer or 16.16 (09:67), yet std140 float rows arrive in M2 (24:136) | The M0 set is integer-only; 16.16 and f32 are defined when M2 adds their first operand, and 24:137 leaves the choice to M2 (§3.3) | [D: 24:137 for the deferral] |
| C18 | Per-submit caps 64/128/256 KB (08-C:134), none in 24 | 65 536 B per submit, so one call stays inside the worker's 0.2 ms (24:34) [E]; 08-C's tier values become the per-frame byte caps (§8.1) | [proposal; Q5] |
| C19 | VRAM caps "follow the pack's `vramSizes`" (09:144, a question default in 09's superseded §5), which multiplies OC's text cells (`OCT/scala/li/cil/oc/server/component/GraphicsCard.scala:74`) | Absolute byte caps, 256 KB/1 MB/4 MB | [R: 07:116-118; 08-C:231-233] |
| C20 | Detecting OC-LuaJIT by feature probes (04:84; 09:67), or by name (24:30) | By name, for every runtime (§9.3) | [D: 24:30] |
| C21 | 07 says never rely on `string.pack` (07:137); 09 and 24 add a fast path (09:67; 24:139) | A fast path on `"Lua 5.3"`/`"Lua 5.4"` only, and only if M0 measures it faster (§9.3) | [proposal] |
| C22 | No-renderer readback returns `nil, reason` (23:164), or the confirmed image (24:87) | The confirmed image | [D: 24's Review fix 4, 24:346] |
| C23 | `FakeRenderHost` returns synthetic pixels (23:165) | It takes a pluggable record renderer: zeros by default, the tier-E oracle in tests (§2.4) | [proposal] |
| C24 | Stale citations: 24:30 and :467 cite `LuaJITArchitecture.java:82`; 24:514 cites 23 §5.2 for "a sprite readback" | `@Architecture.Name` was at `:82` at aa12418, the commit RM:223 names, and moved to `:85` at 62970a0 (`OCLJ/src/main/java/io/github/astronfo/ocluajit/arch/LuaJITArchitecture.java:85`). The sprite is the M0 self-test's (24:248; RM:40); 23 §5.2 names tier scenes (23:236) | [M] |
| C25 | After a computer stop or start the host frees resources "and keeps the target" (21:107), but frames it executed after K stay on that target, so host and server diverge | `RESET` carries K's pixels, and the host restores them (§2.2) | [proposal; Q12] |
| C26 | 24's M1 row has the `index8`/`GL_R8` rule arrive "as `BLIT` sources gain formats" (24:249); the owner's 2026-10-14 decision keeps the other formats in M2 (24:248; RM:35) | Other `BLIT` source formats are M2's | [D: owner, 24:248] |
| C27 | An upload "arriving when the tail contains no frame folds immediately" (21:229), but nothing folds resource records left at the tail's head after a readback | They fold right after the readback's fold (O6), so the server tail always starts with a `FRAME` | [proposal] |

## 2. Where the stream sits

### 2.1 The M0 callbacks

All callbacks are `direct = true` except `bind` [D] (09:54). Handles cross as `Integer` and are read with `checkInteger` (24:30; 08-C:134). Byte strings are read with `checkByteArray`, which hands over the fresh per-call array itself (`OCT/scala/li/cil/oc/server/machine/ArgumentsImpl.scala:179-185`; 21:120-127) [M]. `checkInteger` raises on NaN, saturates values beyond the `int` range to `Integer.MAX_VALUE`/`MIN_VALUE` and truncates fractions toward zero (`ArgumentsImpl.scala:60-112`) [M]. Every check below runs on the values it returns, so 16.7 means 16 and 10^10 means 2 147 483 647.

**Order inside a callback** [proposal]:
1. argument and range checks, which read only the arguments and the live view and have no side effect;
2. the budget charge (§8.2), which may throw `LimitReached`;
3. the effect: live-view changes, then records appended and offered.

So charges precede every side effect, and the `LimitReached` retry, which repeats the checks, is idempotent [D] (09:54; 21:133). No charge is computed from an unchecked size (§8.2). `submit` charges before it validates, because validation is its expensive part and its charge depends only on min(`#s`, 65 536) (§3.5). `present` charges before deciding its status, so a loop that spins on `"busy"` pays for it.

| Callback | Returns | Effect | Status |
|---|---|---|---|
| `bind(address)` | `true` or `nil, msg` | maps a display to this device; rendering does not depend on it (21:63); its messages belong to the display block, not to S5 | [D] (24:248) |
| `getCaps()` | small table | 24 §3.3's keys (24:125) plus the §8.1 keys | [D] + [proposal] |
| `submit(stream)` | `true` or `nil, msg` | validates the whole segment (§3.5), then appends the array to the open frame | [D] (24:248), rules [proposal] |
| `present()` | `frameId` or `nil, status` | seals the open frame into `FRAME(frameId)`, appends and offers it, then appends and offers the frees deferred while it was open; `"busy"` at the third unconfirmed frame, `"no renderer"`, `"host render stalled"` (24:126) | [D] + rules [proposal] |
| `readPixels(x, y, w, h)` | `data, frameId` or `nil, msg` | a region of the newest confirmed frame in the surface byte layout (§5.7), checked as §3.6 lists; at most 16 384/32 768/65 536 B per call by tier, an error beyond, never truncated (09:62; 24:129) | [D] semantics; signature and checks [proposal] |
| `createTexture(w, h[, format])` | handle or `nil, msg` | §6.2 | [D] (owner, 2026-10-14); API [proposal] |
| `updateTexture(tex, x, y, w, h, data)` | `true` or `nil, msg` | §6.3 (09:59's `updateTexture`) | [D] + [proposal] |
| `free(tex)` | `true` or `nil, msg` | §6.4 | [D] + [proposal] |

`readPixels` drops 09's `target`, `fmt` and `maxBytes` (09:62) for v1: there is one format per surface, and offscreen render targets are GPU-only in v1, which 24 marks as its own [proposal] (24:129). Later arguments are appended as optional trailing ones [proposal; Q13]. `present`:
- on `"busy"` and `"host render stalled"`, it leaves the open frame intact, so the program can retry;
- on `"no renderer"`, it discards the open frame and appends its deferred frees, because nothing will ever execute that frame and a loop on a dedicated server must not grow it [proposal].

`present` without any submit since the last one is legal and produces an empty `FRAME` (M1's expand-only presents rely on that, 24:130). `frameId` is a `Long`, starts after the cleared image's 0 (24:129), grows by 1 per successful present and is never reused in the device's lifetime [proposal]. Not in M0: 09's `present(bytes)`, `present([flags])`, `getFrameStatus`, `abort`, `setMode`, `freeAll`, `handles` and `getMemory` (09:59-60); each is additive later.

**Frame statuses in M0** (`opengpu_frame`, 24:127) [D]:
- `"done"`: the frame is confirmed server-side (the fold, O6);
- `"dropped"`: still unconfirmed 1 s after `present`. M0 has canvas semantics only, so the frame stays queued, its pixels still land, and the next `"done"` covers it (24:266, accepted at 24:277);
- `"superseded"` needs frame mode (M1), and `"no renderer"` arises as a signal only from M1's load without a renderer (24:181); in M0 `present` returns `"no renderer"` at once.

Frames discarded by an epoch change (§2.2) get no signal, because the same event clears the card's context (09:65, carried by 24:32) [I].

### 2.2 Device state and ordering

Per device, S7 keeps four pieces of state [D shape, 24:179; the split, proposal]:
- the **live view**: what validation sees, namely the surface format and size, the caps, the live handle table, VRAM use and the queued resource bytes;
- the **open frame**: segments validated since the last present, their running totals, and the frees deferred while it is open;
- the **server tail**: the records after the confirmed frame K, always starting with a `FRAME` when it is not empty;
- the **confirmed state**: the pixels of K, and the texture masters, which hold every resource record that precedes the server tail.

Two sequences must be told apart [proposal]:
- the **offered sequence** is every record in the order `Device` offers it to the host, one offer per record, at the moment it is appended;
- the **server tail** is what the server still needs to replay. A resource record that no `FRAME` precedes leaves it at once, by folding into the masters (O6), but it is offered all the same.

The open frame exists from the first accepted submit after a present until the next successful present, or until its discard on `"no renderer"` or an epoch change.

Ordering rules [proposal unless cited]:
- **O1.** One device's records are offered in call order; there is no order across devices [D] (21:94).
- **O2.** `submit` never touches the tail. A frame enters it only at `present`, whole, so no partial frame is ever offered [D] (24:99; 08-C:78-79).
- **O3.** `createTexture` and `updateTexture` append and offer at call time. Records offered earlier precede them; the open frame and every later frame follow them.
- **O4.** `free` makes the handle invalid for validation at once and credits its VRAM at once. Its `TEX_FREE` is appended at call time when no frame is open; when one is open, it is appended right after that frame's `FRAME` at present, or when the open frame is discarded. A validated frame therefore never references a freed resource.
- **O5.** The host executes a device's records strictly in offered order. In M1's frame mode only `FRAME` records may be skipped (24:127-128); resource, palette and dither records always execute, because they are separate records.
- **O6.** The fold for readback N applies the server tail's records up to and including `FRAME N`, in order, to the masters (create zeroed, update row-wise, free drop), then replaces the pixels and sets K = N [D] (24:179; 21:229-231). Right after that fold, and whenever a resource record is appended, the resource records ahead of the tail's first `FRAME` fold at once; with no `FRAME` in the tail, that is 21:229's "folds immediately". The masters thus lag exactly as 21:231 requires: they hold the state that the first unconfirmed frame starts from.
- **O7.** The host and the masters key resources by the full 31-bit handle, never by slot, so a slot reused while an older generation is still referenced in the tail cannot collide.

**The tail bound** [D: 24:179] is at most 2 unconfirmed frames, plus the open frame under its caps (§8.1), plus the resource bytes queued in the server tail, which may not exceed the VRAM cap. The queued resource bytes count [proposal] 64 B per resource record in the server tail, plus w·h·bpp for a `TEX_CREATE` (the host uploads zeros) or `#data` for a `TEX_UPDATE`. `createTexture` and `updateTexture` return `"busy"` beyond the cap. `free` is never refused: every `TEX_FREE` needs a create that was counted (corrected in "S7 implemented", H23: up to 256 `TEX_FREE`s can pass the cap by 16 384 B, so the bound below is 256 records more). That bounds the server tail to 4 096/16 384/65 536 resource records at T1–T3 [M: `cmdstream-checks/rev_checks.py`].

Examples, with t created and folded beforehand:
- `submit(A)` (A blits t), `updateTexture(t, …)`, `submit(B)` (B blits t), `free(t)`, `present()` on a device whose tail is empty. Offered: `TEX_UPDATE(t)`, `FRAME(A, B)`, `TEX_FREE(t)`. Server tail after `present`: `FRAME(A, B)`, `TEX_FREE(t)`, since `TEX_UPDATE` folded at once. Both blits show the update. This is WebGPU's queue order, where writes precede later submits [I].
- Two frames in flight: tail `FRAME 5` (blits t), then `updateTexture(t, …)` and a present. Offered and tail: `FRAME 5`, `TEX_UPDATE(t)`, `FRAME 6`. Frame 5 shows the old contents and frame 6 the new ones, on the host and on replay. Readback 5 folds `FRAME 5`, then the `TEX_UPDATE` now at the head (21:231).

**Lifecycle** [proposal unless cited; Q12]. 21 §2.5 has two events that change the epoch, and M1 adds a third:

| Event | Server side | To the host | Host |
|---|---|---|---|
| `computer.stopped` or `computer.started` (21:107) | epoch++; the open frame, with its deferred frees, and every `FRAME` in the tail are discarded; every live handle is freed with a generation bump (handles reset, 21:107; 09:102); the tail's resource records and the masters are cleared, and VRAM use and queued bytes drop to 0; the confirmed pixels and K are kept (21:107) | `RESET(epoch, a copy of K's pixels)` | deletes every texture of the device, overwrites its surface with the pixels, and keeps the surface object (21:107: "free resources and keep the target") |
| card removed or moved; the computer's chunk unloads (21:106) | epoch++; the context is cleared; `release(key)` is called. In M0 the `Device` and its state are then dropped, since nothing persists before M1 (24:181), and the card's next environment is a new device that starts cleared at `frameId = 0`. From M1 the tail, the open frame and its deferred frees, the handle table, the masters and the confirmed pixels persist with the card's side file (21:106) | `release(key)`, an SPI call, not a record | deletes every GL object of the device, its surface included, at its next START step (d) (21:88); drops records still queued for the key and late readbacks (21:106) |
| load (M1; 24:181; 21:240) | a new epoch; `open(key, …)`; the masters and the confirmed pixels are re-sent; the tail is replayed under the new epoch, with its records re-stamped | `open`, `INIT` (or `RESET` plus `TEX_*` records from the masters), then the tail | as for any device |
| the display's chunk unloads (21:108) | the display is unbound; the device lives on | — | drops the `displayId` mapping |

`RESET` carries the pixels because the host may already have executed frames after K when the epoch changes. Keeping its surface as it stands would leave those discarded frames' pixels on it, and the next readback would differ from the oracle's. Restoring K makes the result independent of how far the host got. A host may skip records whose epoch is below the newest epoch offered for the device, an optimization it can read from a per-device field at dequeue; `RESET` makes the result the same either way. Readbacks of older epochs are dropped by `Device`'s epoch check (21:91, :106).

### 2.3 Records

Records are Java values that hold only ids, numbers and byte arrays, never a `Machine`, tile entity, `World` or `Context` [R: 21:130]. In single player they travel by reference (21:215). Their serialized form belongs to M1's side-file spec (RM:82), within the constraints listed under Persisted. Kind numbers are a separate namespace from opcodes, and Lua can produce records only through callbacks, so a stream cannot forge server-only kinds [proposal].

| Kind | Name | Produced by | Body | Skippable (M1) | Persisted (M1) | From |
|---|---|---|---|---|---|---|
| 1 | `FRAME` | `present` | `frameId`; segments (`byte[][]`, each a whole validated stream); per-class pixel totals | yes, in frame mode | yes, segments verbatim | M0 |
| 2 | `TEX_CREATE` | `createTexture` | handle, w, h, format | no | yes | M0 |
| 3 | `TEX_UPDATE` | `updateTexture` | handle, x, y, w, h, payload (the argument array) | no | yes | M0 |
| 4 | `TEX_FREE` | `free` (O4) | handle | no | yes | M0 |
| 5 | `PALETTE` | `setPalette` | entries | no | yes | reserved, M1 (24:130) |
| 6 | `DITHER` | `setDither` | mode, matrix | no | yes | reserved, M1 (24:131) |
| 7 | `INIT` | load, eviction (server only) | surface pixels; textures re-sent as `TEX_CREATE` + `TEX_UPDATE` from the masters, spread by the creation budget (24:173, :181). M1 may define it as `RESET` followed by those records | no | no (derived) | reserved, M1 |
| 8 | `UPLOAD` | compute output (server only) | handle, range, payload | no | yes | reserved, M3 (24:105) |
| 9 | `RESET` | `computer.stopped`/`started` (§2.2) | epoch; surface pixels, a copy of K's in the §5.7 layout | — | no (SPI only) | M0 |

Every record carries the device's epoch. The `DeviceKey` (session, deviceId) is the `offer` argument (21:63), and only `FRAME` has a `frameId`. 21's per-op `generation` (21:94) is the display generation of LAN packets (24:33), so records do not carry it [proposal]. `TEX_UPDATE` is 24's `RESOURCE` upload record (24:232):
- the handle carries its generation (G1);
- the rect is (x, y, w, h);
- the content hash is omitted until a consumer exists (option (b), parked, RM:281). A hash is derived from the payload, so adding it later is additive. CRC32 costs 0.010 ms per 512 KB (12:14) [M, by 12].

The `TEX_*` bodies, their format codes and the handle-type codes belong to command format v1 and are versioned with it (§4.2).

### 2.4 The SPI and the executors

[proposal: 21 §1.3's shape (21:52-56), with `submit` renamed `offer`, `open` added, and one record type]

```java
public interface RenderHost {                       // :core, common code
    boolean available();
    void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink);   // once per device, before any offer
    void offer(DeviceKey key, Record record);       // one queue offer plus "device runnable"; never blocks (21:80) (S8's GL host: at most one render-thread lock hold, H56, "S8 implemented")
    void release(DeviceKey key);
    void bindDisplay(String displayId, DeviceKey key);
    void releaseSession(long session);
}
public final class SurfaceDesc { public final int tier, width, height, format; }   // format: 1 index8, 2 rgb565 (§6.2)
public interface ReadbackSink {                     // one per device, implemented by Device; non-blocking (21:56)
    void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels);   // §5.7 layout
}
```

- **`open`.** `Device` calls it once, at creation, on the server thread where it captures its host (21:63), and before any `offer`. It never blocks and does no GL work. The host allocates the surface at its next START and clears it to 0 with the clear quad (24:197), which is the cleared `frameId = 0` image (24:129; 21:242). `open` and `release` of one key take effect in call order, and a key may be opened again after its release (M1's chunk reload) with a higher epoch.
- **Why no host caps.** 23's sketch had `HostCaps caps()` (23:173). `getCaps()` answers from server state (24:133 (a)), so the SPI returns no host data [I].

**Validation** runs on the OC worker inside the 0.2 ms per-call budget (24:34). The executors re-read segments with the same `:core` reader, which trusts validated input and asserts its invariants in dev builds [proposal]. Dev builds also hash every segment and payload at submit and at execution [R: 21:128]. The executors:
- **`GlRenderHost` and `GlTestHost`** execute on the render thread at START (24:173), into the R8UI or R16UI surface. They expand into the RGBA8 present texture and read back through the PBO ring (24:197). They read back only the newest frame of each device per render frame; older frames complete `"done"` with it (21:87). `GlTestHost` runs the same render-core classes (23:182).
- **`NoRenderHost`**: `available()` is false, and `open`, `offer` and `release` do nothing. `present` returns `nil, "no renderer"`, and `readPixels` returns the confirmed image: the cleared `frameId = 0` image in M0 (24:87, :119; RM:65). Submits and texture calls validate exactly as with a GL host, so a program gets the same errors on a dedicated server (24:228) [proposal].
- **`FakeRenderHost`** [proposal] queues offers per device and runs them only when a test calls `step()`, which hands every queued record to a pluggable `RecordRenderer { open(key, surface); accept(key, record); pixels(key) }` and then calls `onReadback` once per device for the newest `FRAME` it ran, like `GlTestHost`'s `pumpFrame()` (23:182). The default renderer keeps every surface zeroed, the synthetic pixels of 23:165; tests plug in the tier-E oracle from `:core`'s test fixtures, which keeps the oracle out of the main jar (§5.7). Because it consumes every record, the oracle sees `TEX_*` and `RESET` as well as frames.
- **The tier-E oracle** (S6) runs the same records in offered order on CPU arrays (§5.7).

### 2.5 Side files and LAN

Side files are M1 [D] (24:181; RM:82). v1 already makes the tail persistable:
- every record is self-contained (§2.3);
- a `FRAME` persists as its verbatim segments, and each segment carries its own command-format version in its header, which is what G12's per-version dispatch needs (24:85, :181);
- card removal and chunk unload keep the device's state for the side file instead of resetting it, and load replays the tail under a new epoch (§2.2; 24:181; 21:240), so a loop suspended between `submit` and `present` resumes with valid handles (09:102) [proposal for M1's details].

**LAN:** guests never receive commands; they receive the host's pixels (21:200; 24:226) [D]. The `CMDS`/`RESOURCE` body ids stay reserved (24:63, :232). If option (b) ever ships, `CMDS` would carry `FRAME` segments verbatim and `RESOURCE` would carry `TEX_*` records. A segment is at most 64 KB, inside the ≤ 256 KB packets (24:33). A `TEX_UPDATE` can be larger, up to 1 MB at T2 and 2 MB at T3 (§6.2), so `RESOURCE` would split it into row bands, each a valid sub-rect update; that is additive [I].

## 3. Framing

### 3.1 Segment layout

A segment is one Lua string passed to `submit`: an 8-byte header, zero or more records, and `END`. The shortest legal segment is 9 bytes, header plus `END`; it is a no-op.

| Offset | Size | Field | v1 value | Rule |
|---|---|---|---|---|
| 0 | 4 | magic | `4F 47 50 55` (`"OGPU"`) | otherwise `BAD_MAGIC` |
| 4 | 1 | version | 1 | must be a version this mod decodes (§4.2), otherwise `BAD_VERSION` |
| 5 | 1 | flags | 0 | every bit reserved; non-zero is `BAD_FLAGS` |
| 6 | 2 | reserved (u16) | 0 | non-zero is `BAD_RESERVED` |

[D: the magic and the version byte, 09:67 via 24:30; R: the flags and reserved fields, 08-C:115; the must-be-zero rules, proposal]

### 3.2 Records, `END`, endianness, alignment

- **Little-endian** throughout [D] (09:67 via 24:30; 08-C:112). Hosts are x86-64 only (24:18).
- **Record = u8 opcode + fixed operands of the size the opcode table gives.** At most one variable tail is allowed, and its byte count is a field of the fixed part (M0: `TEXT`'s `n`). The decoder looks the opcode up in a 256-entry size table, where 0 means unassigned. It checks that the fixed part fits in the remaining bytes, then the count's limit, then that the counted tail fits, and only then reads any other field (§3.6's order) [proposal; resolves C4].
- **No per-record length prefix.** Unknown opcodes are errors, never skipped. Skipping a draw would make the readback differ from what the program asked for, and an older mod must not quietly drop newer records [proposal].
- **`END` (0xFF) is mandatory and must be the last byte** [D: 0xFF, 09:67]. Bytes after it are `TRAILING_BYTES`, and running out of bytes before it is `MISSING_END`. Opcode 0x00 is never assigned [proposal].
- **No alignment or padding.** Fields are byte-packed and read unaligned. The encoding is canonical: every accepted segment decodes to exactly one record list, and re-encoding that list gives the same bytes (§11.1). A later payload that needs alignment (std140 rows, M2) is copied into the aligned staging buffer that LWJGL forces anyway (21:135) [I].
- **A segment is not a scope** [proposal]. Frame-scoped state (none in M0; M1's `SCISSOR`, M2's binds) starts at its default at every `FRAME` and carries across that frame's segments in order, whatever their versions (§4.2). That is what lets a program split a frame anywhere (P5, §11.3).

### 3.3 Operand types

| Type | Bytes | Encoding | Notes |
|---|---|---|---|
| u8 | 1 | — | 0–255 |
| i16 | 2 | LE two's complement | −32 768 to 32 767; all M0 coordinates and sizes |
| u16 | 2 | LE | counts |
| u32 | 4 | LE | |
| handle | 4 | u32 holding a G1 handle (§6.1) | a value ≥ 2^31 is never well-formed (`Handles.isWellFormed`, `core/src/main/java/io/github/mindbound/opengpu/core/Handles.java:49-51`) |
| colour | 4 | u32; meaning by surface format (§3.4) | |
| bytes[n] | n | raw | n from a u16 earlier in the same record |

M0's operands are integers only [D: 09:67 via 24:30]. Whether M2's camera, globals and model matrices travel as 16.16 or as f32 is M2's choice (24:137). Each type is defined with its first opcode: f32 as IEEE-754 binary32 LE with NaN and Inf rejected at decode (08-C:235), 16.16 with an exact rounding rule and the `% 4294967296` reduction of 08-C:147-148. Neither is in v1, so S6 builds no reader for them [proposal].

### 3.4 The colour operand

[proposal; resolves C6; Q3] A colour is a u32 whose meaning follows the device's surface format:

| Surface | Accepted values | Surface value `sv(c)` |
|---|---|---|
| index8 (T1, T2) | `0x000000II`, 0 ≤ II ≤ 255 | `II` |
| rgb565 (T3) | `0xAARRGGBB` with AA = `0xFF` in v1 | `(q(RR,31) << 11) \| (q(GG,63) << 5) \| q(BB,31)` with `q(c, m) = (c·m + 127) / 255` in integer division [D] (24:205) |

Other values are `BAD_COLOUR`, with "alpha must be 0xFF before blending (M1)" as the reason on rgb565.
- `q` round-trips every 5- and 6-bit level under bit-replication expand. Any 565 value v can be drawn exactly by passing `0xFF` and `expand(v)` (`cmdstream-checks/q565.py`, `cmdstream-checks/rev_checks.py`) [M]. Examples [M]: `sv(0xFF000000) = 0x0000`, `sv(0xFFFF8000) = 0xFC00`, `sv(0xFFFFFFFF) = 0xFFFF`.
- M1 relaxes the alpha rule for blended ops on the RGBA8 path, where present quantizes with the same `q` (24:249, :280). An opaque value then still gives `q(c)`, and a 565 texel blitted through RGBA8 gives `q(expand(v)) = v`, so no M0 stream changes its pixels when M1 moves T3's ops to RGBA8 (C7) [I, from the script's round trip].
- One width for every tier keeps the codec format-agnostic; it costs 3 bytes per colour on index8 [I].

### 3.5 Validation algorithm and atomicity

The validator in `:core` takes the segment array, the live view and the open frame's running totals. It returns either an accepted result (record count, per-class pixel totals, byte count) or exactly one error, the first that §3.6's order reaches, and it never throws for any input bytes [proposal]:

1. **Whole-segment caps:** the submit cap, then the frame byte cap (the open frame's bytes plus `#s`).
2. **Header**, field by field (§3.1).
3. **Records**, at p = 8, until `END`:
   - `END` handling, then the opcode lookup;
   - structure: the fixed part fits; for `TEXT`, n ≤ 1 024 and then the tail fits;
   - field checks in layout order (§3.6, §5.3–5.6);
   - accounting: the record joins the running record count and its fill joins the per-class totals, which are checked against the frame caps.
4. **Commit:** only now is `s` appended to the open frame and are the new totals kept.

Accounting rules [proposal]:
- `END` is not counted as a record; every other record is, empty ones included;
- fill: `CLEAR` counts W·H, `FILL_RECT` its rect clipped to the surface, `BLIT` its clipped area (the i and j ranges of §5.7's oracle) with `KEY0`-skipped pixels included, and `TEXT` 78 px (one 6×13 cell) per codepoint, unclipped (§7.3);
- empty ops still get every field check: a bad colour or a stale handle fails even when nothing would be drawn.

A failed submit leaves the open frame, its totals and the tail exactly as they were [proposal]. The budget charge (§8.2) depends only on min(`#s`, 65 536), so it is taken before validation and spent whatever the outcome. Validation is linear in `#s` and allocates nothing per record [proposal]. Handle checks read the live view, so a handle freed by an earlier `free` fails here as `STALE_HANDLE` (O4).

### 3.6 Error model

A content error returns `nil, msg` as a normal return value, never as an exception. OC logs a callback's exception when `logLuaCallbackErrors` is on (`OCT/scala/li/cil/oc/server/machine/luac/NativeLuaArchitecture.scala:72-78`), and a normal return keeps it quiet; it also matches `present`'s statuses [proposal; Q2]. Argument-type errors, such as a non-string `stream`, stay OC's own `IllegalArgumentException`, which OC turns into a Lua error (`NativeLuaArchitecture.scala:78-81`; `OCT/resources/assets/opencomputers/lua/machine.lua:1071-1074`) [M].

**Stream errors** (`submit`), in evaluation order [proposal]. The offset is the byte offset of the failing record's opcode, or of the header field; `<OP>` is the opcode's name, `0xNN` for an unassigned opcode, `header`, `END`, or `submit` for whole-segment caps.

| # | Code | Trigger | Offset | `<OP>` |
|---|---|---|---|---|
| 1 | `OVER_SUBMIT_BYTES` | `#s` > 65 536 | 0 | `submit` |
| 2 | `OVER_FRAME_BYTES` | the open frame's bytes + `#s` > the frame byte cap | 0 | `submit` |
| 3 | `TRUNCATED_HEADER` | `#s` < 8 | 0 | `header` |
| 4 | `BAD_MAGIC` | bytes 0–3 ≠ `OGPU` | 0 | `header` |
| 5 | `BAD_VERSION` | byte 4 is not a version this mod decodes | 4 | `header` |
| 6 | `BAD_FLAGS` | byte 5 ≠ 0 | 5 | `header` |
| 7 | `BAD_RESERVED` | bytes 6–7 ≠ 0 | 6 | `header` |
| | *record loop at offset p, from 8* | | | |
| 8 | `MISSING_END` | p = `#s` | p | `END` |
| 9 | `TRAILING_BYTES` | s[p] = 0xFF and p + 1 < `#s` | p | `END` |
| 10 | `UNKNOWN_OPCODE` | size[s[p]] = 0 | p | `0xNN` |
| 11 | `TRUNCATED_RECORD` | p + the fixed size > `#s` | p | name |
| 12 | `TEXT_TOO_LONG` | `TEXT`: n > 1 024 | p | `TEXT` |
| 13 | `TRUNCATED_RECORD` | `TEXT`: p + 17 + n > `#s` | p | `TEXT` |
| | *field checks, in layout order* | | | |
| 14 | `BAD_COLOUR` | a colour not valid for the surface (§3.4) | p | name |
| 15 | `BAD_HANDLE` | a handle that is not well-formed: 0, ≥ 2^31, or type 0 (`Handles.java:49-51`) | p | name |
| 16 | `UNKNOWN_TYPE` | well-formed, with a type of 8–15 | p | name |
| 17 | `WRONG_TYPE` | a type of 2–7 where a texture is expected | p | name |
| 18 | `STALE_HANDLE` | type 1, but the slot is not live or its generation differs: freed, or never created | p | name |
| 19 | `WRONG_FORMAT` | the texture's format differs from the surface's; unreachable in M0 (§6.2) | p | name |
| 20 | `BAD_FLAGS_FIELD`, then `UNKNOWN_FONT` | an undefined flag bit is set; then, for `TEXT`, a font id this release does not define (M0 defines font 0 only; 29 F4) | p | name |
| 21 | `BAD_UTF8`, `CONTROL_CHAR` | one left-to-right scan of `TEXT`'s bytes; the first offending sequence decides, and the detail is its string byte index (§7.2) | p | `TEXT` |
| | *accounting* | | | |
| 22 | `OVER_FRAME_RECORDS` | the open frame's records plus this one > 4 096 | p | name |
| 23 | `OVER_FRAME_FILL` | the open frame's fill plus this record's > the fill cap | p | name |

Per opcode the field checks are: `CLEAR` and `FILL_RECT` the colour; `BLIT` the handle (rows 15–19), then the flags; `TEXT` fg, then bg, then the flags, then the font, then the string.

The message is `"<offset>: <OP>: <reason>"`. Examples:
- `0: submit: 70000 bytes exceed the 65536-byte cap`
- `4: header: unsupported command format 2 (this mod decodes 1)`
- `26: BLIT: handle 0x08000001 is not live`
- `26: BLIT: handle 0x10000000 is a buffer, expected a texture` (24:30)
- `44: TEXT: invalid UTF-8 at string byte 3`
- `57: 0x02: unknown opcode`

The Java side returns a structured `StreamError(code, offset, opcode, detail)`, and S6's tests assert the code and the offset.

**Callback results** [proposal]. The checks run in the order listed, on the values `checkInteger` returns (§2.1), with every comparison written so that 32-bit `int` arithmetic cannot overflow; products such as w·h·bpp are formed only after the range checks pass. Handle messages are those of rows 15–18: `"invalid handle 0x…"`, `"handle 0x… has an unknown type"`, `"handle 0x… is a <kind>, expected a texture"` (kinds as named in §6.1) and `"handle 0x… is not live"`.

| Callback | Checks, in order | Messages |
|---|---|---|
| `submit(s)` | §3.5 | `"<offset>: <OP>: <reason>"` |
| `present()` | none; it reports statuses | `"busy"`, `"no renderer"`, `"host render stalled"` |
| `readPixels(x, y, w, h)` | 0 ≤ x ≤ W, 1 ≤ w ≤ W − x, 0 ≤ y ≤ H, 1 ≤ h ≤ H − y; then w·h·bpp ≤ the readback cap | `"invalid region"`; `"region exceeds N bytes per call"` |
| `createTexture(w, h[, format])` | 1 ≤ w ≤ 1 024 and 1 ≤ h ≤ 1 024; `format` nil or the surface format's name; fewer than 256 live textures; w·h·bpp ≤ the free VRAM; the queued resource bytes + w·h·bpp + 64 ≤ the VRAM cap | `"invalid texture size"`; `"unsupported format"`; `"too many textures"`; `"not enough video memory"`, OC's own string (`OCT/scala/li/cil/oc/server/component/GraphicsCard.scala:143`); `"busy"` (also while the open frame's deferred frees would pass the texture or VRAM cap: "S7 implemented", H23) |
| `updateTexture(tex, x, y, w, h, data)` | the handle; 0 ≤ x ≤ TW, 1 ≤ w ≤ TW − x, 0 ≤ y ≤ TH, 1 ≤ h ≤ TH − y; `#data` = w·h·bpp; the queued resource bytes + `#data` + 64 ≤ the VRAM cap | the handle messages; `"invalid texture region"`; `"data must be N bytes"`; `"busy"` |
| `free(tex)` | the handle | the handle messages |

**Which strings are API** [proposal]. The status strings `"busy"`, `"no renderer"` and `"host render stalled"` are API: programs branch on them, and they enter M1's API dump with the other status strings (24:85). Every other message, stream reasons included, is documentation: stable within a release and asserted exactly by S7's and S10's tests, but a program should test only for `nil`.

## 4. Versioning (26 G12)

### 4.1 The command-format version

One u8 per segment header identifies the layout and semantics of every record in that segment. The same number is:
- `CommandFormat.VERSION` in the `-api` jar (24:85);
- `getCaps().commandFormat`, the highest version decoded [proposal];
- what M1's side files record for their tail records (24:181).

M0's value is 1. No source proposes per-op versions (08-C:115; 08-B:128), and neither does this note.

### 4.2 Dispatch

- **Decoders.** `:core` keeps one decoder per version it supports, each translating its version's records into the current in-memory op model, so executors and the oracle know one model only [proposal]. M0 supports {1}.
- **`submit` accepts every version the mod decodes** [proposal; Q8]. Side files force those decoders to exist from M1 on anyway (26:155-156), and a program written for v1 then keeps working after a v2 exists. `opengpu.lua` always emits the current version.
- **Mixed versions.** One frame may hold segments of several versions. Each decoder translates into the current op model, and frame-scoped state carries across segments in order (§3.2) [proposal].
- **Side files** (M1) dispatch per segment on its header byte. The `TEX_*` records, their format codes and handle types are part of command format v1, and the side file records, for its resource records, the command-format version that wrote them (24:181, :232) [proposal].
- **Constants.** `:core` cannot depend on the root's `api` package (24:80, :85), so the opcode table, the version, the flag bits, the format codes and the handle type codes are defined once in `:core`, in `io.github.mindbound.opengpu.core.cmd` [proposal]. The `api` package's `CommandFormat` assigns its `public static final` fields from those constants. javac inlines constant expressions, so the `-api` jar needs no `:core` classes at run time [I]. From M1 the same table generates `api-dump.json`'s opcode and record-layout entries (24:85; RM:258).
- **Downgrades** are unsupported. An older mod rejects a newer segment with `BAD_VERSION` and a newer record with `UNKNOWN_OPCODE`.

### 4.3 What breaks and what does not

| Change | Bumps the version? |
|---|---|
| A new opcode in an unassigned or reserved value; a newly defined reserved flag bit; a new record kind; a relaxed check (e.g. alpha < 255 with M1's blending, more texture formats); a raised cap; a new callback | no: v1 rejects every stream that would use it, so no accepted stream changes meaning |
| Any change to an assigned opcode's layout, or to the meaning or pixel result of an accepted segment; removing an opcode | yes. A layout never changes inside a version, so new operands mean a new opcode |
| A tightened check that some accepted segments fail | not a format change, but a Lua-facing break (justification and changelog, 26:157). Side files load through the decoder without the admission caps |
| The glyph bitmaps (§7.1; the cell is part of the format, §7.3), the default palette (§5.7), cap values | not format changes. They go in the changelog and in the goldens' sidecars (23:147) |

Freeze points [proposal; Q8]:
- **Until M1's first release:** v1 is a draft, edited in place. Nothing is persisted before M1 (24:181).
- **From M1's release:** v1 is frozen as a persisted format, and later breaking changes become v2 with v1's decoder kept (26:156).
- **From M4:** the Lua-facing layouts freeze with the API (24:253).

M1 settles three points before its release, while v1 is still a draft [proposal]:
- the advance and fill charge of codepoints without a narrow glyph, which v1 draws as one missing-glyph cell (§7.2), so that wide glyphs do not shift accepted streams after the freeze; this is 29's F5, deferred to M1 and then decided for it on 2026-10-15: 2 cells where musl's `wcwidth` is 2 and 1 cell otherwise, fill per cell, with misc-fixed's 12x13ja as font 0's CJK fallback (29 §6.2, F7);
- whether frame-mode frames start from the cleared image (§10.1);
- capability discovery. `commandFormat` stays 1 while opcodes accrue, so a program written for a newer mod learns that an opcode is missing only from `UNKNOWN_OPCODE`. A `getCaps().opcodes` bitmap (a 32-byte string) would be additive.

### 4.4 Opcode map

| Range | Class | v1 | Reserved for |
|---|---|---|---|
| 0x00 | — | invalid, never assigned | — |
| 0x01–0x0F | whole-target state | 0x01 `CLEAR` | 0x02 viewport (M2, 08-C:117), 0x03 `SCISSOR` (M1), 0x04 depth/stencil clear (M2); 0x05–0x0F unassigned |
| 0x10–0x1F | binds and pass state | — | M2: pipeline, buffers, textures, target, `SET_CAMERA`, `SET_GLOBALS`, uniform rows (24:136-137); 08-C's 0x10–0x16 are the starting point |
| 0x20–0x2F | draws | — | M2: `DRAW`, `DRAW_INDEXED` with the 3×4 model matrix (24:137) |
| 0x30–0x3F | 2D | 0x30 `FILL_RECT`, 0x31 `BLIT`, 0x33 `TEXT` | 0x32 `LINE`, 0x34 `COPY`, 0x35 circles (M1, 24:249); 0x36–0x3F unassigned |
| 0x40–0x4F | compute | — | reserved; expected to stay empty (C16) |
| 0x50–0xEF | — | unassigned | later milestones |
| 0xF0–0xFE | — | reserved | framing or debug extensions; assigning one is additive (§4.3) |
| 0xFF | framing | `END` | — |

## 5. The M0 opcodes

### 5.1 Common rules

[proposal unless cited]
- **Coordinates.** OpenGPU pixel (x, y) has its origin at the top left, y grows downwards, and the pixel's centre is at (x + 0.5, y + 0.5) [D] (06:40; 24:156).
- **Rects.** A rect (x, y, w, h) covers the pixels with x ≤ px < x + w and y ≤ py < y + h. The sum x + w is computed in 32-bit arithmetic, so i16 operands never overflow. w ≤ 0 or h ≤ 0 is a valid, empty op. Everything is clipped to the surface [0, W) × [0, H), so off-surface operands are valid and draw what remains.
- **No flips anywhere.** Surface pixel (x, y) is GL window pixel (x, y) of the surface FBO and memory row y of the readback, which needs no CPU flip (20:164; `gl-testkit/src/test/java/io/github/mindbound/opengpu/gltestkit/GlSmokeTest.java:118-127`) [M]. Texture row 0 is the first payload row (§6.3).
- **Integer surfaces only, so no blending in M0.** GL skips blending on integer colour buffers (GL33:10235-10236) [D] (24:138). Every op overwrites.
- **Host notes for S8.**
  - Ops become integer-aligned quads, never `GL_LINES`/`GL_POINTS` [D] (24:197).
  - The executor draws exactly the rect the oracle clips to, computed on the CPU (§5.7's ranges), so no vertex leaves the surface and no `texelFetch` leaves its texture, where results are undefined (GL33:4324-4349).
  - Colours reach the shader as the precomputed `sv(c)`, through `glUniform1i` (C57, 25:323) or a per-instance attribute, never `glVertexAttrib*` (V1, 24:203). `glUniform1ui` is A16, which GLSM does not map (25:407).
  - Instanced against expanded quads is an M0 measurement (G16, 24:296); tier E allows no difference between them (23:137) (S8: both built and run by every scenario, expanded the default until then, H39; `glBench` has the timing row, H65; "S8 implemented").

### 5.2 Layouts

Offsets are relative to the opcode byte; the total includes it.

| Op | Name | Total | Fields (offset: type name) | Energy class (M1) |
|---|---|---|---|---|
| 0x01 | `CLEAR` | 5 | 1: colour c | clear |
| 0x30 | `FILL_RECT` | 13 | 1: i16 x · 3: i16 y · 5: i16 w · 7: i16 h · 9: colour c | fill |
| 0x31 | `BLIT` | 18 | 1: handle tex · 5: i16 sx · 7: i16 sy · 9: i16 w · 11: i16 h · 13: i16 dx · 15: i16 dy · 17: u8 flags | copy |
| 0x33 | `TEXT` | 17 + n | 1: i16 x · 3: i16 y · 5: colour fg · 9: colour bg · 13: u8 flags · 14: u8 font · 15: u16 n · 17: bytes[n] | set |
| 0xFF | `END` | 1 | — | — |

Field order follows 08-C §3 (08-C:116-125), which 09 carries (09:67). The departures [proposal: they change 08-C's layouts as 09:67 carries them; Q13] are: `CLEAR` has no depth or mask (C5), `BLIT`'s `blend` byte becomes `flags`, and `TEXT` gains `flags` and, by 29's F4 [D: owner, 2026-10-15], `font`.

### 5.3 `CLEAR`

Checks: c valid (§3.4). Result: every surface pixel becomes `sv(c)`. On the host it is a full-surface quad writing `sv(c)`: `glClear` is undefined on integer buffers and `glClearBuffer*` is unmapped in GLSM [D] (24:197; GL33:10964).

### 5.4 `FILL_RECT`

Checks: c valid. Result: every pixel of the clipped rect becomes `sv(c)`.

### 5.5 `BLIT`

1:1 copy from a texture to the surface.

Checks, in order (§3.6 rows 15–20):
- `tex` is a well-formed handle (`BAD_HANDLE`) of a known type (`UNKNOWN_TYPE`), the type is 1, texture (`WRONG_TYPE`), and it is live with a matching generation (`STALE_HANDLE`);
- its format equals the surface format (`WRONG_FORMAT`; always true in M0, §6.2);
- `flags & 0xFE == 0` (`BAD_FLAGS_FIELD`).

Flags bit 0 is `KEY0` [proposal; Q6]: source texels whose value is 0 (index 0, or 565 value 0x0000) are not written. That is PS1's transparent `0000h` (26:322), done with `discard` as 26's CLUT sketch does (26:327), since integer surfaces ignore blending (24:138).

Result, for 0 ≤ i < w and 0 ≤ j < h: dest pixel (dx + i, dy + j) receives source texel (sx + i, sy + j) unless any of these holds:
- the source texel lies outside [0, TW) × [0, TH);
- the dest pixel lies outside the surface;
- `KEY0` is set and the texel value is 0.

The texel value is copied raw, with no conversion: an index on index8, 565 bits on rgb565. Clipping both rects this way means the host never fetches outside the texture, where `texelFetch` is undefined (GL33:4324-4349).

Rules for S8:
- the source is never the surface (a feedback loop, GL33:12123-12130; `COPY` is M1, 24:249);
- the unit runs with sampler object 0 bound (C12, "bind 0 during pass", 25:278; 20 §1), inside the pass's save and restore (24:197).

Tier E covers it (23:137). Flags bits 1–2 are reserved for M1's integer scale, ×1–×4 as in `TEXT` (§10.1; 29 F8, decided 2026-10-15).

### 5.6 `TEXT`

Checks, in order: the fixed part fits; n ≤ 1 024 (`TEXT_TOO_LONG`); the tail fits (`TRUNCATED_RECORD`); fg, then bg, valid even when bg is unused (`BAD_COLOUR`); `flags & 0xFE == 0` (`BAD_FLAGS_FIELD`); font = 0, the only font M0 defines (`UNKNOWN_FONT`; M1's ids are a relaxed check, §4.3); the n bytes well-formed UTF-8 with no control characters, in one scan (§7.2).

Flags bit 0 is `TRANSPARENT_BG`; bits 1–2 are reserved for M1's integer scale (§10.1; 29 F8).

Result: codepoint k (0-based) of the string fills the cell [x + 6k, x + 6k + 6) × [y, y + 13) from font 0's glyph table G (§7.1). Glyph row r (0 = top) is byte g[r], and column b (0 = left, b < 6) is bit 7 − b of it, MSB leftmost, as BDF stores a row and as OC renders `font.hex` (`OCT/scala/li/cil/oc/client/renderer/font/FontParserHex.java:101-109`). A set bit writes `sv(fg)`. A clear bit writes `sv(bg)`, unless `TRANSPARENT_BG` is set, in which case the pixel keeps its value. Cells are clipped and never wrap.

### 5.7 The surface byte layout, expand and the oracle

**Surface byte layout** [proposal; resolves 21:96 against 24:197]. Rows are stored top row first, row-major, with no padding:
- index8: 1 B per pixel, the index;
- rgb565: 2 B per pixel, a LE u16 with r5 in bits 15–11, g6 in 10–5 and b5 in 4–0.

That is what GL's `GL_RED_INTEGER` readback of R16UI gives on x86-64 (`GlSmokeTest.java:169-172`) [M]. It is the one layout of:
- `readPixels` data, `onReadback` and `RESET`'s pixels;
- texture payloads and masters;
- side files (M1) and LAN tiles (M4).

21's packed-RGBA8 readback (21:96) stays an M0 alternative "if faster and exact" (24:197), on condition that it produces these bytes (S8: not built; RM:43's PBO measurement decides whether M1 builds it, H65, "S8 implemented").

**Expand** [D shape, 20:163-165; 24:113]. Expand writes alpha 255 (24:201):
- index8 → `palette[i]`;
- rgb565 → `(r5<<3 | r5>>2, g6<<2 | g6>>4, b5<<3 | b5>>2)`, in integer arithmetic, never GL's 5_6_5 unorm conversion. That conversion differs from bit replication at 14 levels (`cmdstream-checks/q565.py`) [M].

The function lives in `:core` main, because M4's guests expand on the CPU with the host's function (24:113) [proposal].

M0's default palette, until `setPalette` arrives in M1, is 08-C's 6×6×6 cube plus 40 greys (08-C:235), with its grey levels fixed here [proposal]. The cube is entry i = 36r + 6g + b for r, g, b in 0–5, with channels 51·level. Grey k (0 ≤ k < 40) sits at entry 216 + k, with value (255·(k + 1) + 20) / 41 in integer (floor) division: entry 216 is 6, entry 255 is 249, and no grey repeats a cube level [M: `cmdstream-checks/rev_checks.py`]. Entry 0 is black, so a cleared T2 device is black (21:242). OC's own 8-bit palette starts at 0x0F0F0F (`OCT/scala/li/cil/oc/util/PackedColor.scala:136-141`) and would leave a cleared device dark grey.

**Tier-E oracle** [D: a test-only oracle, 23:149; scope proposal]. It lives in `:core`'s `testFixtures` source set, never in main, so it ships in no jar and does not reopen decision 3 (24:9). `:gl-testkit`, `:ocelot-harness` and `FakeRenderHost`'s tests consume it. It is constructed from a device's `SurfaceDesc` and the glyph table G. Its state is the surface (one value per pixel, zero at start) and a map from full handle to (w, h, texels). It runs records in offered order with the same reader, using the rules of §5.3–5.6 literally:

```
sv(c)      index8: c;  rgb565: (q(c>>16&255,31)<<11) | (q(c>>8&255,63)<<5) | q(c&255,31);  q(v,m) = (v*m+127)/255
RESET      T = {}; S = the record's pixels
TEX_*      CREATE: T[h] = (w, h, zeros);  UPDATE: copy the payload's rows into T[h];  FREE: remove T[h]
CLEAR      for every pixel: S = sv(c)
FILL_RECT  for max(y,0) <= py < min(y+h,H), max(x,0) <= px < min(x+w,W): S[px,py] = sv(c)
BLIT       i0 = max(0,-sx,-dx), i1 = min(w, TW-sx, W-dx); j0 = max(0,-sy,-dy), j1 = min(h, TH-sy, H-dy)
           for j0 <= j < j1, i0 <= i < i1: v = T[sx+i, sy+j]; if !(KEY0 && v == 0): S[dx+i, dy+j] = v
TEXT       k = 0; for each codepoint cp: g = G(cp) or G.missing
           for r < 13, b < 6 with (x+6k+b, y+r) on the surface:
             if (g[r] >> (7-b) & 1) S = sv(fg) else if (!TRANSPARENT_BG) S = sv(bg)
           k = k + 1
```

Its output is the surface in the layout above. It is a few dozen lines per op, not a rasterizer.

## 6. The minimal texture path

[D: owner, 2026-10-14 (24:248; RM:35): a 2D texture in the surface's own format, with create, region update and free, as `BLIT`'s source and the self-test's sprite, exercising G1, G8 around uploads and G13b's upload cost; other formats, index8 palette rows and the rest of the texture work stay in M2; ≈ 1–2 d [E]. Everything else in this section is a proposal.]

### 6.1 Handle type and generations

Handles are `type(4) | slot(11) | generation(16)`, positive, type 0 reserved [D] (24:30; `Handles.java:3-8`, `:11-17`). `Handles.java:7` leaves the type numbers to S5:

| Type | Resource | From |
|---|---|---|
| 0 | none (invalid) | — |
| 1 | texture | M0 |
| 2 | buffer | M2 |
| 3 | render target | M2 |
| 4 | program | M2 |
| 5 | pipeline | M2 |
| 6 | kernel | M3 |
| 7 | compute list | M3 |
| 8–15 | unassigned; `UNKNOWN_TYPE` | — |

The kinds are 09's resource list (09:59) plus the compute list (24:139). Only type 1 is used in M0. The type bits persist from M1 (24:30), so 2–7 are reserved now, which lets M0's errors name them ("is a buffer", 24:30) and keeps M2–M3 from renumbering.

Generations:
- Each type's slots come from a FIFO free queue, initially 0–2 047 in order. `free` increments the slot's generation, wrapping 65 535 → 0, and puts the slot at the queue's tail.
- A `computer.stopped`/`started` reset frees every live handle the same way (§2.2). Generations are never reset during the device's lifetime, and they persist with the handle table from M1 (24:30).
- A stale handle is therefore missed only when its slot has been reused exactly a multiple of 65 536 times since, which under FIFO reuse needs at least 65 536 × (free slots) creates [I].
- 06:28 retires overflowed slots instead. That would let a long create/free loop exhaust the slots, so wrapping is chosen.
- A slot that was never allocated is not live, so a well-formed type-1 handle naming it is `STALE_HANDLE`, even though its generation 0 matches.
- A new device's first texture is `0x08000000` (type 1, slot 0, generation 0) [M] (`cmdstream-checks/example.py`).
- Handles are device-local. Every device's first texture has the same value, so a handle passed to another card is accepted there if that card happens to have the same handle live. That cannot be detected without per-device generation seeds, which this note does not propose; S7 adds a test that documents the behaviour.

### 6.2 `createTexture(w, h[, format])`

- **Checks** (messages in §3.6):
  - w and h are in 1–1 024. GL 3.3's guaranteed `MAX_TEXTURE_SIZE` is 1 024 (GL33:14799-14808), and a cap that followed the host's limit would give programs different answers on different hosts and need host data on the server (24:133 (a)) [I];
  - `format` is nil or the surface format's name;
  - fewer than 256 textures are live;
  - w·h·bpp fits the free VRAM (§8.1);
  - the queued resource bytes stay within the VRAM cap (§2.2).
- **Charge:** after the checks, w·h·bpp/256 KB with `bitblt`'s two-phase pattern (§8.2).
- **Effect:** allocate a handle, enter (w, h, format) in the live table, add w·h·bpp to VRAM use, and append and offer `TEX_CREATE`. Contents are all zeros: index 0, or 565 black. GL leaves textures allocated with null data unspecified (GL33:7745-7751), so the definition is OpenGPU's.
- **Format:** always the surface format in M0. The record carries the format as an OpenGPU code (1 `index8` = 1 B/texel, 2 `rgb565` = 2 B/texel; 0 invalid; ≥ 3 reserved for M2), never a GL enum [D] (24:238).

### 6.3 `updateTexture(tex, x, y, w, h, data)`

- **Checks** (messages in §3.6), on `checkInteger`'s values:
  - `tex` is a live texture;
  - 0 ≤ x ≤ TW, 1 ≤ w ≤ TW − x, 0 ≤ y ≤ TH and 1 ≤ h ≤ TH − y. Outside regions are rejected server-side, since GL would raise `INVALID_VALUE` and upload nothing (GL33:7959-7970);
  - `#data` = w·h·bpp exactly, formed after the region check. LWJGL computes 0 required bytes for `GL_RED`/`GL_RED_INTEGER` (`LWJGL/org/lwjgl/opengl/GLChecks.java:182-230`) [M], so this check is the only guard against a driver reading past the buffer;
  - the queued resource bytes plus `#data` + 64 fit within the VRAM cap, otherwise `"busy"` [D for the bound, 24:179].
- **Charge:** after the checks, `#data`/256 KB with the two-phase pattern (§8.2).
- **Payload:** the region in the surface byte layout (§5.7): rows top first, w·bpp bytes each, no padding; rgb565 as LE u16. The tight packing resolves C9.
- **Effect:** append and offer `TEX_UPDATE(tex, x, y, w, h, data)`. `data` is the argument array itself, which is never written again [D] (24:179; 21:120-128).

### 6.4 `free(tex)`

- **Checks:** `tex` is a live texture, otherwise one of the handle messages, which decode the type (24:30; §3.6). OC's `freeBuffer` answers a missing buffer with `nil, "no buffer at index"` (`GraphicsCard.scala:171-174`).
- **Effect:** O4 (§2.2). The handle is invalid at once and its VRAM is credited at once; `TEX_FREE` is appended and offered now, or right after the open frame's `FRAME`. `free` is never `"busy"` (§2.2).
- **VRAM accounting** follows the live view. The host may hold a freed texture until its deferred `TEX_FREE` executes, so `GlLedger` and `:core` agree once the host has drained the device's queue and the server tail is empty, which is when G10's test compares them (24:218) [proposal]. With a frame open the host also holds the textures freed during it, so the comparison adds its deferred bytes ("S7 implemented").

### 6.5 Ordering against draws

O3, O4, O6 and the examples of §2.2 are the whole rule set:
- the open frame sees every create and update made before its `present`;
- frames already offered never see later ones;
- no frame sees a freed texture.

M0 keeps texture masters and folds them (O6). They cost at most the VRAM cap per device. M1 persists them, and eviction rebuilds from them (24:179, :183), so S7 builds the one data model now [proposal].

### 6.6 Host side (S8)

[D where cited; storage choice: proposal, C8; Q11]
- **Storage:**
  - index8 is `GL_R8`, uploaded as `GL_RED`/`GL_UNSIGNED_BYTE`. The `BLIT` pass recovers the index as `floor(r * 255.0 + 0.5)` (24:205), which is exact for all 256 values in a float32 model (`cmdstream-checks/q565.py`) [M]; R8 allocates at least 8 bits (GL33:7262, :7279).
  - rgb565 is `GL_R16UI`, uploaded as `GL_RED_INTEGER`/`GL_UNSIGNED_SHORT`, fetched with `usampler2D`/`texelFetch`. GL 3.3 has no exact 565 storage that keeps bit replication, since unorm 5_6_5 differs at 14 levels (§5.7).
  - Both use `GL_NEAREST`, `CLAMP_TO_EDGE` and `GL_TEXTURE_MAX_LEVEL` 0, set before the first upload (20:37-38). `MAX_LEVEL` 0 also keeps GLSM's emulated mipmap generation inert (`ANG8/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:4287-4292`).
- **Calls:** only 25 §4.1's, since a call outside it needs a new row (25:251):
  - C29 `glGenTextures`, C30 `glTexParameteri`, C31 `glTexImage2D(ByteBuffer)` with a zeroed buffer at create, C32 `glTexSubImage2D(ByteBuffer)`, C33 `glDeleteTextures` and C34 `glPixelStorei` (25:295-300);
  - C10 `glActiveTexture` and C11 `glBindTexture` (25:276-277), to bind on one fixed unit;
  - C01 `glGetInteger` and C09 `glBindBuffer` (25:267, :275), to save, unbind and restore the vanilla leg's unpack PBO;
  - C94–C98 (25:360-364), for G13b's `GL_TIMESTAMP` pair;
  - never the `ShortBuffer` forms (A11, 25:404), and not the unpack-PBO overload, which is outside the call set.
- **Where:** creates, uploads and deletes run inside the START pass's save/restore bracket, on one fixed unit, so the texture binding and the active unit are restored with everything else (24:197) [proposal].
- **G8 around every upload** (24:203):
  - set `UNPACK_ALIGNMENT` 1 (odd-width R8 rows are not 4-aligned), `UNPACK_ROW_LENGTH` 0 and `UNPACK_SKIP_PIXELS`/`SKIP_ROWS` 0;
  - set `UNPACK_SWAP_BYTES` and `UNPACK_LSB_FIRST` false on both contexts (C10; GLSM tracks both, `PixelUnpackState.java:35-36`);
  - unbind `GL_PIXEL_UNPACK_BUFFER` on the vanilla leg, because LWJGL's `ByteBuffer` overloads require it unbound (`LWJGL/org/lwjgl/opengl/GL11.java:2954-2960`). GLSM suspends a bound one itself (`ANG8/…/GLStateManager.java:2098-2112`, `:5284-5298`);
  - restore everything afterwards.
- **Staging:** one direct-buffer copy on the render thread (21:135) [D], from a per-host buffer sized to the largest upload (2 MB at T3).
- **Budget:** uploads count against `renderBudgetMs` (24:173), without G13a's 8-object cap, which is M1 (RM:83).
- **`RESET`:** delete the device's textures (C33), then upload its pixels into the surface with the same G8 bracket (C32).
- **`GlLedger` (G10)** (24:83):
  - kind `texture`, label = the handle in hex, bytes w·h·bpp; its per-device total equals `:core`'s VRAM use when the queue and the tail are empty (§6.4), plus the open frame's deferred bytes while one is open ("S7 implemented");
  - surfaces, the present texture, PBOs and the glyph atlas are separate kinds outside `:core`'s VRAM;
  - deletes are recorded at call time, although GLSM defers the real delete (25:470).

### 6.7 What G13b measures

For each `TEX_UPDATE`, M0 records:
- the payload bytes;
- render-thread ns for the staging copy and for the `glTexSubImage2D` call;
- GPU time from a `GL_TIMESTAMP` pair around it.

Sizes are 4 KB, 64 KB, 256 KB, 500 KB (512 000 B, a full T3 surface) and 2 MB (the largest texture), measured on Intel, NVIDIA and AMD with `hostRenderer` recorded (RM:43). M0 executes every upload in one pass. M1 decides whether to slice uploads over 256 KB [D] (24:296; 26:124).

### 6.8 Deferred to M2

- other formats, among them `rgba8` and index8 with palette rows, and the `BLIT` sources they bring (26 R5; 24:248, :251);
- how user shaders sample rgb565 textures (an R16UI texture cannot bind to a float `sampler2D`);
- filtering and mipmaps;
- initial data at create (08-B:103);
- `freeAll`, `handles` and `getMemory` (09:59);
- render targets, and buffers with dirty ranges (RM:128).

## 7. `TEXT`

### 7.1 Glyph source and the M0 decision point (RM:61)

The protocol names font ids and their cells, never their bitmaps; M0 defines id 0 only (29 F4, 2026-10-15). A `TEXT` record's pixels are a function of the record and font 0's glyph table G that maps codepoints to 6×13 bitmaps, 13 bytes, one per row from the top with the left 6 bits used (MSB leftmost, as in BDF), plus a missing glyph. Changing G's bitmaps is not a format change (§4.3); changing its cell is (§7.3).

**Decided for M0 [Q1, answered 2026-10-14]:** G is font 0, X11 misc-fixed 6x13 (public domain), shipped in OpenGPU's jar. The owner keeps misc-fixed 8x13 as the alternative if 6x13 does not look right as the default size (29 §7). 29's survey ran the licence and provenance check of risk 16 (24:320) for it.
- The source is `6x13.bdf` from Markus Kuhn's ucs-fonts (`$Id` v1.115, 2009-04-06; sha256 d00cf1cb…, 29 §9.1), whose COPYRIGHT reads "Public domain font. Share and enjoy."; NOTICE gets a credit line. A build-time converter (own code) writes it as a table into OpenGPU's jar, which the client reads through the classpath, never through Minecraft's resource manager, which merges resource packs (`FontParserHex.java:47-48`), so G cannot differ between hosts through a pack [I] (S8: `:core`'s `generateFont0` writes `font0-6x13.glyphs`, H48, "S8 implemented").
- G is the whole face [proposal]: 4 121 glyphs, each 6 px wide and inside the 6×13 cell, with U+0020–U+007E, U+00A0–U+00FF and U+2500–U+259F 351/351 inked, and full Greek and Cyrillic; its own U+FFFD is the missing glyph [M: `cmdstream-checks/q1_font0.py`]. At 1 B per texel they fill 321 438 B of atlas, inside one 1 024² page of 13 260 cells; those 351 alone would fill 27 456 B with U+FFFD [M].
- CI, the oracle and `:gl-testkit` use a synthetic G checked in as test data (own work, MIT), so no CI golden depends on a real font. Its rule [proposal]: U+0020 and U+00A0 are blank; each codepoint cp in U+0021–U+007E and U+00A1–U+00FF has row r (0–12) = ((7·cp + 29·r) mod 256) AND 0xFC; every other codepoint gets the missing glyph, a box with rows 0 and 12 = 0xFC and rows 1–11 = 0x84. Its 189 drawn glyphs are pairwise distinct, differ from the box and the blank, set every one of the 6 bit columns and nothing outside them [M: `cmdstream-checks/q1_font0.py`; the 8×16 form, `rev_checks.py`].
- The oracle and the GL atlas take G as one injected object, so they cannot diverge.
- Why ship it in M0 rather than defer (29 §7):
  - the check is done, and the public domain asks only for a credit line;
  - M0 reads no glyph from OC's mixed-licence `font.hex` (unscii, asie's CC0 glyphs, Unifont and Shinonome; 29 §3);
  - M0's criteria still need only that the pattern and sprite are visible (RM:66), and its in-game text already shows the font M1 keeps.
- 29 sets out M1's further fonts, each with its own geometry (29 §5). On 2026-10-15 the owner took misc-fixed's faces (ids 1–6 in 29 §5.3; 29 §8 F3) with 12x13ja as the wide fallback (F7, after 29 §10's provenance check found no grant for 18x18ja's and 18x18ko's Hangul), decided that M1 supports proportional fonts, starting with Tiny5, Tiny5 Duo and ANAKRON (F6), and decided integer scale (F8, 28:859). Its F4, a `font` byte, was taken on 2026-10-15 and is in this note's `TEXT` (28:426, :465); its F5, `wcwidth` advances with their fill, was deferred to M1 the same day and then decided for M1 (29 §6.2), so M0 still advances one cell per codepoint (28:384, :650). unscii-16, OC's look (`OCT/../../README.md:131-134`; 06:38), waits for the deferred stock-GPU text console, whose font is chosen then (29 §8 F2).

**Alternative kept:** misc-fixed 8x13 (8×13 cells, 3 703 glyphs, the same checks pass [M: `q1_font0.py`]), if 6x13 does not look right in game. Switching changes the cell, so it must happen while v1 is a draft (§4.3, §7.3).

### 7.2 Encoding

- The string is UTF-8. OC's `gpu.set` decodes its string as UTF-8 (`ArgumentsImpl.scala:169`), charges its call budget once per call (`setCosts` by tier) and energy per codepoint (`GraphicsCard.scala:67`, `:100-106`, `:482-489`) [M].
- It must be well-formed: shortest form, no surrogates, nothing above U+10FFFF. Otherwise the error is `BAD_UTF8`, with the string byte's index as detail [proposal; Q7].
- C0 and C1 controls (U+0000–U+001F, U+007F–U+009F) are `CONTROL_CHAR`, which leaves M1 room to give them meaning additively [proposal].
- `BAD_UTF8` and `CONTROL_CHAR` come from one left-to-right scan: the first offending sequence decides.
- n counts bytes, as Lua's `#` does. n ≤ 1 024.
- A codepoint without a narrow glyph in G draws G's missing glyph in one cell. M1 advances such codepoints by the rule decided on 2026-10-15 (§4.3; 29 §6.2).
- Wide glyphs are M1's (29 §8 F5, F7; on 2026-10-15 F5 was decided for M1 and F7 narrowed to 12x13ja, 29 §6.2, §10). Font 0 has none; misc-fixed's 12x13ja is its double-width companion at 12×13 and M1's wide fallback for it (29 §3, §6.4), while OC's `font.hex` has 12 953 narrow and 62 890 wide glyphs (06:38; recounted [M]).

### 7.3 Cells

Cells are 6×13 px, font 0's (Q1). A line holds 53 cells on T2 and 106 on T3, in 15 and 30 lines [M: `q1_font0.py`]. The cell is part of the format, because it sets the pixel result and the fill charge, so changing it is an in-place edit only while v1 is a draft (§4.3). The validator needs no glyph data: it checks the font id, counts codepoints, charges 78 px of fill per codepoint, and does not clip for the count.

### 7.4 fg and bg

As in §5.6: fg on set bits, bg on clear bits unless `TRANSPARENT_BG`. Both are colour operands (§3.4).

### 7.5 Host atlas (S8)

- one `GL_R8` atlas per host session (S8: per host, that is per GL context, 1 020 × 325 for font 0, H33, "S8 implemented") holding the glyph bits as 0/255, built from the same G object the oracle uses, at most 1 024 × 1 024 (GL33:14799-14808);
- `GlLedger` kind `glyph-atlas`;
- per-cell quads, or instanced ones (G16); each fragment fetches its bit with `texelFetch` and writes fg or bg, or discards.

Glyphs at integer offsets are tier E (23:137).

## 8. Limits and costs

### 8.1 Caps

[proposal; provisional until M0's measurements (RM:43, :62); Q5]

| Cap | `getCaps` key | T1 (M1) | T2 | T3 | Basis |
|---|---|---|---|---|---|
| tier | `tier` | 1 | 2 | 3 | the charges use OC's 0-based tier (§8.2) |
| surface | `width`, `height`, `format` | 160, 100, `"index8"` | 320, 200, `"index8"` | 640, 400, `"rgb565"` | [D] 24:13 |
| command format | `commandFormat` | 1 | 1 | 1 | §4.1 |
| bytes per submit | `maxSubmitBytes` | 65 536 | 65 536 | 65 536 | keeps one call's validation inside 0.2 ms (24:34) at ≥ 0.5 GB/s [E], which S6 measures at the cap; its charge is ≤ 0.094 < 0.5, the pack's lowest budget (§8.2) |
| bytes per frame (open frame, all segments) | `maxFrameBytes` | 65 536 | 131 072 | 262 144 | 08-C's per-submit values (08-C:134, :231-233), as a frame total: they bound the tail at about 3 frames per device |
| records per frame | `maxFrameRecords` | 4 096 | 4 096 | 4 096 | host vertex-building work [E] |
| fill per frame (§3.5) | `maxFrameFill` | 256 000 px | 1 024 000 px | 4 096 000 px | 16 surfaces, the 2D analogue of 24:210's per-frame triangle caps |
| `TEXT` bytes per record | `maxTextBytes` | 1 024 | 1 024 | 1 024 | — |
| unconfirmed frames | `maxUnconfirmedFrames` | 2 | 2 | 2 | [D] 24:34; `"busy"` at the third |
| texture side | `maxTextureSize` | 1 024 | 1 024 | 1 024 | GL33:14799-14808 |
| live textures | `maxTextures` | 256 | 256 | 256 | bounds GL objects per device |
| VRAM (Σ w·h·bpp of live textures) | `vramBytes` | 262 144 | 1 048 576 | 4 194 304 | [R] 07:116-118; 08-C:231-233 |
| queued resource bytes in the tail | (= `vramBytes`) | 262 144 | 1 048 576 | 4 194 304 | [D] 24:179 for the bound; 64 B per record (§2.2) |
| readback per call | `maxReadbackBytes` | 16 384 | 32 768 | 65 536 | [D] 09:62; 24:129. A full surface takes 1, 2 and 8 calls [M] |

09, 07 and 08-C write these sizes in KB and MB without saying which; binary units are this note's [proposal]. `getCaps()` also keeps 24 §3.3's `renderer`, `readback`, `graphics` and `hostRenderer` (24:125) [D], 19 keys in all, within the small-fixed-table rule (09:59).

Several submits per frame are not for capacity: they let a program build a frame in small pieces and keep its Lua heap low (§8.4). A T3 screen full of font 0's text is 106 × 30 = 3 180 cells: up to 3 180 `TEXT` records of about 18 B (57 240 B, about 56 KB) and 248 040 px of fill, inside every cap [I].

### 8.2 Budget charges

These are 09 §3.2's charges, carried "as written" by 24 §2.1 (09:71; 24:31) [D], tuned for the pack's `[0.5, 1, 1.5]` (10:100). t is the card's tier counted from 0, as OC's cost arrays and `bitbltCost` index it (`GraphicsCard.scala:64-73`) and as 08-C:235 writes it [M]. The new rows and the order rules are marked.

| Callback | Charge | Note |
|---|---|---|
| `submit(s)` | 1/32·2^−t + min(`#s`, 65 536)/1 MB [min: proposal] | at most 0.094, so never two-phase; taken before validation |
| `present()` | 1/8, 1/16, 1/32 | taken before the status is decided |
| `readPixels` | w·h·bpp/256 KB (1/4 per 64 KB), after the region check | — |
| `updateTexture` | `#data`/256 KB, with `bitblt`'s two-phase pattern, after the checks | see below |
| `createTexture` | w·h·bpp/256 KB, as an upload of zeros, two-phase, after the checks [proposal] | at most 4 at T2 (a 0.1 s pause) and 8 at T3 (0.17 s) [M] |
| `free`, `getCaps` | none beyond the direct-call default (`invoke` charges 1/limit, 19:16) | OC's `freeBuffer` and `allocateBuffer` charge nothing (`GraphicsCard.scala:135-152`, `:171-174`) |
| `bind` | non-direct: one call per tick (11:13) | — |

**Two-phase pattern** [D pattern, 09:71; copied from `GraphicsCard.scala:221-251`]. Let c be the charge and k = (t + 1)·0.5 the card tier's credit (`:235`):
- c ≤ k: `consumeCallBudget(c)`, which may throw `LimitReached` before any side effect.
- c > k, first attempt: set the device's exhausted flag and throw `LimitReached`.
- c > k, synchronized retry next tick: the budget check is off there (`OCT/scala/li/cil/oc/server/machine/Machine.scala:286-294`). Pause for ((c − k) − k)/k/20 s when c − k > k (`GraphicsCard.scala:243`), clear the flag, and proceed uncharged. The retry repeats the checks; if one fails, it clears the flag and returns the error.

Examples [M: `cmdstream-checks/example.py`, `cmdstream-checks/rev_checks.py`]:
- a full T3 surface upload (512 000 B) has c = 1.95; with k = 1.5 that is a one-tick stall and no pause;
- a 2 MB texture has c = 8, a pause of 0.17 s.

**Why the checks come first** [proposal]. A charge computed from unchecked sizes would make `createTexture(1e9, 1e9)` at T3 pause its machine for about 1 240 days before it fails, computed in `double`, because `Machine.pause` saturates at `Int.MaxValue` ticks (`Machine.scala:255-256`); computed in Java `int`, the same product wraps to 1 321 730 048 B and a pause of about 168 s [M: `cmdstream-checks/rev_checks.py`]. Two plausible implementations would disagree, and both are wrong. `submit`'s min with the cap does the same for an oversized string, which would otherwise have c > 1 and run uncharged in the synchronized retry (`Machine.scala:286-294`).

### 8.3 Energy (M1)

Energy is charged per present and per 2D op class from M1, with provisional, configurable costs modelled on OC's per-cell `gpuClear`, `gpuFill`, `gpuCopy` and `gpuSet` (24:135) [D]. v1 provides the inputs: each opcode's class (§5.2) and the validator's per-class pixel totals, which `FRAME` carries. M0 computes them and charges nothing.

### 8.4 RAM context

2D must run on one 192 KB stick (24:13) [D]:
- That stick gives 353 894 B of real allocator headroom above the kernel, and OC reports apparent memory as real ÷ 1.8 on a 64-bit JVM (10:94) [M, by 10]. Building a string with `table.concat` needs 2–3× its size transiently (10:96) [I].
- A 64 KB segment is about 36 KB apparent. Building it takes about 128–192 KB real, about 71–107 KB apparent, or 37–56 % of the stick's headroom before OpenOS takes its share [M: `cmdstream-checks/rev_checks.py`, arithmetic]. On T1, programs should submit 8–16 KB pieces.
- A 16 KB readback is about 9 KB apparent (10:96).
- What OpenOS leaves free at 192 KB is an open M0 measurement (RM:43); the T1 caps wait for it.
- `opengpu.lua` (M1) chunks submits and readbacks against `computer.freeMemory()`, as 09's readback helper does at ≤ 1/8 (09:62; RM:240).

## 9. The Lua encoder

### 9.1 Rules

[D: 09:67 via 24:30, plus measured hazards]
- Integers only, one record per fixed-arity `string.char` call. No varargs and no `char(unpack(t))` in hot loops: they cause a side-trace explosion on LuaJIT, 3.821 s against 0.960 s on 5.3, while fixed-arity 8-wide code runs 3.7× faster than 5.3 (`OCLJ/bench/results-2026-09-01.md:58-71`; 04:84) [M]. The builders pass 13 to 18 arguments, a width that is unmeasured; §9.5 (b) times it.
- No `//`, bitwise operators or `bit32` in the shared chunk. 5.2 cannot parse the operators, and `bit32` functions are plain C functions the tracer cannot inline (04:84).
- **`math.floor` every operand, flags included.** LuaJIT's `string.char` truncates non-integers: `lj_lib_checkint` converts with `lj_num2int`, an unchecked `(int32_t)` cast (`LJ/lj_lib.c:242-254`; `LJ/lj_obj.h:1005`; called from `LJ/lib_string.c:65-77`) [M]. Stock 5.3 and 5.4 reject them: `luaL_checkinteger` raises "number has no integer representation" (`LUA53/lstrlib.c:167-179`; `LUA53/lauxlib.c:422-424`; `LUA54/lstrlib.c:195-207`) [M]; OC's natives are Eris builds of the same versions [I]. Flooring keeps every runtime identical.
- **No `string.format("%d")`** (5.2 raises at ≥ 2^31, `OCLJ/bench/results-in-machine-phase1-2026-09-04.md:246-250`) [M].
- No pattern functions on buffers: OC replaces them with pure-Lua ports for strings of 500 B and more (`OCT/resources/assets/opencomputers/lua/machine.lua:97-100`) [M].
- **No `computer.realTime()`** (09:67; 19 §8), source-only `load` (10:14), and no `1LL` literals (`OCLJ/docs/ffi-decision.md:22-30`).
- **Pieces go into an array and are joined with `table.concat` per ≤ 4 KB** (04:93); one string per record is fine at 2D sizes.
- JIT-hostile code is a memory hazard, not only a speed one: on a 1 MB OpenOS machine the naive benchmark lost the machine with the JIT on (`OCLJ/bench/results-in-machine-phase1-2026-09-04.md:72-77`) [M].
- **LuaJ** (correctness only, decision 10) runs the `char` path. Its numbers reach Java as `Double` and its strings as fresh byte slices (`OCT/scala/li/cil/oc/util/ScalaClosure.scala:73-75`), and its `_VERSION` is `"Luaj"` (04:70).

**Out-of-range values** [M: `cmdstream-checks/rev_checks.py` for the `char` path, on both number models]:
- i16 fields wrap modulo 2^16 on the `char` path;
- u32 fields (colours, handles) raise "value out of range" in `string.char`, because §9.2's splitters do not reduce them first, unlike 08-C's `u32` (08-C:143-147);
- the `pack` path raises for both (`LUA53/lstrlib.c:1347-1363`);
- non-integers are floored on the `char` path and raise on the `pack` path.

The encoder's contract covers in-range integers only, and byte identity is tested only there [proposal].

### 9.2 Reference record builders (M0 test encoder; ancestor of M1's `opengpu.lua`)

```lua
local char, floor, concat = string.char, math.floor, table.concat
local HEADER, END = "OGPU\1\0\0\0", "\255"

local function clear(c)
  c = floor(c)
  local c0 = c % 256; c = floor(c / 256); local c1 = c % 256; c = floor(c / 256)
  return char(0x01, c0, c1, c % 256, floor(c / 256))
end

local function fill(x, y, w, h, c)
  x = floor(x) % 65536; y = floor(y) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
  c = floor(c)
  local c0 = c % 256; c = floor(c / 256); local c1 = c % 256; c = floor(c / 256)
  return char(0x30, x % 256, floor(x / 256), y % 256, floor(y / 256),
              w % 256, floor(w / 256), h % 256, floor(h / 256), c0, c1, c % 256, floor(c / 256))
end

local function blit(tex, sx, sy, w, h, dx, dy, flags)
  tex = floor(tex); flags = floor(flags) % 256
  local t0 = tex % 256; tex = floor(tex / 256); local t1 = tex % 256; tex = floor(tex / 256)
  sx = floor(sx) % 65536; sy = floor(sy) % 65536; w = floor(w) % 65536; h = floor(h) % 65536
  dx = floor(dx) % 65536; dy = floor(dy) % 65536
  return char(0x31, t0, t1, tex % 256, floor(tex / 256), sx % 256, floor(sx / 256), sy % 256, floor(sy / 256),
              w % 256, floor(w / 256), h % 256, floor(h / 256), dx % 256, floor(dx / 256), dy % 256, floor(dy / 256), flags)
end

local function text(x, y, fg, bg, flags, font, s)
  x = floor(x) % 65536; y = floor(y) % 65536; fg = floor(fg); bg = floor(bg); flags = floor(flags) % 256; font = floor(font) % 256
  local n = #s
  local f0 = fg % 256; fg = floor(fg / 256); local f1 = fg % 256; fg = floor(fg / 256)
  local b0 = bg % 256; bg = floor(bg / 256); local b1 = bg % 256; bg = floor(bg / 256)
  return char(0x33, x % 256, floor(x / 256), y % 256, floor(y / 256), f0, f1, fg % 256, floor(fg / 256),
              b0, b1, bg % 256, floor(bg / 256), flags, font, n % 256, floor(n / 256)) .. s
end
```

A Python transliteration of these four builders, with Lua's floored `%`, matches the `struct`-packed layouts of §5.2 on 20 000 random and edge inputs (`cmdstream-checks/luabuilders.py`, without the flags and font floors), and with them floored it reproduces §9.4's bytes under both number models, doubles only (5.2, OC-LuaJIT, LuaJ) and integer subtypes (5.3, 5.4) (`cmdstream-checks/rev_checks.py`) [M]. Neither shows that the Lua runtimes behave the same; that is S10's byte-identity test (§11.1).

### 9.3 The `string.pack` fast path and detection

[proposal; resolves C20, C21]

```lua
local arch = computer.getArchitecture()          -- "Lua 5.2", "Lua 5.3", "Lua 5.4", "LuaJ", "LuaJIT"
local usePack = (arch == "Lua 5.3" or arch == "Lua 5.4") and string.pack ~= nil and PACK_MEASURED_FASTER
-- with usePack: fill = function(x, y, w, h, c) return string.pack("<BhhhhI4", 0x30, x, y, w, h, c) end
```

- **The names** are OC's `@Architecture.Name` values (`OCT/scala/li/cil/oc/server/machine/luac/NativeLuaArchitecture.scala:23,28,33`; `OCT/scala/li/cil/oc/server/machine/luaj/LuaJLuaArchitecture.scala:24`; `OCLJ/src/main/java/io/github/astronfo/ocluajit/arch/LuaJITArchitecture.java:85`) [M].
- **Never select by a syntax probe or by the absence of `string.pack`.** The pinned LuaJIT parses `& | ~ << >>` (`OCLJ/docs/roadmap.md:146-147`), and decision 10 adds `pack` to OC-LuaJIT (24:30). The second case is now live too: since 2026-10-17 OC-LuaJIT has `string.pack` and 5.3's operators with 64-bit results (`OCLJ/README.md:24`, `:28` and `docs/roadmap.md:207-208` at a98787b), and a program that tests for `pack` takes its 5.3 path there (`OCLJ/README.md:26` at a98787b).
- **OC-LuaJIT stays on the traced `char` path until M0 measures `pack` faster there too,** as on 5.3 (`PACK_MEASURED_FASTER`, below); only then would the selection above name `"LuaJIT"`. The reason given here on 2026-10-14, planned formats that do not cover u8 or i16 (24:139), is gone: OC-LuaJIT's `pack`, built 2026-10-17, has Lua 5.3.6's whole format language, so `<BhhhhI4` and the other 2D layouts pack there (`OCLJ/README.md:24` at a98787b). It is a library C function, registered with `LJLIB_CF` in the fork's `lib_string.c` (`OCLJ/native/luajit/patch-strpack-register.sh:125-135` at a98787b) [M], and a hot loop of `pack` calls stitches the trace (`OCLJ/docs/roadmap.md:207` at a98787b), where 8-wide fixed-arity `string.char` code ran 3.7× faster than 5.3 and the builders' 13 to 18 arguments are unmeasured (28:739); which is faster there is §9.5's to measure.
- **`string.buffer`** (decision 10's item 4; 24:16, :256; RM:220) may replace `table.concat` on OC-LuaJIT only, behind the same architecture check, and not in M0. A buffer must never be live across a yield or a component call, since eris-lj refuses to persist userdata (24:256; RM:226), and it is not exposed before Track L's caveat (a) is closed (RM:225).
- **`pack` calls need no `load()`.** They parse on 5.2 as ordinary calls, so `load()` is needed only for 5.3 operator syntax (04:92).
- **`PACK_MEASURED_FASTER`** comes from M0's measurement, because 07 (≈ 12–25 MB/s, 07:82) and 04 (30–80 MB/s, 04:95) disagree and neither is measured. `<BhhhhI4` is 13 B with no padding, since `pack`'s default alignment is 1 (`LUA53/lstrlib.c:1210-1214`) [M]. The `pack` path must produce the `char` path's bytes for every in-range input (§11.1).

### 9.4 Worked example: one M0 frame on T2

Lua:

```lua
local gpu = component.opengpu
local tex = gpu.createTexture(16, 16)            -- 0x08000000 on a new device
gpu.updateTexture(tex, 0, 0, 16, 16, sprite)     -- sprite: a 256-byte string, index8, rows top first
local s = concat({HEADER, clear(0), fill(10, 20, 100, 50, 196),
                  blit(tex, 0, 0, 16, 16, 150, 40, 1), text(8, 180, 255, 0, 1, 0, "Hi"), END})
assert(#s == 64)
assert(gpu.submit(s)); local frameId = gpu.present()
```

Bytes (64 B; [M] `cmdstream-checks/example.py`, `cmdstream-checks/rev_checks.py`):

| Offset | Record | Bytes |
|---|---|---|
| 0 | header | `4F 47 50 55 01 00 00 00` |
| 8 | `CLEAR` 0 | `01 00 00 00 00` |
| 13 | `FILL_RECT` 10,20,100,50 colour 196 | `30 0A 00 14 00 64 00 32 00 C4 00 00 00` |
| 26 | `BLIT` tex 0x08000000, 0,0 16×16 → 150,40, `KEY0` | `31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01` |
| 44 | `TEXT` 8,180 fg 255 bg 0 `TRANSPARENT_BG` font 0 "Hi" | `33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69` |
| 63 | `END` | `FF` |

Offered to the host, in order: `TEX_CREATE`, `TEX_UPDATE`, `FRAME(1)`. The server tail after `present` holds `FRAME(1)` only, because the two resource records folded at once on a device with no frame in its tail (O6).

On T3 the same frame needs rgb565 operands: `clear(0xFF000000)`, `fill(10, 20, 100, 50, 0xFFFF8000)` (565 value 0xFC00), a 512-byte sprite (16 × 16 × 2 B), and `text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi")`. The segment is again 64 B [M: `cmdstream-checks/rev_checks.py`]. The index8 operands 0, 196 and 255 would be `BAD_COLOUR` there, since their alpha is not 0xFF.

### 9.5 M0's encode measurement (RM:43)

24 asks for a "1 k-triangle encode" on 5.3, 5.2 and OC-LuaJIT (24:248), but v1 has no `DRAW` until M2 [proposal; Q9]. Two loads are proposed instead:
- **(a) M2's likely geometry encoding:** 3 000 vertices × 3 float32 (12 B/vertex). float32×1-4 is among 22's vertex stream formats, beside unorm8×4 and snorm16 (22:66), and the one positions most likely use [I]. Encoded via `string.pack("<fff")` on 5.3 and a `math.frexp`/`char` encoder elsewhere (24:139), byte-identical to `<f`; on OC-LuaJIT, whose `pack` was built on 2026-10-17 (§9.3), both are timed.
- **(b) The M0 2D load:** 1 000 `FILL_RECT` records (13 000 B), which also times the 13-argument `string.char` calls on OC-LuaJIT and, there as on 5.3, the `pack` path's `<BhhhhI4` beside them, for `PACK_MEASURED_FASTER` (§9.3).

Conditions: a 1 MB machine, JIT on for OC-LuaJIT, 20 warm-up runs, the median of 100. Results are reported in µs and in MB/s, so the figure carries over to M2's real layout.

## 10. Forward compatibility

### 10.1 M1

- **Palette and dither** are callbacks producing `PALETTE`/`DITHER` records (kinds 5, 6), not stream opcodes. They change the expand of the next present, and a present preceded only by them runs expand only (24:130-131).
- **`SCISSOR` (0x03)** is frame state, off at every frame start (§3.2).
- **`LINE` (0x32), `COPY` (0x34) and circles (0x35)** take numbers reserved now. `COPY` reads the surface, so it needs an intermediate copy, not a feedback loop (GL33:12123-12130).
- **`BLIT` and `TEXT`** gain integer scale through flags bits 1–2 (29 F8, decided 2026-10-15). s is 1 plus the bits' value, so ×1–×4, and 0 keeps v1's ×1. Each source texel or glyph pixel becomes an s×s block, one factor for both axes, so font 0's cell becomes 6s×13s; fill counts the scaled area, 78·s² px per codepoint for font 0. Defining the bits is additive (§4.3), and the result stays tier E (23:137). `BLIT`'s other source formats arrive with M2's texture formats as a relaxed check (owner, 2026-10-14; 24:248).
- **T3 blending** relaxes the alpha check. Blended ops go through RGBA8 with the same `q` (§3.4), and T3's ops move to the RGBA8 canvas (C7).
- **Frame and canvas modes.** Only `FRAME` records are skipped (O5), and skipping is safe only for self-contained frames (21:100). v1's `KEY0`, `TRANSPARENT_BG` and partial fills read prior pixels, so the proposal for M1 is that every frame-mode frame starts from the cleared image (index 0 or 0x0000), whatever was skipped before it. That is a mode rule with no layout change, settled before M1's release (§4.3).
- **Simple-2D callbacks** (`fill`, `blit`, `line`, `text`, `copy`; 09:61) build a one-record segment Java-side (header, record, `END`) and pass it through the same validator.
- **Persistence:** record serialization and replay under a new epoch (§2.2, §2.5).
- **`api-dump.json`** is generated from the opcode table (§4.2), with the status strings of §3.6.
- **Energy** uses the class totals (§8.3).

### 10.2 M2

- More texture formats (codes ≥ 3), as `BLIT` sources too (24:248), and the `GL_R8` rule for user sampling (already met for index8, C8).
- Buffers with an `updateBuffer` callback shaped like `updateTexture` (zero-copy).
- Opcodes 0x02 and 0x04–0x2F, under the length rule of §3.2. std140 rows travel as a counted byte tail, like `TEXT`'s string.
- `SET_CAMERA`, `SET_GLOBALS` and `DRAW`'s 3×4 model matrix decide between f32 and 16.16 (24:137); f32 operands reject NaN and Inf (§3.3).
- Handle types 2–5.
- `PipelineDesc` stays a configuration-time table, never a stream op (24:30).
- An index-output pipeline kind, if the M2 review accepts question 11 (24:288).

### 10.3 M3

- `dispatch(k, gx, gy, gz, bindings, uniforms)` stays a callback with byte-string arguments (24:139).
- Compute output reaches graphics only as server-produced `UPLOAD` records in the same tail (kind 8; 24:105, :133 (c)). Lua's streams cannot contain them (§2.3).
- `copyBuffer` across cards is a server-side dependency plus an `UPLOAD` into the consumer's tail (24:268) [I].
- 0x40–0x4F stay reserved.

### 10.4 M4 and parked options

- LAN carries pixels only (§2.5).
- The freeze lints G2 through the API dump (24:253).
- Option (b) would carry segments and `TEX_*` records verbatim, splitting large `TEX_UPDATE`s into row bands (§2.5).
- Option (d), a software `RenderHost` (21 §5), would execute the same records, as the oracle does.

### 10.5 Never in the protocol

- GL types or enums: formats and kinds are OpenGPU codes [D] (24:238). Caps are OpenGPU constants, never host limits [proposal, from 24:133 (a)].
- Opcodes that return host data, or `barrier`, `sync` or `fence` [D] (24:133 (a), (b)).
- Pointers, offsets into host memory, Java references, wall-clock times or bytecode; program sources travel as Lua strings (24:136).
- Server-only record kinds in a Lua stream (§2.3).
- Optional fields, padding or alternative encodings, which would break the canonical form (§3.2).

## 11. S6's test plan for the codec and validator

### 11.1 Round trips and byte identity

- A Java test encoder (one builder per opcode, mirroring §9.2) feeds the reader, and the decoded fields must equal the inputs.
- Edge values for every i16 field: −32 768, −1, 0, 1, W − 1, W, 32 767.
- Colours at the edges of each format; `TEXT` with n = 0, 1 and 1 024 and with 1- to 4-byte UTF-8.
- Canonical form: decoding any accepted segment and re-encoding it reproduces it exactly.
- For S10: Lua `char` bytes == Java bytes on 5.3 and 5.2 (gating), and on OC-LuaJIT locally (24:256); the `pack` path equals the `char` path on 5.3, and on OC-LuaJIT locally if M0 selects `pack` there (§9.3). 5.4 (both paths) and LuaJ (the `char` path) run as non-gating legs where ocelot-brain loads them [I], otherwise in M1's LuaJ pass (24:249).

### 11.2 Negative cases (structured: code, offset, opcode)

Every expected result comes from §3.6's tables, through the naive validator of §11.3; `cmdstream-checks/rev_checks.py` implements a small one and checks the cases marked [M].
- **Lengths:** the empty string, 1–7 bytes (`TRUNCATED_HEADER`), and exactly the header (`MISSING_END` at 8) [M].
- **Header fields:** each magic byte wrong (`BAD_MAGIC`, 0); versions 0, 2 and 255 (`BAD_VERSION`, 4); each flags bit (`BAD_FLAGS`, 5); reserved values 1 and 0x8000 (`BAD_RESERVED`, 6).
- **Framing:** `END` followed by any byte (`TRAILING_BYTES` at the `END`) [M]; unassigned opcodes 0x00, 0x02, 0x32, 0x40 and 0xFE (`UNKNOWN_OPCODE`); every M0 record cut at every byte boundary, with and without a following `END`. A cut record can swallow the `END`: a `FILL_RECT` one byte short followed by `END` reads 0xFF as its colour's top byte, which is `BAD_COLOUR` on index8 and then `MISSING_END` on rgb565 [M]. `TEXT` with n beyond the end (`TRUNCATED_RECORD`, even when a field is also bad) and with n = 1 025 (`TEXT_TOO_LONG`) [M].
- **TEXT strings:** overlong forms, surrogates, > U+10FFFF, truncated sequences and stray continuation bytes (`BAD_UTF8`); U+0000, U+000A, U+007F and U+0085 (`CONTROL_CHAR`); a string with both, where the first decides.
- **Colours:** index8 0x100 and 0xFF000000; rgb565 alpha 0x00 and 0xFE (`BAD_COLOUR`).
- **Handles:** 0, 0x80000000 and type 0 with other bits set (`BAD_HANDLE`); an unassigned type 9 (`UNKNOWN_TYPE`); type 2 (`WRONG_TYPE`); a wrong generation, a freed handle and a never-allocated slot (`STALE_HANDLE`); a stale handle with bad flags, where the handle decides [M]. A live handle of another device is accepted, and the test documents it (§6.1).
- **Flags and font fields:** `BLIT` and `TEXT` flags 0x02 and 0x80 (`BAD_FLAGS_FIELD`); `TEXT` fonts 1 and 255 (`UNKNOWN_FONT`), bad flags with a bad font, where the flags decide, and a bad font with bad UTF-8, where the font decides (the font cases [M]).
- **Caps ±1:** submit bytes, cumulative frame bytes, records and fill, with the frame byte cap reported before any header error [M]. A rejected submit leaves the totals and segments unchanged, and the next valid submit is accepted.
- **Callbacks (S7):** every row of §3.6's callback table, with saturated and fractional arguments (10^10, −10^10, 16.7; NaN raises OC's argument error), `x + w` at the `int` limit, and `createTexture(1e9, 1e9)`, which returns `"invalid texture size"` uncharged.

### 11.3 Fuzzer

A seeded mutation fuzzer in `:core`'s JUnit tests, with no new dependency, run with 10^5 cases per CI run [E: seconds] over generated valid segments (grammar-based, edge-heavy fields). Mutations are bit flips, byte insertion and deletion, truncation, splicing of two segments, and corruption of the `TEXT` count. Properties:
- (P1) the validator never throws, ends in O(n), and returns only §3.6's codes;
- (P2) it agrees, in acceptance, code and offset, with a second, deliberately naive validator written straight from §3.6's tables and §5's layouts (differential);
- (P3) every accepted segment is canonical (§11.1);
- (P4) the oracle runs every accepted segment without exceptions and writes in-bounds only;
- (P5) splitting an accepted frame's records over k segments gives the same oracle output as one segment, for splits that stay under the caps (each extra segment adds 9 header and `END` bytes to the frame byte total).

### 11.4 Oracle goldens and S7's ordering tests

- **Hand-computed vectors** as hex fixtures in `:core`'s test resources: each op on a small surface, with clipping at every edge, negative and zero sizes, `KEY0`, `TRANSPARENT_BG`, the synthetic G and its missing glyph.
- **Exhaustive quantize checks:** `q` against floor(c·m/255 + ½) for all c, and `q(expand(v)) = v` for all 65 536 values.
- **Multi-frame canvas carry-over.**
- **The ordering examples of §2.2,** run through S7's `Device` with `FakeRenderHost` and the oracle, asserting both the offered sequence and the server tail.
- **Lifecycle:** a stop or start with frames in flight gives K's pixels on the oracle whether `FakeRenderHost` ran the old frames or not; `release` and a later `open` of the same key; late readbacks of an old epoch are dropped.
- **For S8:** the generated segments on `GlTestHost` equal the oracle byte for byte on llvmpipe and on the owner's Intel, NVIDIA and AMD (S8: done on CI-parity llvmpipe under WSL, Intel and NVIDIA, on all three legs in both quad forms, and on CI's own llvmpipe, Mesa 25.2.8, in run 38899286849 of 08ca13b, run 38922626582 of 2c4528e and run 40073138862 of 0761bc4; AMD later, Q-S8-2; "S8 implemented"). That holds for instanced and expanded quads and on the GLSM legs, since tier E is 0 px everywhere (23:137) [D]. Lua bytes == FBO bytes is S10's (23:186).

## 12. Open questions for the owner

Each default stands until the owner decides. On 2026-10-14 the owner accepted the defaults of Q2–Q13 and answered Q1; on 2026-10-15 they took 29's F4, deferred its F5, accepted its F8 for M1 and answered its F3, F6 (in part), F7 and F9, and had the writer decide its F5 for M1 (sections "F4 taken, F5 deferred", "F8 decided", "F3, F6, F7 and F9 answered" and "F5 decided").

1. **Q1. Glyphs for M0's `TEXT`.** *Default:* a dev-only source, OC's own `font.hex` read from OC's jar (narrow glyphs, a fixed subset of 351), with the synthetic table of §7.1 in CI; the licence check and the shipped font stay in M1. *Why:* M0 does not release, the format is font-independent, and the check has no M0 consumer. *Answered 2026-10-14, after the owner's mcer12/PixelFonts proposal was evaluated and a font survey (29) proposed Spleen 8×16: font 0 is misc-fixed 6x13, shipped in M0, with 8x13 as the alternative "if it doesn't look right as the default size" (the owner). The 8×16 cell had come from OC's `font.hex`, not from a need of the protocol, since OpenGPU draws text at any pixel (§7.1, §7.3). S6 uses the synthetic table either way.*
2. **Q2. Malformed streams.** *Default:* `nil, "<offset>: <OP>: <reason>"` as a return value, never a Lua error, from one closed, ordered set of codes (§3.6); only the status strings are API. *Why:* it matches `present`'s statuses, keeps `logLuaCallbackErrors` quiet, lets programs report or retry, and lets two validators agree exactly. *Answered 2026-10-14: default accepted.*
3. **Q3. Colour operands.** *Default:* one u32 whose meaning follows the surface format: an index on index8, `0xFFRRGGBB` on rgb565 quantized by `q`, with alpha required to be 0xFF until M1 (§3.4). *Why:* one codec for every tier, and M1's blending relaxes a check instead of adding opcodes. The alternative, raw RGB565 u16 on T3, would need a second layout for blended ops. *Answered 2026-10-14: default accepted.*
4. **Q4. Texture calls while a frame is open.** *Default:* updates are visible to the whole open frame; frees are deferred until right after it (O3, O4); resource records ahead of the tail's first `FRAME` fold at once (O6). *Why:* well-defined, WebGPU-like, a validated frame never sees a freed texture, and the masters stay exact for replay. The alternative is to reject frees of textures the open frame uses. *Answered 2026-10-14: default accepted.*
5. **Q5. Provisional caps** (§8.1). *Default:* accept them, in the binary bytes given, until M0's measurements, with 64 B per resource record in the queued-bytes bound. T1 (M1) may get a 16 KB per-submit cap once OpenOS's free memory at 192 KB is known (§8.4). *Why:* each is grounded in a decided budget (0.2 ms per call, the tail bound, GL 3.3's 1 024) or a report's figure, and M0 measures the rest. *Answered 2026-10-14: default accepted.*
6. **Q6. `BLIT`'s `KEY0` in M0.** *Default:* yes: texel value 0 is not written. *Why:* sprites need transparency, it is one `discard` and one line of oracle, and it stays tier E. The alternative leaves every flag bit reserved until M1. *Answered 2026-10-14: default accepted.*
7. **Q7. Invalid UTF-8 and control characters in `TEXT`.** *Default:* reject them (§7.2). *Why:* the result stays deterministic without a replacement policy in the oracle, and M1 can still give controls a meaning. OC's `checkString` decodes through `new String(bytes, UTF_8)` (`ArgumentsImpl.scala:169`), which replaces malformed input [I]. *Answered 2026-10-14: default accepted.*
8. **Q8. Version policy.** *Default:* v1 is editable in place until M1's release; afterwards, breaking changes bump the version, and `submit` keeps accepting every version the mod decodes (§4). *Why:* side files force the old decoders to exist anyway, and old programs keep working. *Answered 2026-10-14: default accepted.*
9. **Q9. M0's encode benchmark.** *Default:* (a) 3 000 × 3 float32 vertices and (b) 1 000 `FILL_RECT`s, on 5.3, 5.2 and OC-LuaJIT (§9.5). *Why:* float32 is among M2's vertex stream formats (22:66) and the likely one for positions, so an integer-only number would not carry over. It needs a 5.2 float32 encoder about 0.5 d early [E]. *Answered 2026-10-14: default accepted.*
10. **Q10. T3's host path in M0** (C7). *Default:* M0 writes R16UI directly, with operands quantized on the CPU by `q`; M1 moves T3's ops to the RGBA8 canvas its blending needs. *Why:* M0 has no blending, the bytes are identical (`q(expand(v)) = v`), and it is the simpler S8. The alternative, 24:197's literal reading, renders T3 into RGBA8 and quantizes at present already in M0: S8 then builds M1's T3 structure and M0's T3 measurements represent M1, for about +0.5–1 d [E]. Index8 writes R8UI directly either way (20:158; 26:371). *Answered 2026-10-14: default accepted.*
11. **Q11. `GL_R8` storage for index8 textures from M0** (C8). *Default:* yes. *Why:* it is 24:205's rule, exact under `floor(r * 255.0 + 0.5)`, and M1 and M2 then migrate nothing. The alternative is R8UI in M0, migrated in M1 when 26:93 schedules the rule. *Answered 2026-10-14: default accepted.*
12. **Q12. Lifecycle** (§2.2). *Default:* a computer stop or start offers `RESET` with K's pixels, and the host restores them; card removal and chunk unload `release` the host's objects and, in M0, drop the device's state, which M1 persists and replays. *Why:* host, oracle and server agree after every epoch change, and the M1 path needs no undoing. The alternative keeps the host's surface as it stands (21:107's literal text), so frames discarded by the change may still show in the next readback, and the oracle can match the host only after a full `CLEAR`. *Answered 2026-10-14: default accepted.*
13. **Q13. Other changes to decided or carried text.** *Default:* accept:
    - (a) 08-C's layouts as 09:67 carries them: `CLEAR` loses depth and mask, `BLIT`'s `blend` byte becomes `flags`, `TEXT` gains `flags`, and the compute opcodes leave the stream (24:139 already moved `dispatch`);
    - (b) 09's `readPixels(target, x, y, w, h, fmt, maxBytes)` (09:62) becomes `readPixels(x, y, w, h)`;
    - (c) G8's `SWAP_BYTES`/`LSB_FIRST` rule applies on both contexts (24:203);
    - (d) the SPI method `submit` becomes `offer`, and `open` joins it (21:52; 24:170);
    - (e) `RESET` overwrites the kept target with K's pixels (21:107; Q12).
    *Why:* each is listed under "Edits to other documents", and each follows from a rule above. *Answered 2026-10-14: default accepted.*

## 13. Sources

- **Reports:**
  - 24 (§1 decisions 3, 7, 10, 12; §2.1; §3.1–3.7; §4; §5; §6 M0–M4 and the OC-LuaJIT inputs; §7 questions 3 and 6 with their answers; Review notes; §9 and amendments);
  - 20 §1, §3; 21 §1.3, §2.1–2.5, §3.1–3.3, §5, §6; 22 §1.3; 23 §3.4–3.5, §4.1–4.2, §5;
  - 25 §4.1–4.2 (C01, C09–C12, C29–C34, C57, C94–C98, A11, A16), §5;
  - 26 §1.2–1.8 (G1, G8, G10, G12, G13, G14, G16), §3.2 (R5);
  - 09 §3.2, §3.5, §3.6, §5 question 4; 08-A §3, 08-B §3, 08-C §3 and §6;
  - 04 §4–§6, 06 §1, 07 §2, §5 and its implications, 10 F6–F8, 11 §2, 12 §1, 19 (Summary, §8);
  - `docs/roadmap.md` (M0, M1, M2, Track L, Track D, the parked list).
- **Repository:** `core/src/main/java/io/github/mindbound/opengpu/core/Handles.java:3-8, :11-17, :49-51`; `gl-testkit/src/test/java/io/github/mindbound/opengpu/gltestkit/GlSmokeTest.java:118-127, :169-172`.
- **OpenComputers 1.12.64-GTNH** (`OCT/`):
  - `scala/li/cil/oc/server/component/GraphicsCard.scala:64-74, :100-106, :135-152, :143, :171-174, :221-251, :482-489`;
  - `scala/li/cil/oc/server/machine/Machine.scala:255-256, :286-294`;
  - `scala/li/cil/oc/server/machine/luac/NativeLuaArchitecture.scala:23, :28, :33, :72-81`;
  - `scala/li/cil/oc/server/machine/luaj/LuaJLuaArchitecture.scala:24`;
  - `scala/li/cil/oc/server/machine/ArgumentsImpl.scala:60-112, :169, :179-185`;
  - `scala/li/cil/oc/util/ScalaClosure.scala:73-75`;
  - `scala/li/cil/oc/client/renderer/font/FontParserHex.java:47-48, :101-109`;
  - `scala/li/cil/oc/util/PackedColor.scala:136-141`;
  - `resources/assets/opencomputers/lua/machine.lua:97-100, :1071-1074`;
  - `resources/assets/opencomputers/font.hex`; the tree's `README.md:131-134`.
- **OC-LuaJIT 62970a0** (`OCLJ/`): `src/main/java/io/github/astronfo/ocluajit/arch/LuaJITArchitecture.java:85` (`:82` at aa12418); `bench/results-2026-09-01.md:58-71`; `bench/results-in-machine-phase1-2026-09-04.md:72-77, :246-250`; `docs/roadmap.md:146-147`; `docs/ffi-decision.md:22-30`; at a98787b (§9.3, 2026-10-24), `README.md:24`, `:26`, `:28`, `docs/roadmap.md:207-208` and `native/luajit/patch-strpack-register.sh:125-135`. LuaJIT 1ee778a4 (`LJ/`): `lib_string.c:65-77`, `lj_lib.c:242-254`, `lj_obj.h:1005`. Stock Lua: `LUA53/lstrlib.c:167-179, :1210-1214, :1347-1363`; `LUA53/lauxlib.c:422-424`; `LUA54/lstrlib.c:195-207`.
- **Angelica:** 2.2.8 (`ANG8/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/`): `GLStateManager.java:2098-2112, :4287-4292, :5284-5298`; `states/PixelUnpackState.java:35-36`. 2.2.32 (`ANG32/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/`): `GLStateManager.java:7000-7008`.
- **LWJGL 2.9.4 sources:** `org/lwjgl/opengl/GLChecks.java:182-230`; `org/lwjgl/opengl/GL11.java:2954-2960`.
- **GL 3.3 core specification** (`opengpu-refs/web22/gl33core.txt`): `:4324-4349` (`texelFetch`), `:6388-6389` and `:11104-11105` (pixel store), `:7262`, `:7279` (required formats), `:7745-7751` (null data), `:7959-7970` (`TexSubImage` bounds), `:10235-10236` (no blending on integer buffers), `:10964` (integer clears), `:12123-12130` (feedback loops), `:14799-14808` (`MAX_TEXTURE_SIZE`).
- **Scripts** (`cmdstream-checks/`, run with `python -I`): the drafter's `q565.py` (quantize and expand properties), `example.py` (§9.4's bytes, handles, quantize examples, charges, caps) and `luabuilders.py` (§9.2's builders against §5.2's layouts); the reviser's `rev_checks.py` (both number models with floored flags, the T3 variant, out-of-range behaviour, palette anchors, caps in bytes, charges and the unchecked-charge pause, the overflow-safe region checks, the synthetic glyph table, and a naive validator run over §11.2's marked cases; on 2026-10-15 it, `example.py` and `luabuilders.py` gained `TEXT`'s font byte, and it the `UNKNOWN_FONT` cases); and Q1's `q1_font0.py` (font 0's cell, coverage and atlas, and the synthetic table at 6×13 and 8×13, run on the BDFs as data).

## Edits to other documents (applied 2026-10-14)

Applied on 2026-10-14, after the owner accepted the defaults of Q2–Q13, so each "if Qn's default stands" below holds; 08-C, 09, 21, 23, 24, 26 and the roadmap list their edits in a dated section of amendments after M0 step S5, and research/README.md's process step 9 records the step. One item waited for Q1: the roadmap's `TEXT` decision point (RM:61), which recorded only that Q1 was open while mcer12/PixelFonts was evaluated; it has recorded Q1's outcome since Q1 was answered (section "Q1 answered", below). In this note, the header's status, §12's answers and this heading changed the same day, and C24's citation of the 2026-10-10 amendment was corrected from 24:466 (HEAD's numbering) to 24:467, the line this list names, with the citation paragraph after the header noting that shift. Every `NN:L` and `RM:L` citation still names the same line, since the edits moved no cited line; §1.3 and this list quote those lines as they read before the edits.

- **24 §2.1 boundary row (24:30) and the 2026-10-10 amendment (24:467):** `LuaJITArchitecture.java:82` held at aa12418; at 62970a0 it is `:85`. Pin the commit with the line.
- **24 §3.1 `:core` row (24:80):** the `RenderHost` SPI has `open(DeviceKey, SurfaceDesc, ReadbackSink)` and `offer`; `FakeRenderHost` takes a record renderer and runs on explicit steps.
- **24 §3.1 root row (24:85):** the opcode table, the version and the codes live in `:core` (`core.cmd`); the `api` package's `CommandFormat` copies them as compile-time constants (§4.2).
- **24 §3.3:**
  - `getCaps` gains the §8.1 keys [proposal];
  - `submit` returns `nil, "<offset>: <OP>: <reason>"` from §3.6's codes; the status strings are API;
  - the M0 texture callbacks are named, with §3.6's results;
  - `readPixels(x, y, w, h)` for v1.
- **24 §3.5 OC-worker row (24:170):** "append to tail, `submit`" → "append, `offer`".
- **24 §3.6 (24:179):** the ordering rules O1–O7; the offered sequence and the server tail; resource records ahead of the tail's first `FRAME` fold at once; queued resource bytes count 64 B per record; the lifecycle table of §2.2, with `RESET` carrying K's pixels; `INIT` may be `RESET` plus `TEX_*` records.
- **24 §3.7, common rules (24:197):** if Q10's default stands, M0's opaque 2D writes R8UI/R16UI directly with operands quantized on the CPU by `q`; the quantize pass is the RGBA8 path for M1's T3 canvas and M2's 3D.
- **24 §3.7, state rules (24:203):** `SWAP_BYTES`/`LSB_FIRST` are forced on both contexts; GLSM caches pack state from 2.2.32 and the rule stands; on the vanilla leg the unpack PBO is unbound around `ByteBuffer` uploads.
- **24 §3.7, quantize paragraph (24:205) and §6 M1 (24:249):** the owner's 2026-10-14 decision keeps the other formats in M2 (24:248), so "as `BLIT` sources gain formats" leaves M1's row [D: owner]; if Q11's default stands, the `index8`/`GL_R8` rule is met from M0 (C8).
- **24 §4, "Carried now" (24:232):** the `RESOURCE` record is `TEX_UPDATE`, whose handle carries the generation (G1); the content hash is added when a consumer exists; the asynchronous SPI is `offer`/`onReadback`.
- **24 §6 M0 (24:248) and the readiness-check amendment (24:514):** cite 28 for the command stream and the texture path; the sprite readback is the M0 self-test's (RM:40), not 23 §5.2's.
- **24 §9 and a new amendments section:** a row for 28, and an "Amendments after M0 step S5" entry listing these edits.
- **Roadmap:**
  - mark S5 done once the owner accepts 28 (RM:51); point M0's "2D render path" item (RM:35) and S6–S8 (RM:52-54) to 28 §1.2;
  - record Q1's outcome at the `TEXT` decision point (RM:61);
  - M0 measurements (RM:43): §9.5's two encode loads and the validator's time for a 65 536-byte submit;
  - Track L (RM:217): OC-LuaJIT's planned `pack` formats lack u8 and i16, so 2D records stay on the traced `char` path; adding `B`/`h`/`H` is the owner's call;
  - Track D (RM:258): the `:core` opcode table is the source of `api-dump.json`'s opcode and record-layout entries.
- **research/README.md:** an index row for 28, and a process step for M0 S5.
- **09 §3.2 (09:67):** a pointer to 28, which supersedes 08-C's table as the v1 layout.
- **08-C §3 (08-C:112):** a pointer to 28: `CLEAR` loses depth and mask, `BLIT`'s `blend` byte becomes `flags`, `TEXT` gains `flags`, and the compute opcodes are not in v1.
- **21 §1.3 (21:52-56):** `submit` → `offer`, and `open(DeviceKey, SurfaceDesc, ReadbackSink)` added.
- **21 §2.5 (21:107):** the host restores K's pixels from `RESET`.
- **21 §3.3 (21:137):** dirty-rect payloads are tightly packed, so G8's `ROW_LENGTH` 0 applies.
- **23 §4.1 (23:165):** `FakeRenderHost` runs a pluggable record renderer on explicit steps: zero pixels by default, the tier-E oracle from `:core`'s test fixtures in tests.
- **26 §1.4 G8 (26:80-82):** both contexts, and the 2.2.32 note.
- **26 §1.4 (26:93):** if Q11's default stands, the `GL_R8` rule applies from M0.

## Q1 answered (2026-10-14)

The owner answered Q1 after 29's survey: font 0 is X11 misc-fixed 6x13, with 8x13 kept as the alternative. Changed in place above this section, with no line added or removed, so every `NN:L` citation of this note still names the same line: the header's status and its citation note (28:3, :5), summary item 9 (28:17), the fill rule (28:284), §4.3's glyph row (28:376), `TEXT`'s result and oracle loop (28:469, :503), §7.1 (28:628-641), §7.2's wide-glyph item (28:651), §7.3 (28:655), §8.1's full-screen example (28:694), §12's opening and Q1 (28:942, :944), the scripts list (28:990), and the 2026-10-14 edits list's Q1 sentence (28:994). The new script is `cmdstream-checks/q1_font0.py`. The roadmap's S5 entry and `TEXT` decision point (RM:51, :61), 24 §6 M0, risk 16 and 24 §9's row for 28 (24:248, :320, :337), each with a dated amendment, research/README.md's rows for 28 and 29 and its process step 10, and 29 itself were revised the same day.

## F4 taken, F5 deferred (2026-10-15)

The owner answered two of 29's questions on 2026-10-15. F4: "yes, let's add the ID byte now". F5: "undecided but I *think* we can skip CJK support for M0". So `TEXT` carries a u8 `font` at offset 14 and is 17 + n B, M0 defines font 0 only and rejects any other id as `UNKNOWN_FONT` after the flags check, and M1's further ids are a relaxed check (§4.3). M0 keeps one cell and 78 px of fill per codepoint, ships no `wcwidth` table and owes no musl notice; M1 settles the advance of wide codepoints before v1 freezes (28:384), with 29's F7. Changed in place above this section, with no line added or removed, so every `NN:L` citation of this note still names the same line: the header's status and its citation note (28:3, :5), summary items 3 and 9 (28:11, :17), §3.6's rows 13 and 20 and its field order (28:310, :318, :324; `UNKNOWN_FONT` shares row 20 so that the rows keep their numbers), §4.3's advance point (28:384), §5.2's `TEXT` row and departures (28:426, :429), §5.6's checks and result (28:465, :469), §7.1 (28:628, :639), §7.2's wide-glyph item (28:651), §7.3 (28:655), §8.1's full-screen example (28:694), §9.2's `text` builder and its check sentence (28:786-787, :792, :796), §9.4's worked frame, now 64 B (28:824-825, :829, :837-838, :842), §11.2's flags item (28:918), §12's opening (28:942) and the scripts list (28:990). Q13 (a) and the 2026-10-14 edits list (28:957, :1020) still record `TEXT`'s departures as accepted that day; §5.2 governs. `example.py`, `luabuilders.py` and `rev_checks.py` were updated and re-run (61 checks pass). 29, the roadmap's `TEXT` decision point (RM:61), 08-C's pointer (08-C:112), 24 and research/README.md record the answers the same day.

## F8 decided (2026-10-15)

The owner accepted the defaults of 29's F8 on 2026-10-15: "I think the defaults make sense, we can record them." In M1, `TEXT` and `BLIT` gain integer scale ×1–×4 in flags bits 1–2, one factor for both axes, with fill counted on the scaled area (§10.1). M0 is unchanged: both opcodes still reject those bits as `BAD_FLAGS_FIELD` (§5.5, §5.6), and defining them in M1 is additive (§4.3). Changed in place, with no line added or removed: §1.1's M1 row (28:32), §5.5's and §5.6's flags sentences (28:461, :467), §7.1's list of 29's open questions (28:639), §10.1's `BLIT` item (28:859) and §12's opening (28:942). 29 §6.1 and §8 F8 record it.

## F3, F6, F7 and F9 answered (2026-10-15)

The owner answered 29's remaining font questions on 2026-10-15. F3: "I think we can keep using misc-fixed, it's versatile, easy to read, has a good list of sizes, etc." F6: "proportional font support is a definite yes but let's leave the question of *which* fonts to include for later if that's alright". F7: "the misc-fixed CJK variants are a good (and obvious) choice, we can support those; as for any other CJK fonts, same as F6, let's leave that for later". F9: "sure, let's clean up". None of them changes v1's layouts or M0: M0 still defines font 0 only (§5.6), and M1's font ids 1–6, misc-fixed's faces (29 §5.3), arrive as a relaxed check (§4.3). M1's wide fallback, 12x13ja, and its proportional fonts both need the advance and fill rule that 29's F5 proposes, which M1 settles before v1 freezes (28:384); its proportional fonts start with Tiny5, Tiny5 Duo and ANAKRON, chosen later the same day (29 F6). F9 deleted 29's comparison-only ROM files, which no part of this note used. Changed in place, with no line added or removed: §4.3's advance point (28:384), §7.1's list of 29's fonts (28:639), §7.2's wide-glyph item (28:651) and §12's opening (28:942). 29 §8 records the answers, as do the roadmap, 24 and research/README.md. After a provenance check the same day (29 §10), the owner narrowed F7 to 12x13ja: "Sure, in that case let's go for (1) - no 18-pixel CJK for now."

## F5 decided (2026-10-15)

The owner left 29's F5 to the writer on 2026-10-15: "I don't really have an informed opinion regarding F5 (I haven't worked with fonts and glyph tables before to any meaningful extent besides just rendering them) - let's go with whichever option looks the most sensible to you." It is decided for M1 (29 §6.2–§6.4, §8 F5). Cell fonts advance 2 cells where musl's `wcwidth` is 2 and 1 cell otherwise, zero-width and non-printable codepoints included; OC's overrides from its own `font.hex` are not applied, because they describe OC's font (29 §6.2). Fill is the advance in cells·w·h·s², 78 or 156 px per codepoint for font 0 at s = 1. Proportional fonts advance by their glyphs and are charged maxAdvance·lineHeight·s². Glyphs narrower than their advance, missing glyphs included, are centred; layout is computed unscaled and multiplied by s; every box pixel the glyph leaves unset takes bg unless `TRANSPARENT_BG` is set; and programs measure text with M1's `textWidth`. M0 is unchanged: one cell and 78 px per codepoint (§5.6; 28:284, :650). In M1 the rule changes the pixels and the fill of streams that contain wide codepoints, which v1 allows while it is a draft (§4.3, 28:379), and it settles §4.3's open point (28:384) before v1 freezes. M1 adds musl's MIT section to NOTICE. Changed in place, with no line added or removed: the header's status (28:3), summary item 9 (28:17), §4.3's advance point (28:384), §7.1 (28:639), §7.2's missing-glyph and wide-glyph items (28:650, :651) and §12's opening (28:942).

## S6 implemented (2026-10-15)

M0 step S6 (RM:52) built §1.2's S6 row in `:core`: the codec (`cmd/`: the opcode table as data, `Validator`, `SegmentReader`, `Utf8`, `Clip`, the error codes of §3.6), the colour functions and the palette (`colour/`), `GlyphTable` (`font/`) and `SurfaceDesc`; the tier-E oracle, the synthetic G, the test encoder, a map-backed live view and the commit model in `:core`'s test fixtures, which no jar ships (`DevJarLayoutTest.noTestFixtureClassShips`); and §11.1–§11.4's S6 tests. Nothing above this section changes; `Handles.java:7`, which 28:516 cites as leaving the type numbers to S5, now names `HandleTypes`. Where this note left a point open, S6 settled it as follows, and S7, S8 and S10 build on these:

- **Messages** (28:334, :347). Every code has one reason text (`BAD_COLOUR` one per surface format, `<field>` being `colour`, `fg` or `bg`), each pinned by `StreamErrorMessageTest`, with the six examples of §3.6 verbatim. Row 14's text extends 28:264 to name the field and its value: `"<field> 0x%08X: alpha must be 0xFF before blending (M1)"` on rgb565 and `"<field> 0x%08X is not an index 0-255"` on index8, with the value as detail; row 9 reads `"END is not the last byte (%d after it)"` and rows 11 and 13 `"record needs %d bytes, only %d left"`. `<OP>` is upper-case hex for an unassigned opcode (`0xFE`, 28:293). `StreamError` adds a `reason`; its `opcode` is −1 for the `submit` and `header` rows and 0xFF for rows 8–9. Reasons are formatted in `Locale.ROOT`.
- **UTF-8** (row 21, 28:319; §7.2, 28:646). The detail is the string index of the offending sequence's first byte: the lead byte, or the stray continuation or invalid byte itself. Noncharacters, private-use codepoints, U+FEFF and unassigned codepoints are well-formed and not controls, so they are accepted and draw G's glyph, or the missing glyph where G has none (the synthetic G has none for them; `6x13.bdf` has 107 private-use glyphs from U+E000).
- **The fill cap** (28:279, row 23) is compared with the sum of the four classes' totals, as §8.1's single `maxFrameFill` says. `maxFrameRecords` (row 22) is `FrameLimits.frameRecords`, 4 096 at every tier.
- **`maxTextBytes`** (28:684) stays a format constant, `Opcodes.TEXT_MAX_BYTES` = 1 024, not a `FrameLimits` field: row 12 is a structure check, and raising it later is a relaxed check (§4.3).
- **The live view** (28:120) also answers a texture's width and height, read only after rows 15–20 pass, because `BLIT`'s fill uses §5.7's ranges (28:500). S7's `Device` must answer without allocating, since validation allocates nothing per record (28:287); the fixtures' map-backed view boxes, so the allocation test and the bench use a view that does not.
- **`SurfaceDesc.tier`** (28:193) is not read by the codec or the oracle, which take the width, height and format (28:37); the caps come from `FrameLimits`. `tier` is S7's.
- **Clipped areas** (28:284; §5.7, 28:499-500) are max(0, x1 − x0)·max(0, y1 − y0) for `FILL_RECT` and max(0, i1 − i0)·max(0, j1 − j0) for `BLIT`, so an empty or inverted range counts 0 px.
- **Dev-build invariants** (28:202) are Java `assert`s, on in every Gradle test task; the reader asserts a v1 header and `END` as the last byte and throws on an unassigned opcode.
- **The synthetic G** (28:633) is generated by the test fixtures' `SyntheticGlyphs` from the rule (own code, MIT), with `SyntheticGlyphsTest` repeating `q1_font0.py`'s checks of `synthetic(6, 13)` (189 drawn glyphs, pairwise distinct, every column used, nothing outside 0xFC) and pinning eight glyphs' rows. §11.4's hand-computed vectors are `oracle-goldens.txt`, in the test-fixture resources rather than the test resources of 28:933, so S8 can replay them on GL, and an independent Python model, `cmdstream-checks/oracle_goldens.py`, re-derives every grid and total.
- **Reset order** (§6.1, 28:534). A reset frees the live handles in ascending slot order, each going to the free queue's tail as `free` does, after the slots already queued. S7's `Device` does the same.
- **§11.2's foreign handle** (28:917): S6 tests that the validator accepts whatever the live view reports live; the two-device test is S7's (§6.1).
- **The validator's time** (28:680, RM:43). `:core:benchTest` on JDK 8 (Temurin 1.8.0_504, Windows 11, 24 threads), a T3 surface with caps of 65 536 B per submit and per frame, 4 096 records and no fill cap, four loads at the 65 536-byte cap (records, ASCII text, CJK text, a reject at the last record): warm medians of 16–33 µs (1 923–3 858 MiB/s), p99 at most 62 µs, against 28:680's ≥ 0.5 GB/s; at 16 384 and 4 096 B the medians are 4–8 µs and 1.5–2.1 µs. The first call of a cold JVM, at the cap and interpreted, took 3.5–3.7 ms in two runs. The warm median is the comparison; the cold figure goes to the owner with M0's measurements, and Q5's caps stay provisional until then. An accepted validate call allocates 80 B, the result and its totals, whatever the record count; a rejected one also allocates its `StreamError` and reason text.
- **The font-0 converter** (28:631): the owner scheduled it for S8 on 2026-10-15 ("Sure, let's build the converter in S8."); until then `GlyphTable` is filled from the synthetic G only.

`cmdstream-checks/rev_checks.py`'s naive validator decoded a `TEXT` string before looking for controls, so a control before bad UTF-8 gave `BAD_UTF8`; it now follows 28:648's single scan, with two new checks (63 pass). The tests (521 in `:core:check`, 0 skipped) cover §11.1–§11.4's S6 bullets: round trips and byte identity, every negative case of §11.2 with its code, offset and op, a 10^5-case fuzzer whose naive validator shares no code with the main one (P1–P5), the 46 goldens, the worked frame of §9.4, exhaustive checks of `q`, `sv`, expand and UTF-8, and an allocation check per opcode. A review round of four reviewers, each finding checked by an adversarial verifier, found no defect against this note; a follow-up run closed the sixteen test gaps and contract details it found, and every hand mutant tried is now caught except a quadratic UTF-8 scan, which only a timing assertion would catch and §8.1's measurement covers instead. The roadmap's S6 entry and measurements item (RM:52, :43), its S8 entry (RM:54) and research/README.md record S6 the same day.

## S7 implemented (2026-10-15)

M0 step S7 (RM:53) built §1.2's S7 row in `:core`: `Device` (`device/`: the live view, the open frame, the offered sequence, the server tail, the confirmed state and masters, the fold, the statuses and the lifecycle, with the M0 callbacks' checks, results and charge amounts) and the SPI (`render/`: `RenderHost`, `ReadbackSink`, `RecordRenderer`, `DeviceKey`, the five records, `NoRenderHost` and `FakeRenderHost`). The test fixtures, which no jar ships, add an oracle-backed `RecordRenderer`, a model of OC's `checkInteger`, a recording call budget, a manual clock and executor and a device rig; the tests add an independently written reference model, `NaiveDevice`. The `@Callback` layer stays the root's, with the card (H5; 24:85). Where this note left a point open, S7 settled it as follows (gaps H1–H22 of the S7 plan and H23–H25 of its amendments); S8 and S10 build on these:

- **The SPI** (28:185-192, :205). `RenderHost` gains `stalled()`, a default method returning false, read at `present` for `"host render stalled"` (24:126): a liveness bit like `available()`, not host data, so 28:200 and 24:133 (a) stand (H1). `RecordRenderer` gains a default `release(DeviceKey)` beside `{ open, accept, pixels }` (H17). `FakeRenderHost` is main code in `core.render`, beside `NoRenderHost`, as 24:80 lists; the oracle renderer it is given in tests stays in the test fixtures, so no jar ships the oracle. It runs every record in order unless a test sets 28:155's optional skip of older epochs. No method of the SPI throws for calls that follow it, and for an open key `offer` accepts every record: it never throws and never drops one, because the device has already changed its state when it offers (28:89's order). A host therefore bounds what it holds (H24) only without changing results. `FakeRenderHost`'s `IllegalStateException` for a key that is not open is a test aid, stricter than the contract.
- **Readbacks** (28:194; 21:85, :87, :131). `onReadback` copies the pixels before it returns, and the host may reuse its array as soon as it returns. `Device` keeps 21's ring itself: at most three surface arrays (K's, one pending readback and one spare) under a leaf lock taken after the device monitor, never before it; at most one fold task queued per device; the newest readback wins, and its fold confirms the older frames (28:112). `readPixels` and the snapshot copy under the monitor, and K's array is never handed out (`RESET` carries a copy; H18). A readback for another key, a `null` or wrong-length array or a frame never presented counts as rejected; one of another epoch, at or below K, or not newer than the pending one counts as stale; `onReadback` never throws into the host for any input; an `Error` from the executor's `execute` is rethrown after the hand-off is undone, and with a direct executor (the tests') the fold runs inside `execute`, so what it throws propagates (H8). The copy costs one surface on the host's thread per readback (≤ 512 000 B at T3), which RM:43's render-thread measurement covers. The fold executor (21:91's pool in the root) must run every task it accepts: a refusal by throwing counts the readback as rejected and the next one schedules a fold again, a pool that silently discards an accepted task leaves the device's frames unconfirmed until it is released, and a caller-runs policy would run the fold on the host's thread.
- **Q-S7-1, pending frees** (H23; owner, 2026-10-15: "Count pending frees"). Both critics of the S7 plan found that an open frame plus a `createTexture`/`free` loop grew the masters, the host's textures and the deferred `TEX_FREE`s without bound. Until present appends them behind its `FRAME`, or the `"no renderer"` discard does, the open frame's deferred frees still count against `createTexture`'s caps; `reset` drops them. `createTexture`'s checks are, in order: `"invalid texture size"`, `"unsupported format"`, `"too many textures"`, `"not enough video memory"`, then **`"busy"` when live + deferred textures ≥ 256 or `vramUsed` + deferred bytes + w·h·bpp passes the VRAM cap**, then H2's queued check below; every check is uncharged and is repeated after the charge. O4 is unchanged: `free` of a live handle is never refused or `"busy"`, the handle is invalid at once and its VRAM is credited at once; only `createTexture` sees the pending frees. What now holds: the masters hold at most 256 textures and the VRAM cap in bytes, so 28:577 is true (the plan's H10, up to twice the cap, is gone); the host, which runs the offered records in order, holds at most 256 textures and the VRAM cap in bytes at whatever point of the offered sequence it has reached, since live + deferred textures stay within both caps at every offer (I12); and the queued resource bytes are at most the VRAM cap + 64 · 256, since up to 256 `TEX_FREE`s join the tail unchecked between two checked appends (the plan's H11). That corrects 28:140's "every `TEX_FREE` needs a create that was counted": the cap can be passed by 16 384 B. This `"busy"` can come with no frame in flight, so no `"done"` or `"dropped"` follows it and waiting never clears it; a program presents before it retries. 24:129's and 24:179's "`busy`" at the tail bound do not cover this case, and S10's harness Lua and M1's `opengpu.lua` must present on a create `"busy"` when no frame is in flight.
- **The queued check** (28:343, :344, :548, :559; H2). It applies only when the record will join a non-empty server tail. On an empty tail a resource record folds at once (O6) and is never queued, so a T2 create or whole-texture update of 1 024×1 024 (1 048 576 B, over 1 048 512 B) is not `"busy"` for ever, as 28:215 ("up to 1 MB at T2"), 28:140, 28:706 and 24:129 require.
- **Statuses** (28:98, :108-115; H3, H12, H22). `present`'s precedence is `"no renderer"`, then `"host render stalled"`, then `"busy"` at the third unconfirmed frame. `available()` is read at every present; `"no renderer"` discards the open frame and appends its deferred frees behind any frames still in the tail. Statuses go to a `FrameListener` (the root's `opengpu_frame`): one `"done"` per fold, `"dropped"` from `tick()` (once per server tick, on an injected clock) once per frame at ≥ 1 s, none for frames a reset discards. They are delivered outside the monitor, in the order they arose and never two at once, by whichever thread drains the queue, a fold's thread or `tick()`'s caller, so a listener must be thread-safe.
- **Charges** (28 §8.2, 28:44; H4, H14, H15). `Device` runs the order (checks, charge, effect), the amounts and `bitblt`'s two-phase state through a `CallBudget` (`consume`, `pause`) passed per call, and throws `:core`'s unchecked `LimitReached`, which the root's `@Callback` maps to OC's; the root's adapter only forwards to `Context`. That moves the application of the charges into `:core`, a change to 28:44's split. The budget is never called under the device monitor. The two-phase flag is per device, shared by create and update, and cleared by every charge but a first attempt's throw (on the plain path before `consume`, on the retry after its pause), by a failed check and by `reset`, so a reset between OC's direct attempt and its synchronized retry makes the retry throw `LimitReached` again, as a fresh first attempt. `readPixels` is charged after both its region and its cap check, so an over-cap read is uncharged.
- **Arguments and keys** (28:84, :692, :920; H6, H7). Coercion is OC's `checkInteger` (truncation, saturation, NaN raising OC's argument error); the fixtures model it and S10 repeats the key rows through real OC. `Device.getCaps()` returns 28:675-690's 15 keys; the root adds 24:125's four. `DeviceCaps.forTier` builds T2 and T3 only; a public constructor takes any surface of tier 1–3 with custom caps, for tests (H20).
- **The fold and the masters** (28:137, 21:129; H9, H25). O6 governs: resource records ahead of the tail's first `FRAME` fold under the monitor on whichever thread appends them, at most one call's charged copy (2 MB). A fold runs under the monitor too; its worst case, a fold of the whole queued cap at T3 (≈ 4 MB of copies, 0.5–2 ms [E]), makes a callback of the same device wait, a departure from 21:80's ≤ 0.2 ms monitored append that M0's measurements record.
- **Lifecycle** (28:146-155, :199; H13, H21). `release` kills the device: every callback but `getCaps` then throws `IllegalStateException("device released")`, checked before any check or charge and again after the charge. A key is opened again by a new device with a higher first epoch; M0's root should use fresh device ids. A freed slot joins the free queue at once; its reuse is safe because records and masters key by the full handle (O7).
- **Handles across devices** (28:539, :917; H19). Two devices' handles alias; S7's two-device test documents that nothing detects it, which is §11.2's S7 half of the foreign-handle case.
- **Dev-build integrity** (28:202; H16). CRC32s under Java assertions: each segment at submit, checked at present; each record's arrays at construction, checked by `FakeRenderHost` and the fold (and by S8's hosts).
- **VRAM against `GlLedger`** (28:568, :602; G10, 24:218). With a frame open, the host still holds the textures freed during it, so once the host has drained the device's queue and the server tail is empty, `GlLedger`'s texture total for the device equals `vramUsed` plus the open frame's deferred bytes (both in `DeviceSnapshot`), and `vramUsed` alone when the open frame, if any, has freed nothing. S8's G10 test compares that (S8: `G10Scenario`, "S8 implemented").
- **For S8** (H24). While the server tail is empty, the records offered to a host that is not executing them (a loading screen, a stalled render loop) are bounded only by the host. `GlRenderHost` must bound what it holds for a device that has not drained without changing results, for example by applying the resource records offered ahead of the next `FRAME` to its own copy of the textures (at most the VRAM cap, by H23), or report `stalled()`. `stalled()` is read only at `present` today; whether `createTexture` and `updateTexture` should then return `"host render stalled"` is S8's question for the owner (answered on 2026-10-16, Q-S8-1: eager host-side mirrors, and texture calls never return `"host render stalled"`; "S8 implemented").

The tests (1 580 in `:core:check`, 0 skipped) cover §11.2's S7 bullets (28:917's two-device case; 28:920's callback table, 133 rows with the saturated, fractional and NaN arguments, plus the `getCaps` check) and §11.4's (28:936-937): §2.2's two ordering examples (28:143-144) and §9.4's worked frame through `Device` with `FakeRenderHost` and the oracle, asserting the offered sequence and the server tail, the lifecycle cases, and 45 of the 46 goldens run through `Device` (`reset-then-frame` is left out, since a device's `RESET` only ever carries K's pixels). A model test compares `Device` with `NaiveDevice`, written from this note and a short brief without reading `Device`, after every operation of 500 random sequences (100 000 operations; 400 000 at `-Popengpu.model.cases=2000`), and checks I1–I12 and a replay of every offered record on the oracle every 16 operations (64 on T2) and at the end; a concurrency test runs a worker per device, a render thread, a server thread that ticks and resets, and a two-thread fold pool at once; an allocation test shows that the live view's lookups allocate nothing and that a steady-state readback allocates no surface array. A review round of four reviewers, each finding checked by an adversarial verifier, found no defect in `Device` against this note; the one reading that `Device`, `NaiveDevice` and the tests shared and the review found wrong was this note's own `GlLedger` condition (28:568, :602), which this section corrects. The verifiers confirmed seventeen of its nineteen findings, twelve distinct points, and refuted two, and all twelve were closed the same day: one code fix (a fold executor that threw anything but `RejectedExecutionException` left the device unable to schedule a fold again, with nothing counted), the SPI and listener contracts, the `GlLedger` comparison and H23's `"busy"` above, test timeouts (`:core`'s test tasks run each `@Timeout` on its own thread, so a lock-order regression fails a test instead of hanging the build), an assertion that could not fail, and tests for the charges running outside the device monitor, a release landing between a charge and its effect, `readPixels` copying under the monitor, a snapshot kept across later folds and the ring's allocation. Every mutant that survived the review is now caught, except those the reviewers judged equivalent, and the thread-dependent ones are caught with the JVM pinned to two CPUs too. On two CPUs, which S7 took for CI's count (S8's first CI run reports 4 Gradle worker leases, Gradle's default of one per processor), one status test had failed by spinning on `"busy"`; it now waits for a fold. 28:140, :343, :568 and :602 point here. The roadmap's S7 entry and its 2D-path, hosts and measurements items (RM:53, :35, :36, :43), 24:80, :129 and :179 and research/README.md record S7 the same day.

## S8 implemented (2026-10-16)

M0 step S8 (RM:54) built §1.2's S8 row (28:45) from §5's host notes, §6.6–6.7 and §7.5. `:gl` holds the render core in `gl` (`GlBackend`, one per GL context, with the pass's bracket, the programs, VAOs, stream VBO, atlas, palette, staging and the readback waits; `DeviceGl`, one per device, with its surface, present texture, FBOs, textures, ops, `RESET` and expand; `PassState`, `PixelStore`, `Program`, `Shaders`, `OpQuads`, `QuadBatch`, `QuadMode`, `GlyphAtlas`, `GlTexture`, `GlFormats`, `ReadbackRing`, `ReadbackFinish`, `QueryPolicy`, `GpuTimer`, `UploadProbe` and `GlLedger`) and the host core in `gl.host` (`GlHostCore` with `HostDevice`, `Mirror`, `Pending`, `PassBudget`, `HostOptions` and `DeviceStats`, over the `HostBackend` and `DeviceBackend` seams). `:gl-testkit`'s test fixtures add `GlTestHost`, `GlRecordRenderer`, the hygiene assertion (`GlHygiene`), `GlReads`, `GlForeignWork` and the scenarios, 17 in `Scenarios.ALL` and two regression scenarios beside them, run on the compatibility leg (`glCompatTest`) and on core 3.3 under GLSM 2.2.8 and 2.2.28 (`glsmFloorTest`, `glsmPackTest`); two more legs on the same GLSM releases, `glsmReleaseConfigTest` and `glsmPackReleaseConfigTest`, run `GlsmReleaseConfigTest`'s three tests of H63's check under Angelica's release configuration. `:core` adds font 0's build-time converter (source set `fontgen`), the table it writes and its reader `GlyphTables`, and in its test fixtures `ShadowOracle` (one tier-E oracle per opening, fed at offer time) and `TeeRenderHost` (a host under test with the shadow beside it, which checks every readback the host delivers). No jar ships the converter or the test fixtures. The root's `GlRenderHost`, the card and `opengpu_frame` stay with later steps (24:85). Where this note left a point open, S8 settled it as follows (gaps H26–H62 of the S8 plan and H63–H72 of its amendments, with what the drivers and the review showed); S9 and S10 build on these:

- **The build** (H26, H51, H52, H60). `:gl` depends on `:core` only in M0 (H52) and on LWJGL 2.9.4 compile-only; the root shades it from S8 on, so `jarTest` covers it from its first commit and nothing loads it on a server (H51). Both shipped jars hold 137 entries, among them `font0-6x13.glyphs`, `NOTICE` and `LICENSE`, and no class of `:gl-testkit`, of `:core`'s test fixtures or of `fontgen`, whose package `jarTest` now bans as well; `core.render.FakeRenderHost` stays the only "Fake" class, main code by design (28:205). 28 governs RM:58's order: S8 built all four ops on both formats (H26). `ImageCompare` is M1's (H60): tier E is byte equality.
- **The GL host and its pass** (21:85-88; 24:173; H29, H32, H46, H47, H57, H64, H67, H70). `GlHostCore` makes no GL call: it drives a `HostBackend`, so its logic runs without a context against the oracle, and `GlTestHost` wraps it as the root's `GlRenderHost` will (H29). A pass with work runs one bracket in a fixed order: `PassState.enter`, the unpack store forced, first-time creation (programs, VAOs, the stream VBO, the atlas, the palette); step (a), the fetch; step (d), the deletes of released openings, moved ahead of 21's place so a key released and opened again before a pass never has two openings' rows (H46); step (b), the opens; the budgeted units (frames, resource records, resets); step (c), expands and issues, with the pack store forced once; then the stores and `PassState.exit`. `GlRecordRenderer`, `GlTestHost`'s reads and `close` use the same bracket, never a bare context, and an idle pass makes no backend call. `PassBudget`'s clock starts at the first unit, so in M0 the fetch (with its `glFinish`), the deletes and the opens run outside `renderBudgetMs` (28:599; G13a counts deletes and creations in M1). The heartbeat is set at construction and at the start of every pass, idle and failed ones included, and `stalled()` is true once it is older than 2 s (H32). The first pass with work fixes the GL thread, and later passes, `close` and the present accessors assert it (H57; GLSM's main thread under Angelica). Sinks run after the pass, outside the bracket and every host lock (H67). `GL_RASTERIZER_DISCARD` is read with `glIsEnabled` (C14), forced off for the pass and enabled again at exit in every build (H70, with C15, which the plan's call list had missed). Offers to a key that is not open and second opens are counted (`contractViolations()`) and dropped, and `GlTestHost.close()` fails on any (H47); an opening with a side over `GL_MAX_TEXTURE_SIZE`, read at the first pass, is refused the same way at its first turn (H64, replacing H61's fixed 1 024).
- **The seams** (the S8 plan's §2.2 with amendment C). `HostBackend` (`beginPass`, `endPass`, `maxSurfaceSide`, `open`, `staging`, `close`) and `DeviceBackend` (the texture calls, `resetSurface`, `runFrame`, `expand`, `readbackSlotFree`, `issueReadback`, `fetchReadbacks`, `readbacksInFlight`, `readNow`, `destroy`) were fixed before the first unit; `GlBackend` and `DeviceGl` implement them, and so does the tests' `OracleBackend`. The host owns the pass counter. `GlBackend` takes a `QueryPolicy`: `DRIVER` in production, and in tests `NEVER_READY`, under which only the forced fetch completes, so the ring's two-in-flight, owed-third and forced-fetch paths run on real GL. `ReadbackOut.accept` carries no "query available" flag, so `GlBackend` keeps per-device totals of fetches with the query available and forced. The plan's `presentTexture(displayId)` is `GlHostCore.presentDevice(displayId)` or `deviceBackend(key)` with `GlBackend.presentTexture(DeviceBackend)`. `HostOptions`' defaults: mirrors on, a stall after 2 s, `Expand.BOUND_ONLY`, and the forced fetch after 4 passes (1–16).
- **The render core and the two quad forms** (§5.1, §5.7, §6.6, §7.5; H27, H33, H39–H42, H53, H58, H59, H68, H69). Every op is integer-aligned quads over the rects §5.7 clips to on the CPU (28:412-413), drawn into the R8UI or R16UI surface. Both forms are built, `EXPANDED` (each quad's 20-byte record written six times, `glDrawArrays`) and `INSTANCED` (written once, `glDrawArraysInstanced`); every scenario runs both, and `EXPANDED` is the default until G16's measurement and M1's choice (H39; 28:415). Values reach the shaders as integer vertex attributes (`glVertexAttribIPointer`, C82) in both forms, never through `glVertexAttrib*` or `glUniform1ui` (A16) (H40; 28:414). The stream VBO's store is always 64 KiB, specified whole at every flush, so its ledger row is exact: 546 expanded or 3 276 instanced quads per batch (H69). Inside the push the pass also forces `glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)` (C99, new) and disables `GL_CLIP_DISTANCE0`–`7` (C16), which 20 §1's list missed (H41). Unit 0 serves uploads, `BLIT` sources, the atlas and the surface, unit 1 the palette in expand, and the pass saves both with their samplers (H53). Validation bounds every value, so no shader writes a value GL would have to convert (H58). `TEXT` finds each glyph by `GlyphTable.indexOf`'s binary search, at most about 52 500 cells per frame at T3's fill cap, inside RM:43's render-thread measurement (H59). The atlas is one per host, that is per GL context, made at the first pass with a host ledger row (`glyph-atlas`, label `font0`): 1 020 × 325 `GL_R8` texels (331 500 B, 170 cells a row) for font 0, 1 020 × 26 for the synthetic G (H33; 28:663). S8 built the expand into the RGBA8 present texture that 28:203 names and 28:45 did not list, and checks it at tier E on every leg for all 256 indices and all 65 536 rgb565 values (H27); no driver needed the +0.25/255 bias that H42 kept in reserve (Intel, NVIDIA, llvmpipe and Mesa's d3d12 driver under WSL). Under `Expand.BOUND_ONLY` a device's expand stays owed until a display is bound to it, so nothing is lost (H68, a proposal for the root's step).
- **The readback ring** (24:197; 20:127-138; 21:87; H35–H38, H54, SP-4). `ReadbackRing` gives each device three slots, each a pack PBO of W·H·bpp and a `GL_TIMESTAMP` query, at most two in flight. An issue copies the surface with `glGetTexImage(…, 0L)` (C91) and stamps the query; a fetch takes slots in issue order once the query reports available or the forced-fetch count has passed, and `glGetBufferSubData` (C92) then waits, with the driver waits below. A frame's readback that finds both slots busy stays owed until the next fetch, and a newer frame replaces it (H36); a `RESET` run after a `FRAME` cancels that frame's owed readback (H37); an offered `RESET` drops the device's queued records, mirrors and pending state, all of an older epoch (H38; 28:155). `GlRecordRenderer.pixels` reads through the same calls, synchronously, in a bracket (`readNow`: `glGetTexImage` into slot 0, then `glGetBufferSubData`; H54). A device holds its surface (W·H·bpp), its present texture (4·W·H) and three PBOs (W·H·bpp each), with two FBOs and three queries: 512 000 B at T2 and 3 072 000 B at T3 (H35). The review found the third PBO spare (SP-4, a nit, not changed): `issue` takes the lowest free slot while fewer than two are in flight and the synchronous fetch frees a slot at once, so only slots 0 and 1 ever hold a readback, and slot 2 costs W·H·bpp per device (512 000 B at T3, a sixth of the device's bytes) and one read at creation. 20:132's third PBO matters only for a mapped or asynchronous fetch, which M0 has not; keeping three, as 24:197 and 20:132 decided, or dropping to two is the owner's call, which M1 can make with a mapped-fetch path.
- **Q-S8-1, how the GL host bounds what it holds** (H24, 28:1084; H31, H56; owner, 2026-10-16: "Eager mirrors (Recommended)"). The option the owner chose read: "The host keeps a CPU copy of each device's textures, folds updates into it and uploads only what changed. Hard bound per device whatever the stall (about 8.5 MiB of heap at T3, worst case), same results everywhere, no spec change. It also costs something in normal play: heap up to the device's VRAM cap for its whole life (1 MiB at T2, 4 MiB at T3), and a partial update's rows copied on the OC worker. Whole-texture updates are held by reference (copy-on-write), so streaming video costs no copy." `GlHostCore` keeps a `Mirror` per texture: a whole-texture `TEX_UPDATE` adopts the record's payload array by reference, which is safe because a payload is never written again (28:562), and a partial one first makes the mirror its own (zeros for a fresh texture, a clone of an adopted array) and then copies the payload's rows in; the render thread uploads only what changed, staging an adopted or all-zero mirror outside the device's lock and an owned mirror's dirty box under it. Per device, whatever the stall, the mirrors hold at most the VRAM cap and the queue at most the server-tail bound plus two frames (`vramBytes` + 64 · `maxTextures` + `maxUnconfirmedFrames` · `frameBytes`, 4 734 976 B at T3), so about 8.5 MiB of heap at T3, beside the GPU textures (at most the VRAM cap) the host holds anyway; the plan's "≈ 12.5 MiB at T3" had added those. `stalled()` stays a check at `present` only: texture calls never return `"host render stalled"`, and neither `:core` nor this note's results change. `HostBoundTest` at T3: 2 000 whole 1 024² rgb565 updates with the host never passing take 392 ms and leave 2 097 152 B of mirror, 2 000 adoptions, no clone, no queued record and one GPU create (with mirrors off the same updates queue 2 001 records); two frames in flight with creates, updates and frees behind them queue 4 202 284 B against the 4 734 976 B bound; a churn of creates gets 256 through and 44 `"busy"`. On GL, `H24BoundScenario` offers 300 whole 1 MiB updates at T2 with no pass: with mirrors on nothing is queued, one mirror holds the last payload and one upload runs at the first pass; with mirrors off 301 records queue and 301 uploads run; the readbacks are identical. The cost in normal play is the option's: heap up to the VRAM cap for a device's life, and a partial update's rows copied on the OC worker under the device monitor, in single player on top of the server's masters (H25). The render thread holds a device's lock for at most one record's apply, which with copy-on-write is one texture's clone plus one payload's rows, under 4 MiB at T3, or one copy of an owned mirror's dirty box (≤ 2 MiB). The staging buffer is 2 MiB from construction (HO-4), so nothing is allocated under the lock; only `RESET`'s pixels or a `readNow` of an untiered test surface, both staged outside every lock, can grow it. An offer under the `Device` monitor can wait that long, a bounded departure from 28:188's "never blocks" (H56): about 1 ms [E] at worst, from the review's JDK 8 microbenchmark (a 2 MiB heap clone took 427–533 µs), which replaces amendment D's 0.2 ms [E] for one 2 MiB copy. The hold itself is not measured; RM:43 measures it with H25.
- **Q-S8-2, AMD** (H55; 28:938; owner, 2026-10-16: "No, record it later (Recommended)"). The option read: "S8 is done on CI's llvmpipe plus your Intel and NVIDIA, which between them cover all three of GLSM's DSA paths. You run :gl-testkit:check and glBench on the AMD machine whenever you like, at the latest with RM:43's measurements, and I record the result. The code path has no vendor branch (24:187)." S8 then added two driver switches, the fetch `glFinish` by `GL_VENDOR` and the copy wait by `GL_RENDERER` (below); AMD gets the `glFinish` and not the copy wait, and joins the drivers verified clean only after the re-verification recipe below passes on it.
- **Intel's Windows driver: two readback bugs and a crash** (20:138; 24:197). On Intel(R) Graphics with driver 32.0.101.8860, GL-legal sequences went wrong three ways, a different mechanism from llvmpipe's below. (1) A PBO the CPU has never read can return the bytes its memory held before (zeros, or another device's earlier readback) instead of waiting for the copy, on every leg, under both query policies and in both formats, for 0.1 % to 28 % of fresh devices' first readbacks, the most with 16 devices opened in one pass. `ReadbackRing.create` therefore reads each new PBO once with `glGetBufferSubData`, on every driver: no stale readback in 52 960 with it, at a cost of at most about 0.4 ms per device opened; a copy at creation without the read did not help. `GlFirstReadbackTest` (1 024 devices) is its regression test and also runs every case under `ReadbackFinish.NEVER`, because (2)'s `glFinish` hides this defect. (2) `glGetBufferSubData` can return a PBO's earlier bytes, or a partly landed copy, when any texture's storage was re-specified (`glTexImage2D` on an existing name) between the copy, in an earlier pass, and the fetch, whether or not the query reported available. GLSM's `glDeleteTextures` defers the delete and re-specifies the texture to 1 × 1 `GL_R8` (Angelica 2.2.8's `GLStateManager.java:2201-2209`, `:2248`), so in game, where every mod's deletes go through GLSM, every Intel user would get wrong confirmed pixels: 80–139 of 404 readbacks were stale with OpenGPU's own frees under GLSM, and 68–197 of 404 with foreign deletes. Waiting on `GL_QUERY_RESULT`, a `glGetError`, the `GL_DYNAMIC_READ` and `GL_STATIC_READ` hints, orphaning before each copy and mapping the buffer all failed; only a `glFinish` before the read works. `GlBackend.finishOnce` runs one `glFinish` (C100, new) per pass before the first fetch reads a PBO, armed again by any later issue (a `glFinish` per device had cost about 37 µs per device per pass). On an idle GPU it adds 0.02 to 0.2 ms per fetching pass (`glBench` on Intel, T2 back to back: 427 500 and 432 300 ns against 324 800 and 328 200 ns without); in game it waits for all queued GL work, the game's included, so a GPU-bound client loses its CPU–GPU overlap (not measured). `ForeignTexturesScenario` (4 devices, 100 passes, four textures deleted the way GLSM does between passes, both query policies, 320 × 200 index8 and 80 × 25 rgb565) is its regression test on all three legs: without the `glFinish`, up to 139 stale per case (one case of twelve, on 2.2.8, had none) and 149–335 per leg, so every leg failed. (3) `glGetTexImage(GL_RED_INTEGER)` into a PBO under a hostile pack store (alignment 8, row length 7, skips, swap bytes and LSB first) kills the JVM with an integer divide by zero in `igxelpgicd64.dll`; OpenGPU forces the pack store before every `glGetTexImage`, `readNow`'s included, so production is safe, and the plan's "`PACK_*` not forced" mutant is a JVM crash on Intel. (1) and (2) contradict 20:138's "fetching buffer data without `MAP_UNSYNCHRONIZED` waits for pending writes" on this driver; with these waits correctness still never depends on the query.
- **Q-S8-3, which drivers get the fetch `glFinish`** (owner, 2026-10-16). Asked with (2)'s facts, NVIDIA's result and the cost (once per fetching pass, 0.02 to 0.2 ms on an idle GPU, the lost overlap of a GPU-bound client), with the options "All but verified (Recommended)", "Every driver" and "Intel only", the owner answered: "Let's consider whether we might be opening a potential complication where we'd have to reverify every driver for every new driver version, even the previously verified ones. If you think the likelihood of that being needed is minimal then let's go with your recommended option; otherwise, let's go for the "every driver gets a glFinish" one." The main session judged that likelihood minimal, because the per-milestone GPU runs re-verify NVIDIA anyway (`AUTO` turns the `glFinish` off there), and took "All but verified"; it told the owner only that the recommended option was applied, so the judgement is the main session's. As built, `ReadbackFinish` is `AUTO` (production), `ALWAYS` or `NEVER`; under `AUTO` the `glFinish` runs unless `GL_VENDOR` (C02, read once per backend at its first pass) starts with "NVIDIA", so it stays on for Intel, AMD ("ATI Technologies Inc.", "AMD"), Mesa, Microsoft's d3d12, VMware and an empty or missing vendor. `verifiedClean` names a vendor, not a driver version, so a later NVIDIA driver is trusted until a GPU run re-verifies it. Every backend logs `[opengpu-gl] readback glFinish on|off (<policy>, GL_VENDOR <vendor>)` in every build. (1)'s create-time read stays on for every driver whatever the policy. `-Popengpu.gl.readbackFinish=auto|always|never` reaches every `:gl-testkit` test task (`auto` by default), and a test run without the property, an IDE's for example, fails by design; `glBench` builds its backend at `AUTO`; `GlReadbackFinishTest` counts the `glFinish` calls per policy on the driver.
- **Re-verifying a driver, and NVIDIA's result.** `ReadbackFinish`'s Javadoc holds the recipe: "To verify a new driver, run, on it, `./gradlew :gl-testkit:glCompatTest --tests *ForeignTextures* --tests *FirstReadback* :gl-testkit:glsmFloorTest --tests *ForeignTextures* -Popengpu.gl.readbackFinish=never`; with no failure, add its `GL_VENDOR` to `verifiedClean`." NVIDIA (GeForce RTX 5060, driver 617.42) is verified clean for (2): with the fetch `glFinish` removed, `ForeignTextures` on all three legs gave no failure in 12 cases, 2 759 readbacks (the question put to the owner said "about 2 900"), while Intel, in a separate run of the same mutant on the compatibility and 2.2.8 legs, failed 118 and 154 of those legs' 1 955 readbacks. NVIDIA is not verified for (1): its runs without the create-time read passed, but with the fetch `glFinish` on, which hides that defect, so the read stays on everywhere. In the final runs the recipe passes on NVIDIA and fails on Intel as expected (62 stale readbacks in `GlForeignTexturesTest`, 140 in `GlsmForeignTexturesTest`); it passes on llvmpipe only because the copy wait below stays on.
- **llvmpipe: a Mesa bug, and the copy wait** (decided by the main session). On CI-parity llvmpipe (Mesa 26.0.8 under WSL2), `ConcurrencyScenario` failed on every leg in about 4 runs of 6 with a readback equal to the previous frame's surface. OpenGPU's GL is legal there (one context, no unsynchronized or persistent map, and a `glFinish`, which cannot reorder commands, removes every failure); the cause is Mesa's. llvmpipe copies a texture into a PBO on the CPU at `glGetTexImage` (`texture_transfer_modes` 0, `lp_screen.c:287` in 26.0.8), through a map for reading that waits only for a scene it finds writing the texture, and `lp_setup_is_resource_referenced` returns the first scene, in slot order, that names the texture at all instead of combining the scenes' answers (the early return at `lp_setup.c:1027-1028` in 26.0.8, `:968-969` in CI's 25.2.8, read but not run, and `:1034-1035` on main as fetched in October 2026; older than August 2023). A scene in a lower slot that only samples the surface, or merely has it bound to a sampler unit, hides the scene in a higher slot still drawing the frame, so the copy takes the surface from before the frame. Scenes rasterize in order, so the expand's own sampling sees the frame; only CPU maps read stale data. `driver-checks/lp_stale_gettex.c`, a standalone C program with no OpenGPU code, shows it: on 26.0.8, 185–190 of 200 readbacks wholly stale by default, and 0 when the last draw before the copy samples another texture (`c`) or with a `glFinish` right before the copy (`f`); `driver-checks/lp_order_check.c` puts the same two scenes in either slot order, with a control: with the scene that samples the surface in the slot below the surface's fill (`lowread`) 199 of 200 readbacks are stale, in the reverse order (`highread`) 0, and with a lower scene that samples another texture instead (`noread`) 0. Each builds with `gcc -O1 -o <name> <name>.c /usr/lib/x86_64-linux-gnu/libEGL.so.1` and runs with `EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./lp_stale_gettex [c|f] [iterations]` or `./lp_order_check lowread|highread|noread [iterations]` (200 iterations by default); a Mesa without the bug prints 0 wholly and 0 partly stale readbacks in every mode, and `lp_stale_gettex` then exits 0 (1 when any readback was stale). OpenGPU's fix, `GlBackend.finishBeforeCopy`: where `GL_RENDERER` (C02) contains "llvmpipe" (`finishesBeforeCopy`, which includes zink on lavapipe, unverified, as a precaution), a readback's copy, issue and `readNow` alike, runs one `glFinish` (C100) first if a device drew into its surface since the backend's last `glFinish`; the first pass logs `[opengpu-gl] readback copy glFinish on (GL_RENDERER <renderer>)`. It is separate from Q-S8-3's fetch policy, which it leaves as it was, and no property turns it off: before the rule may leave out a later Mesa (when CI's floor moves), `lp_stale_gettex.c` must show 0 stale on it. It costs at most one `glFinish` per pass that drew and then copies (`ConcurrencyScenario` on the compatibility leg about 750 → 950 ms on llvmpipe) and nothing on any other driver; Mesa's d3d12 driver under WSL was not affected. `CopyWaitScenario` is its regression test (`GlCopyWaitTest` on the compatibility leg, `GlsmCopyWaitTest` on both GLSM legs), with the fetch `glFinish` off where the copy waits, so that it cannot hide the race: without the wait it failed in every valid run on llvmpipe, 15 on all threads and 7 on two pinned cores (18–55 stale readbacks per leg), and again in all 7 runs of the close-out run's final tables (5 on all threads, 2 on two cores), and with it in none. A second phase on the same host and its two devices copies in passes that draw nothing: two passes that draw and copy put both rings in step, then ten cycles of X (both devices draw, neither copies), Y (no frame; the fetch frees a slot and the copies take X's frames) and Z (both draw and copy). The oracle checks every readback, and where the copy waits the phase must count exactly 22 copy finishes (2 + 2 · 10, one in each pass that copies a surface drawn since the last `glFinish`, each Y's included). That count pins the rule that only a real `glFinish` ends the wait: a mutant whose `finishOnce` cleared the drawn flag at a fetch that ran no `glFinish` failed it on every leg in every run (10 of 10 runs on all threads and 6 of 6 on two cores; 12 copy finishes instead of 22, no Y waiting, and 40 instead of 41 in the first phase). The oracle alone caught it in 5 of 30 leg runs on all threads and 11 of 18 on two cores (0.7 % and 9 % of Y's copies stale), because on 26.0.8 the bound device's expand in X binds its surface for sampling while the first scene naming that surface is the one drawing its frame, and llvmpipe then finishes every scene itself (`lp_state_sampler.c:164`, `lp_flush.c:134-145`), so Y's copies mostly come out right without the wait. The bug is not yet reported to Mesa; filing it is the owner's call.
- **HY-1 and D01: H63's checks as built** (20:240-242; 25:447-448). Under Angelica's release default `GLStateManager.glGetError()` answers 0: it returns 0 under `noErrorChecks` (Angelica 2.2.8's `GLStateManager.java:5216-5220`), which Angelica's client sets when `disableErrorChecks`, true by default, is on in an obfuscated client without LWJGL debug (`MixinInitGLStateManager.java:47`, `AngelicaConfig.java:433-436`). So the review found (HY-1, major) that `PassState`'s R-3 drain and H63's exit check would never have fired in a GTNH client; the check worked only in a dev `runClient`, which is not obfuscated. `org.lwjgl.opengl.Util.checkGLError` is no way out: from Angelica 2.2.16 `GLSMRedirector` maps it to `GLStateManager.checkGLError`, which reads GLSM's `glGetError` (none in 2.2.8–2.2.15, as the fix round read the releases; only 2.2.8 was re-read for this note), and `glsmPackTest` caught the first fix, which used it. The main session chose D01 instead: `PassState` reads the driver's error state through a `static final MethodHandle` on `GL11.glGetError`, looked up at run time with `MethodHandles.publicLookup()` and invoked, never called as an instruction; the redirector rewrites call instructions, `invokedynamic` handles and `NEW`/`CHECKCAST` types, not a run-time lookup, and its core exclusions leave LWJGL's own classes alone (`GLSMRedirector.java:177-182` at 2.2.8). The call set gains a row class D, driver-direct, with D01 `GL11.glGetError()I`; C03 is no longer called, so a `GL11.glGetError` call in `:gl` is reported, and `CallSetTest` and `GlsmRedirectionTest` allow no other LWJGL class literal. D01 is hidden from 25 §4's linkage test by design: an exception to R-5's rule against GL calls through `MethodHandle` (25:447), whose reason, that every GL call stays visible to that test (25:448), gives way for this one row. A release build drains (at most 16 errors) and checks on its first pass with work only, a dev build on every pass, so vanilla release builds lose a per-pass `glGetError` too; an error left by the pass throws `"GL error 0x%04X after OpenGPU's pass (20:240)"`. The release-config legs (`-Dopengpu.glsm.noErrorChecks=true`, assertions off) test it: GLSM answers 0 while the driver holds `GL_INVALID_ENUM`; an invalid call inside the first pass makes `endPass` throw; the first pass drains another mod's earlier error without throwing, and a later pass drains nothing and leaves its own error in the driver. The rest of H63: a program that fails to link throws in `beginPass`, an incomplete FBO in the device's `open`, and `GlHostCore` fails closed on any exception from the backend: it logs once, drops every opening's state, tries `backend.close()`, answers `available()` false from then on (so `present` answers `"no renderer"`) and drops offers uncounted, and `deviceBackend` and `presentDevice` answer null, so the root's display shows 20:242's "no graphics" bitmap (HO-2). D01's trade-offs are the owner's to weigh: a future GLSM render backend that is not LWJGL 2 GL would need D01 guarded; lwjgl3ify (GTNH's Java 17+ clients) is not verified with it, though its `GL11.glGetError` is public and static; the alternative is to record that H63's `glGetError` check is inert under Angelica's release configuration and keep only the link and FBO checks.
- **The call set** (25 §4; H44). `CallSetTest` checks 25:251-255's steps 1 and 2 (allowlist and deny-list) over `:gl`'s classes: 66 allowed (64 C rows, R01 and R02), 5 folded, 13 denied and 1 driver-direct. C99 `glPolygonMode` (H41) and C100 `glFinish` are new; C02 `glGetString` is now used, for `GL_VENDOR` and `GL_RENDERER`, and C15 `glEnable`, for H70. On both GLSM legs `GlsmRedirectionTest` checks that every call is redirected and links: 11 GL-calling classes redirected, 81 left unchanged, 15 enables and disables folded and one driver-direct lookup. S9 adds the per-jar linkage (2.2.8, 2.2.21, 2.2.28 and the latest) and the `unmappedGL=FAIL` runs; D01 stays outside them.
- **H72, corrected** (HY-2). The plan's amendments had GLSM emulate `GL_CLIP_DISTANCEi` as clip planes without the driver, so H41's force would matter on the compatibility leg only. The review found that GLSM 2.2.8 and 2.2.28 cache `GL_CLIP_PLANEi` (the same enums) and also send each enable and disable to the driver, so the force matters on every leg, and `GlHygiene`'s label now says so. The clip-distance mutant survives only because Intel does not clip on a `gl_ClipDistance` that the shader never writes.
- **Hygiene and the legs** (20:52-73; 23:59; H28, H30, H45, H62, H66). Every scenario starts from `GlHygiene`'s MC-like hostile state (a framebuffer, program, VAO, PBOs and textures of its own on units 0–3, with `GL_LINEAR` samplers on units 0–1; blend, logic op, cull, colour mask, depth, stencil, polygon offset, alpha test, a clip distance, polygon mode `LINE`, `GL_RASTERIZER_DISCARD`, and both pixel stores at values that would break tier E) and compares the state after every pump, on all three legs; the pass saves units 0–1, and the assertion checks units 0–3 (H45). On the GLSM legs a second start keeps GLSM's defaults and checks a GLSM-routed `glClear` after the pass. Generic attribute values are checked on the compatibility leg only (`GlScenarioTest` sets attributes 1–7 before every scenario and compares them after), because `glVertexAttrib*` bypasses GLSM's cache, and `CallSetTest` shows that `:gl` never calls it (H66; V1); on NVIDIA's compatibility profile attribute 3 aliases `gl_Color`, so the probe restores the colour it found. The render core runs on the two production legs, compatibility and core 3.3 under GLSM; `glCoreTest`, raw core without GLSM, keeps the smoke test, and the render-core tests carry `@RenderCoreTest`, which it excludes (H28). `GlsmLoader` owns `opengpu.gl.`, `gltestkit.host.` and `gltestkit.scenario.`, tests cross into them only through `:core` and JDK types, and every scenario, the 17 and the two regression scenarios, runs on all three legs, while `GlFirstReadbackTest`, `GlPassErrorCheckTest`, `GlReadbackCrossCheckTest` and `GlReadbackFinishTest` run on the compatibility leg only and `GlsmReleaseConfigTest` on the two release-config legs (H30); `GlsmExtension` and `GlsmLoader` stay in `src/glsmTest`, which S10 may change (H62).
- **Font 0** (28:631-632; 29 §9.1; H48–H50, H71). `:core`'s `generateFont0` (a cacheable `JavaExec` on the Java 8 launcher) runs the converter (`Bdf`, `BdfToGlyphTable`; own code, JDK only) on the vendored `core/src/fonts/misc-fixed/6x13.bdf`, which it accepts only with SHA-256 d00cf1cb…23f0, and adds the table to `:core`'s resources. The parser is strict BDF 2.1 (`STARTFONT 2.1` only, no unknown keyword, no `ENCODING -1`, every box inside the cell, no bit beyond a box, unique scalar encodings, U+FFFD present); `.gitattributes` marks `core/src/fonts/**` `-text`, so git never rewrites the file (H49). The table is `font0-6x13.glyphs` in `:core`'s `core/font` package, in `:core`'s jar and the mod's, read through the classpath by `GlyphTables.font0()` (H48): 70 407 B, SHA-256 deeabc5f93025ddf76d406423a840f6fbf475f4b2f3c608f6ce16cc402bfb27c, made of a 329-byte readable header (`OpenGPU glyph table 1`, the font id, the 6x13 cell, 4 121 glyphs, the missing glyph U+FFFD, the source's name, SHA-256, `$Id` and COPYRIGHT text byte for byte, and `licence public domain; credited in OpenGPU's NOTICE`), a 70 074-byte body and the body's CRC32, 0xFED76908. The body's SHA-256, 665917a7d7972c825d76b467641f5cab4f8ed9731be420eeae93ef96dd87afeb, is pinned in `Font0Test`; `cmdstream-checks/font0_table.py`, written from this note, 29 and the BDF format without the Java, prints the same digest and CRC, and 1 500 random 6×13 BDFs gave identical bodies from both. U+FFFD stays as a glyph and is also the missing glyph (H50); U+0000 stays in G although `TEXT` never draws it (`CONTROL_CHAR`), so `TextFont0Scenario` draws the other 4 120 glyphs and the 20 codepoints without one, opaque and with `TRANSPARENT_BG`, and `GlyphAtlasLayoutTest` covers U+0000's cell (H71). NOTICE has misc-fixed's section (Markus Kuhn and contributors, with the font's own statement). The jars pack `src/main/resources/NOTICE` and `LICENSE`, copies of the root files, which S8 had first updated only at the root (FO-1); `jarTest` now fails when a shipped copy differs from the root file, so a later NOTICE section (musl's, M1's fonts) goes into both.
- **G10 against `GlLedger`** (28:568, :602, :1083; 24:218; H34). `G10Scenario` runs through `Device` on `GlTestHost` with T2's and T3's caps, in both quad forms, every readback checked by a `ShadowOracle`. After each step the host is pumped until it owes the key nothing and the server tail is empty; then the device's `texture` rows hold `vramUsed` plus the open frame's deferred bytes, as many rows as live textures plus deferred frees, which the host's own count repeats, and the device's other rows follow H35's formula. The steps: textures of 1 × 1, 16 × 16 and, on T3, 1 024² (2 MiB); whole and partial updates; a free with no frame open; a free during an open frame, whose row stays until the present appends its `TEX_FREE`; a texture created, updated and freed before the host ran any of it, which never reaches the GPU; a create with a frame in flight; and, after the release and one pump, no row of the device (24:218). Host-wide rows (programs, VAOs, the stream VBO, the atlas, the palette) use `HOST_SESSION` and `HOST_DEVICE` −1, so "empty after `releaseSession`" means no row of that session, and `close()` empties the rest (H34). Rows go at call time, although GLSM defers the real delete (28:604).
- **Bench figures** (`:gl-testkit:glBench`, outside `check`; H43, H65). S8 built G13b's hook (`UploadProbe`), `GpuTimer` and the bench, with G16's quad-form row; it did not build the packed-RGBA8 readback, which RM:43's PBO measurement decides for M1 (28:482). Smoke figures for RM:43, not its measurement, on the compatibility leg (LWJGL 2.9.4, Java 8, assertions off, 20 warm-ups, median of 100), Intel(R) Graphics 32.0.101.8860 against NVIDIA RTX 5060 617.42: upload GPU time for a 1 024² `GL_R16UI` texture (2 MiB) 333 against 88 µs, for a 1 024² `GL_R8` one 364 against 45 µs, for a 640 × 400 `GL_R16UI` one 83 against 23 µs; readback fetch at T2 431 against 14 µs back to back and 542 against 47 µs at a 16 ms pace, at T3 1 101 against 47 µs and 679 against 83 µs, Intel's with the fetch `glFinish` and NVIDIA's without (an earlier run with the `glFinish` on every driver, whose figures the owner saw, had NVIDIA's at 37–183 µs); `runFrame` CPU time for a frame of a `CLEAR`, 2 000 `FILL_RECT`s, 200 16 × 16 `BLIT`s and 15 lines of 53 cells (T2) or 30 of 106 (T3): at T2 160 µs `EXPANDED` and 77 µs `INSTANCED` on Intel, 85 and 69 µs on NVIDIA, at T3 277 and 122 µs, 142 and 60 µs, so instancing saves 52–56 % of the CPU time on Intel and 19–58 % on NVIDIA (G16). Back to back, Intel's `GL_TIMESTAMP` query is almost never available within four passes, so nearly every readback there is forced; at a 16 ms pace it is available after one pass.
- **Open for later steps.** (`:gl:test`'s time on CI after the fix to `GlLedgerTest`'s two-thread test, open here until the second CI run, is answered below: its tests took 8.6 s together and the task about 10.4 s in the second run, the low case, and 20.0 s and about 22.7 s in the third, after which the owner chose on 2026-10-24 to re-scope its CI budget and the main session set it at 30 s.) AMD (Q-S8-2), and the recipe there before AMD may join `verifiedClean`. The Mesa report, then narrowing `finishesBeforeCopy` once CI's floor has a fixed Mesa on which `lp_stale_gettex.c` shows 0 stale. Two waits per fetching pass on llvmpipe: `AUTO` keeps the fetch `glFinish` on for `GL_VENDOR` Mesa although the copy wait alone suffices there (`readbackFinish=never` passes on llvmpipe); dropping it is within Q-S8-3, the owner's call. D01's trade-offs, for the owner. SP-4, three PBOs or two (the owner, M1). FO-1's single-source option, packing the root's NOTICE and LICENSE from `processResources`, not taken. Not verified: the bug on Mesa 25.2.8 itself (its source only; CI ran 25.2.8 with the copy wait on, which hides it), zink on lavapipe, lwjgl3ify with D01, NVIDIA's GLSM legs against the clip-distance mutant. Not measured: the fetch `glFinish` on a GPU-bound client and the lock hold, both RM:43's with H25. Test gaps: `deliver()` re-reading the sink holds by construction but no test kills its mutant deterministically; after an H63 failure `HostDevice.backend` stays referenced until `close()`; `CopyWaitScenario`'s exact copy-finish count would also catch a copy wait that runs more often than the rule says, but no such mutant was run. S6's `GlyphTable` still writes a bad codepoint in lower-case hex (`GlyphTable.java:44`).

The tests (2 096 in `check`, 0 failed or skipped, the same counts on Intel, NVIDIA and WSL's llvmpipe on all cores and on two, and on CI) are `:core:test` 1 635 (S7's 1 580 and S8's 55 in `ShadowOracleTest`, `Font0Test`, `GlyphTablesTest`, `BdfTest` and `BdfToGlyphTableTest`), `:gl:test` 326 (`HostLogicModelTest`'s 224 rows, 220 of them random sequences against the oracle with amendment E's properties, `GlHostCoreTest` 40, `HostBoundTest` 5, `CallSetTest` 7 and seven more classes), `:gl-testkit:test` 1, `glCompatTest` 28 (the 17 scenarios as `GlScenarioTest`, the smoke test's 4 and seven regression and cross-check tests), `glCoreTest` 4 (the smoke test), `glsmFloorTest` and `glsmPackTest` 30 each (the 17 scenarios, the spike's 6, `GlsmRedirectionTest`'s 4 and three regression tests), the two release-config legs 3 each, `:jarTest` 28 and the root's `test` 8; `glBench`'s one test runs outside `check`, and `-Popengpu.host.cases=2000` raises `:gl:test` to 2 306. The scenarios are Goldens, Generated, AllValues, Uploads, Reset, TextFont0, Expand, Hygiene, Readback, HostLifecycle, RoundRobin, DeviceGoldens, DeviceOrdering, DeviceLifecycle, G10, H24Bound and Concurrency, with ForeignTextures and CopyWait beside them. For 28:938, `GeneratedScenario` offers each generated case through `GlTestHost` and compares the delivered readback with the oracle byte for byte: 48 cases per surface and quad form on six surfaces (index8 8 × 6 and 17 × 13, rgb565 9 × 7 and 16 × 16, T2 and T3) on the compatibility leg and 16 on each GLSM leg, 576 and 192 cases per leg in both quad forms, plus P5's splits. It ran on all three legs, the compatibility leg and core 3.3 under GLSM 2.2.8 and 2.2.28, on llvmpipe (Mesa 26.0.8, Ubuntu's 26.0.8-1ubuntu0.3 with LLVM 21.1.8, under WSL2 with Xvfb and JDK 8u504: CI parity, not CI, which runs Mesa 25.2.8, 23:400; GLSM's `DSACore` path), on Intel 32.0.101.8860 (`DSAUnsupported`) and on NVIDIA 617.42 (`DSAARB`), so all three of GLSM's DSA paths, and with GLSM's DSA off on both GPUs; tier E held everywhere, and AMD waits for Q-S8-2. CI's first run of S8 (run 38899286849 of 08ca13b, 2026-10-16) then ran it, with the rest of `check`, on CI's own llvmpipe, the Mesa 25.2.8 that WSL's 26.0.8 stood in for: every step green and a "Tests" status of 2 096 passed, on `llvmpipe (LLVM 20.1.2, 256 bits)` with `4.5 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.24.04.4` on the compatibility leg and GLSM's `DSACore` path on both GLSM legs and the two release-config legs; every backend logged `readback copy glFinish on (GL_RENDERER llvmpipe (LLVM 20.1.2, 256 bits))`, every one under `AUTO` also `readback glFinish on (AUTO, GL_VENDOR Mesa)`, and `CopyWaitScenario`'s second phase counted its 22 copy finishes in each quad form on the compatibility leg and both GLSM legs. Against the plan's §4.8 budgets (on CI's llvmpipe `:gl:test` ≤ 20 s with the model test ≤ 8 s, each GL leg ≤ 90 s and the font tests ≤ 2 s; on the owner's GPUs each leg ≤ 30 s [E]), WSL's llvmpipe gave, alone on all cores and on two pinned cores, `glCompatTest` 7.8 and 8.4 s, `glsmFloorTest` 5.8 and 5.8 s, `glsmPackTest` 5.7 and 6.4 s, the release-config legs 0.9–1.2 s and `glCoreTest` under 1 s; the model test took 2.3–2.4 s and the font tests about 0.1 s together; `:gl:test` took 8.3 s on all cores (beside `:core:test`) and 9.1 s alone on two, but 26.1 s inside the two-core full build, where `:core:test` runs beside it on the same two cores, over its 20 s budget. The legs took 1.2–9.2 s on Intel and 1.3–7.1 s on NVIDIA. These times precede the close-out run's second `CopyWaitScenario` phase, which took the scenario's test on a leg, run alone on llvmpipe, from 150–270 ms to 256–317 ms. Amendment E checks the budgets against that first CI run (`ubuntu-24.04` on Azure; its log names no CPU count, but each of its Gradle builds reports "Using 4 worker leases.", Gradle's default of one per available processor, which the build does not override). Its test report, summing each task's test durations, gives `glCompatTest` 13.5 s, `glsmFloorTest` 9.2 s, `glsmPackTest` 5.2 s, the release-config legs 0.1 s each and `glCoreTest` 0.04 s, each far inside 90 s; the model test (`HostLogicModelTest`, 224 rows) 5.85 s, inside 8 s; and the font tests 0.15 s, inside 2 s (`Font0Test`, `GlyphTablesTest`, `BdfTest` and `BdfToGlyphTableTest`, the four classes WSL's figure counts; 0.26 s with `:gl`'s `GlyphAtlasLayoutTest`). `:gl:test`'s tests took 21.4 s together in its one test JVM, so the task took at least that, over its 20 s: 8.30 s of it was `GlLedgerTest.twoThreadsLeaveItEmpty()`, whose two threads each called `bytes()`, which scans every row, and `rows()`, which copies every row, after each of their 10 000 adds while the ledger grew to about 20 000 rows, so its cost was quadratic in the rows; `GlHostCoreTest` took 3.59 s and `HostBoundTest` 2.66 s. The workflow's "Run post-build checks" step, whose `build` runs every test task, took 61 s. CI's figures are sums of test durations inside the full build and WSL's leg and `:gl:test` figures task times, so they compare only roughly. Inside WSL's full builds, on all cores and on two, the legs took 12.8 and 9.3 s (`glCompatTest`), 6.3 and 5.3 s (`glsmFloorTest`) and 5.7 and 5.5 s (`glsmPackTest`), against the first CI run's 13.5, 9.2 and 5.2 s (the second's 6.9, 4.7 and 4.6 s, below); class against class, the first run took 2.0 to 2.5 times as long for the model test (5.85 s against 2.98 s, its suite time inside WSL's all-cores build, and 2.3–2.4 s alone), 2.3 to 2.4 times as long for the ledger test (8.30 s against 3.44–3.57 s alone on WSL's two pinned cores), and 0.15 s against about 0.1 s for the font tests, but the second took 1.2 to 1.5 times as long for the model test (3.45 s against the same WSL figures) and 1.3 to 1.6 times as long for the fixed ledger test (0.42 s against 0.260–0.321 s on WSL's two pinned cores, below), so one run's ratio is no CI-to-WSL factor. After 08ca13b the ledger test keeps its 10 000 rows per thread, the shared key, its bounded loops and its timeouts, but removes each row 1 024 iterations after adding it (the last 1 024 after the loop), adds and removes the shared key in every iteration, and reads `bytes()` every 2nd iteration and `rows()` every 16th, so the ledger holds at most about 2 050 rows and the cost is linear. Run alone it took 0.197–0.213 s on Windows (3.24–3.48 s before) and 0.260–0.321 s on WSL's two pinned cores (3.44–3.57 s before), and `:gl:test` took 5.5–5.6 s on Windows and, under WSL, 5.1–5.2 s on all cores and 6.3–6.4 s on two pinned cores, with 326 tests as before. Against nine `GlLedger` mutants (a method's lock removed, the wrong lock, split or check-then-act lock holds, a row kept at count 0) it kills each at least as often as the committed test did, on Windows, on two pinned Windows cores and on WSL's two pinned cores; `add-split`, an increment after the lock's release, stays the weakest (3 of 10 runs on two pinned Windows cores, 0 of 10 before). CI's second run of S8 (run 38922626582 of 2c4528e, 2026-10-16), the run of the follow-up commit that carries this fix, shows the fix's effect: every step green and a "Tests" status of 2 096 passed, on the same `llvmpipe (LLVM 20.1.2, 256 bits)` with `4.5 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.24.04.4`, with "Using 4 worker leases." in each of its five Gradle builds and `readback copy glFinish on` logged 205 times, as in the first run. Its `:core:test` did not run: Gradle's build cache restored it (`FROM-CACHE`, under the cache key the first run stored, every test's duration identical), so 1 635 of the 2 096 results and `:core:test`'s 14.5 s are the first run's [M]. `:gl:test`'s tests took 8.6 s together, against its 20 s, and the task about 10.4 s by the log's timestamps (23.3 s in the first run), the low case of three runs (the third run is recorded below). The ledger test took 0.42 s, against the first run's 8.30 s. The model test took 3.45 s, inside its 8 s (5.85 s in the first run), with `GlHostCoreTest` at 2.23 s and `HostBoundTest` at 1.83 s (3.59 and 2.66 s). The legs took 6.9 s (`glCompatTest`), 4.7 s (`glsmFloorTest`), 4.6 s (`glsmPackTest`), 0.1 s for each release-config leg and 0.03 s (`glCoreTest`), each far inside 90 s. The font tests took 0.15 s again, inside 2 s, and the "Run post-build checks" step 44 s against 61 s. Why `glCompatTest`, `glsmFloorTest`, `glsmPackTest` and the model test took 0.51 to 0.89 of their first-run sums (the release-config legs and `glCoreTest`, at 0.03 to 0.13 s, took 1.04, 1.14 and 0.65 of theirs) was not measured [I]: by the logs' timestamps [M], in the first run `glCompatTest` ran beside `:gl:test`, whose ledger test kept two threads busy for 8.30 s, and both ran beside `:core:test`, on a runner whose builds each report 4 worker leases (Gradle's default of one per available processor), and `glsmFloorTest` ran beside `:core:test`, while `glsmPackTest`, which overlapped `:core:test` for about 1 s, changed least (5.2 to 4.6 s); in the second run, whose `:core:test` did not run, `glCompatTest` was the only GL leg to overlap another test task, `:gl:test`, for about 6 s, and `:gl:test` otherwise overlapped only the root's `:jarTest` and `test` and `:gl-testkit:test`, for under 2 s each. Neither log names the runner's processors (both runs had the `ubuntu-24.04` image 20261012.334.1 in Azure's centralus), so a faster machine in the second run is not ruled out [I]. CI's third run of S8 (run 40073138862 of 0761bc4, 2026-10-24), the run of the commit that recorded the second, was green on its second attempt, after its first failed in "Setup the workspace": GitHub had evicted the Gradle User Home cache, which no run had used since 2026-10-16 ("Gradle User Home cache not found. Will initialize empty."), and the configuration `:patchedMinecraft` could not resolve Forge's `scala-parser-combinators_2.11` 1.0.1, `scala-swing_2.11` 1.0.1 and `scala-xml_2.11` 1.0.2, because the connection to GTNH's mirror `gregtech.overminddl1.com`, the only repository that has them, timed out (the attempt's log reports them missing at the other three repositories it tried, Forge's maven, Maven Central and GTNH's nexus, and a check of one of their `.pom` files on 2026-10-24 got 404 from Maven Central and GTNH's nexus and, by then, 200 from the mirror, so neither can stand in for it; 23:24). The owner re-ran it: every step green and a "Tests" status of 2 096 passed, on the same `llvmpipe (LLVM 20.1.2, 256 bits)` with `4.5 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.24.04.4` (the same `ubuntu-24.04` image 20261012.334.1, in Azure's westus3), with "Using 4 worker leases." in each of its five Gradle builds and `readback copy glFinish on` logged 205 times. It uploaded exactly five jars, `opengpu-0761bc4.jar` and its `-api`, `-dev`, `-dev-preshadow` and `-sources` jars, "-dirty" appears nowhere in its log, and the runServer step found `:jar` UP-TO-DATE, so 0761bc4's `.gitignore` entries hold. The re-run saved no cache ("Cache hit occurred on the cache key …, not saving cache."), so the stored entries are the first attempt's, which lack the three Scala libraries, and the next push fetches those libraries from the mirror once more and then stores a full entry [D]. Only buildSrc's tasks came from the build cache, so `:core:test` ran, and its tests took 19.3 s together (14.5 s in the first run). `:gl:test`'s tests took 20.02 s together, just over the plan's 20 s, and the task about 22.7 s by the log's timestamps, during which it ran beside `:core:test` for about 20 s and beside `glCompatTest` for about 14 s [M]. In it the race test, `GlHostCoreTest.workersTheRenderThreadAServerThreadAndAFoldPoolRaceWithoutChangingResults`, took 6.43 s (2.31 and 1.16 s in the first two runs), within `GlHostCoreTest`'s 8.40 s (3.59 and 2.23 s); `HostBoundTest` 3.43 s (2.66 and 1.83 s); and the ledger test 0.91 s, its two-thread test 0.88 s (8.30 and 0.42 s). The model test took 6.09 s, inside its 8 s (5.85 and 3.45 s); the legs 13.5 s (`glCompatTest`), 8.0 s (`glsmFloorTest`), 5.9 s (`glsmPackTest`), 0.14 and 0.15 s for the release-config legs and 0.05 s (`glCoreTest`), each far inside 90 s; the font tests 0.22 s, inside 2 s; and the "Run post-build checks" step 88 s. So the second run's 8.6 s was the low case, not the typical one: with `:core:test` running, the legs were back near the first run's sums (`glCompatTest` at 13.5 s again, `glsmFloorTest` and `glsmPackTest` at 0.86 and 1.14 of theirs) and `:gl:test` at 20.0 s with the fixed ledger test. That supports the contention reading above, which stays [I]: three runs, no controlled measurement. The race test paces its workers (a worker parks after a busy present until its device moves, and at each reset mark until the server has started the reset; the Javadoc above it, `GlHostCoreTest.java:1653-1667`), so under contention it waits more [I]. The main session laid out three options (make the race test cheaper, with a mutant check; re-scope the budget; record only), and the owner decided on 2026-10-24: "I think re-scoping the budget is the less intrusive option here, let's go with that." The main session then set `:gl:test`'s CI budget at 30 s, measured as before, as the sum of its test durations in CI's test report of the full build, where it runs beside `:core:test` and the first GL leg, `glCompatTest`, within the builds' 4 worker leases; the model test's 8 s, each GL leg's 90 s and the font tests' 2 s stay. The plan set its 20 s before CI's runs showed `:gl:test` running beside `:core:test` and `glCompatTest`; over the three runs the sum ranged from 8.6 to 21.4 s (21.4 s before the ledger fix, 8.6 and 20.0 s after it), and the whole "Run post-build checks" step stayed short, at 61, 44 and 88 s.

A review round in five dimensions (spec, host, hygiene, tests, font), each finding checked by a refuting verifier, made 20 findings: after verification one blocker, three major, four minor and eight nits stood, and four were refuted (SP-1, HY-3, TE-3, FO-3). The blocker (HO-1): a released opening kept its sink, and through it the whole released `Device`, until `releaseSession`; a release now drops the sink, step (d) drops the backend device, and only a shell of counters stays for `stats` until the session ends or the key opens again. The majors were HO-2 (a failed or closed host's present accessors answer null, so the display shows "no graphics"), HY-1 (D01, above) and FO-1 (the shipped NOTICE, above); the minors HY-2 (H72), SP-2 (the dev-build integrity check also runs before an adopted payload is handed out or cloned and on `RESET`'s pixels), TE-1 (`-XX:ActiveProcessorCount=2` does not pin cores: a real two-core run needs an affinity mask, `start /affinity 3` on Windows or `taskset -c 0,1` on Linux, and the earlier "2-CPU" runs had not pinned) and TE-2 (the oracle backend gives each readback its own latency, so two readbacks fetched in one pass are now tested); the nits included HO-4 and SP-4 (above) and Javadoc and test-strength points. A fix round in three parts followed, then a mutant recheck of 216 rows over every unit's mutants, pinned to two real cores, which restored two test powers the fixes had lost (`GlFirstReadbackTest`'s `NEVER` pass and a partial update in the integrity test) and found the worker race in `GlHostCoreTest` nearly vacuous on two cores, one of the upload seam's contracts untested and noise from a fold pool's shutdown. A follow-up run paced the race with floors per device (400 frames, 400 readbacks and 20 resets; the smallest counts seen were 1 712, 1 732 and 56), tested the upload contract, shut the pool down without an interrupt, moved `open()`'s failure check under the host lock, made the build always pass `opengpu.gl.readbackFinish` and every Javadoc task read and write UTF-8, and its own review found two minor points and a nit. The llvmpipe failure then got a diagnosis with an independent check, and the copy wait with `CopyWaitScenario` got an adversarial verification over the whole matrix (Intel and NVIDIA on Windows, WSL's llvmpipe on all cores and on two, and Mesa's d3d12 driver), which could not refute it. The close-out run then gave `CopyWaitScenario` its second phase (above) and `:core`'s `DeviceConcurrencyTest` the same shutdown of its fold pool: it now calls `shutdown`, records a failure if the pool has not stopped within 20 s, and only then calls `shutdownNow`, whose interrupt had reached a drain task the release had already let go. Before, 6 of 15 two-core runs printed that task's "interrupted waiting for the drain's release" into the test XML, and all 15 passed; after, none of 20 did. No test count changed. 28:188, :415, :482, :631, :663, :938, :1083 and :1084 point here. The roadmap's S8 entry, its 2D-path, hosts, engine-lessons, measurements and `:gl-testkit` items and its order of steps (RM:54, :35, :36, :38, :43, :206, :58), 24's module rows (24:83, :86), 23:62 and :259, 25's C03 row (25:269), the amendments at the end of the roadmap, 24, 23, 25, 29 and 20, and research/README.md record S8 the same day.
