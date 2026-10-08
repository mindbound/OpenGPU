# 15 — Gap: keyboard attachment and input topology for the OpenGPU display

Gap-fill researcher, 2026-10-08. Closes the completeness critic's finding that `00`, `08-A`, `08-C` and `09` state four different rules for where keyboards attach to a screen. Everything below was read in the local OpenComputers-GTNH checkout (`C:\Users\astro\Downloads\OpenComputers-GTNH`, tag `1.12.55-GTNH`) and re-checked against the `1.12.64-GTNH` clone in the scratchpad (the version installed in the instance). Citations are `OC/<path>:<line>` for `src/main/scala/li/cil/oc/<path>` and `API/<path>:<line>` for `src/main/java/li/cil/oc/api/<path>`; line numbers are 1.12.55 unless a 1.12.64 number is given explicitly (§1.9 lists the shifts). `[V]` = read in source; `[I]` = inference or design recommendation.

## Summary

1. **The OC rule.** A screen offers its node on its five non-front faces to anything (cables, keyboards, computers), and on its front face *only* when the block touching the front is an OC keyboard (`OC/common/tileentity/Screen.scala:63-67`). So keyboards attach on **any of the six faces, the front included**, and the front is **keyboard-only**. The keyboard side of the handshake adds its own face rule (`OC/common/tileentity/Keyboard.scala:25-33`). A keyboard can never be *mounted on* the front (the front is non-solid, `OC/common/block/Screen.scala:31`, and the keyboard block refuses it, `OC/common/block/Keyboard.scala:59-65`); the front-face connection is the classic "keyboard on the floor or wall in front of the picture" layout. `00` ("front face only"), `08-A` ("any face but the front"), `08-C`/`09` ("on its front face") were each half of this rule. [V]
2. **The OpenGPU rule.** `opengpu_screen` implements `SidedEnvironment` with `sidedNode(side) = node` for the five non-front faces and, for the front, `node` iff the neighbouring tile is an `Environment` whose node host is an `api.internal.Keyboard` (API-only test, no OC internals); `canConnect(side) = side != front`. Every constituent block of a multi-block display keeps its own node (origin `Visibility.Network`, the rest `Visibility.None`), key messages are sent from every constituent's node with `sendToNeighbors`, and `hasKeyboard` is the union over all constituents and all six faces (§2). [I, modelled 1:1 on the verified OC code]
3. **HostAware.** Restrict the card to `Case` and `Server` for v1 (`worksWith(stack, host) = isComputer(host) || isServer(host)`). A card inside a robot, tablet, microcontroller or drone sits in an **isolated internal network** that only bridges `network.message` packets to the wired world (`OC/common/tileentity/RobotProxy.scala:109-115`, `OC/common/tileentity/Robot.scala:371-377`, `OC/common/tileentity/Microcontroller.scala:166-200`, `OC/common/item/Tablet.scala:288-293`), so `bind(address)` could never find a display block, and their built-in text screens are fixed at assembly, GUI-only objects with no pixel surface to replace. `09 §3.2`'s "HostAware Case/Server/Robot/Tablet" promises hosts that cannot work. [V for the isolation; I for the recommendation]
4. **Touch coordinates.** OC's in-world projection (`Screen.scala:100-139`) yields 0-based *fractional cell* coordinates on the wire and the server converts them to **1-based integer cells** (`+1`) unless `setPrecise` (T3 only). OpenGPU keeps the same projection (2.25/16-block bezel, uniform-scale letterbox, centred), substitutes the framebuffer resolution for `renderWidth/renderHeight`, floors to **0-based integer pixels**, rejects clicks outside `[0,W)×[0,H)` (OC does not: its `inBounds` test has an `||`/`&&` slip at `Screen.scala:137` and `click` ignores it anyway) and boxes the coordinates as `Long`/`Double` per `09 §3.2`'s signal-argument rule (§4). [V for OC; I for the OpenGPU convention]

## 1. The OC rule, from the source

### 1.1 Frame of reference

A screen is `traits.Rotatable`; its `facing` is the picture side (`OC/common/tileentity/traits/Rotatable.scala:73-76`), and in the block's local frame the picture side is always `ForgeDirection.SOUTH`: `toLocal(facing) == SOUTH` for every pitch/yaw combination in `OC/util/RotationHelper.scala:51-81` (the translation tables map the yaw direction, or `UP`/`DOWN` when pitched, to local `south`). Throughout the OC code "`toLocal(side) != SOUTH`" therefore reads "`side` is not the front". [V]

### 1.2 Which faces offer the node

`OC/common/tileentity/Screen.scala:63-67` [V]:

```scala
@SideOnly(Side.CLIENT)
override def canConnect(side: ForgeDirection) = toLocal(side) != ForgeDirection.SOUTH

// Allow connections from front for keyboards, and keyboards only...
override def sidedNode(side: ForgeDirection) =
  if (toLocal(side) != ForgeDirection.SOUTH ||
      (world.blockExists(position.offset(side)) &&
       world.getTileEntity(position.offset(side)).isInstanceOf[Keyboard])) node else null
```

| Face of the screen block | Cable / computer / adapter | OC keyboard |
|---|---|---|
| back, top, bottom, left, right (local ≠ SOUTH) | node offered (`:67`, first disjunct) | node offered (same clause) |
| front (local SOUTH, the picture) | **null** — never connects | node offered **iff** the tile in front `isInstanceOf[tileentity.Keyboard]` (`:67`, second disjunct; comment `:66`) |

The client-side `canConnect` (`:64`) never reports the front; it only feeds cable rendering, because nodes do not exist on the client (`API/network/SidedEnvironment.java:37-54`). [V]

### 1.3 The keyboard's side of the handshake

`OC/common/tileentity/Keyboard.scala:25-33, 58-60` [V]:

```scala
def hasNodeOnSide(side: ForgeDirection): Boolean =
  side != facing && (isOnWall || side != forward.getOpposite)
override def canConnect(side: ForgeDirection) = hasNodeOnSide(side)        // client
override def sidedNode(side: ForgeDirection) = if (hasNodeOnSide(side)) node else null
private def isOnWall = facing != ForgeDirection.UP && facing != ForgeDirection.DOWN
private def forward = if (isOnWall) ForgeDirection.UP else yaw
```

On placement (`OC/common/block/Item.scala:90-92`) a keyboard gets `setFromEntityPitchAndYaw(player)` then `setFromFacing(side)`, so its `facing` is the normal of the face it was placed against, pointing *away* from the supporting block, and its `yaw` is the player's look direction. Hence a keyboard exposes its node on every face except its open top (`facing`); a floor or ceiling keyboard additionally hides the face behind the typist (`yaw.getOpposite`), while a wall keyboard exposes all five remaining faces including the one against the wall. `Network.joinOrCreateNetwork` requires *both* sided nodes to be non-null for the pair (`OC/server/network/Network.scala:453-483`, lookup `:491-497`), so "keyboard touches screen front" connects only when the keyboard's touching face is one of its node faces — for a floor keyboard in front of a wall screen that means its `yaw` must point at the screen. [V]

### 1.4 Mounting versus touching

Two separate rules decide where a keyboard can *sit* and where it can *connect*:

- **Sit.** `OC/common/block/Keyboard.scala:59-65` (`canPlaceBlockOnSide`, with `side` translated by `OC/common/block/SimpleBlock.scala:223-227` into the direction from the keyboard toward its support) requires the support's face to be solid and, if the support is an OC screen, `screen.facing != side.getOpposite` — a screen whose front faces the keyboard is refused. The front is also non-solid (`OC/common/block/Screen.scala:31`), and `onNeighborBlockChange` pops a keyboard whose support stops qualifying (`block/Keyboard.scala:91-99`). So a keyboard **cannot be mounted on a screen's front**; it can be mounted on the screen's back, top, bottom or sides, or on any other block. [V]
- **Connect.** `Screen.scala:67` plus `Keyboard.scala:33`. The front-face case is a keyboard mounted on a *neighbouring* block whose edge touches the picture: the floor block below a wall screen (keyboard facing UP, yaw toward the screen), or the wall below/beside it (keyboard on the wall, screen above it). `block/Keyboard.scala:107-133` (`adjacencyInfo`) enumerates exactly these layouts — screen behind the keyboard's support face, screen "in front of" a floor keyboard (`:114-121`), screen "below" a wall keyboard (`:122-128`) — to route a right-click on the keyboard to the screen's GUI (`:101-105`). [V]

### 1.5 When the connection is made

A tile that is an `Environment` is scheduled for `Network.joinOrCreateNetwork` on its first server tick (`OC/common/tileentity/traits/Environment.scala:34-39` → `OC/common/EventHandler.scala:76-79`); the keyboard block re-joins on `updateTick` (`block/Keyboard.scala:53-57`). The join walks the six sides, asks *this* tile for `sidedNode(side)` and the neighbour for `sidedNode(side.getOpposite)` (`Network.scala:461-463`, `:491-497`), and connects the pair when both are non-null and colour/FMP/Immibis checks pass (`:467-473`). Nothing is persisted for this; the screen's `sidedNode` is evaluated against the live world every time. [V]

### 1.6 `hasKeyboard`, `getKeyboards()` and GUI gating

- `tileentity.Screen.hasKeyboard` (`Screen.scala:79-87`): true if **any constituent block** of the multi-block has, on **any of its six faces**, a `Keyboard` tile with `hasNodeOnSide(side.getOpposite)` (the keyboard's face toward the screen). It does not consult the screen's own `sidedNode`, which is equivalent because `:67` is non-null for every non-front face and for the front exactly when the neighbour is a keyboard. [V]
- The GUI opens only when `screen.hasKeyboard && (force || player.isSneaking == origin.invertTouchMode)` (`OC/common/block/Screen.scala:345-352`, client-only `openGui`, no container), and the GUI receives `() => origin.hasKeyboard` to show the "keyboard missing" icon (`OC/client/GuiHandler.scala:43-44`, `OC/client/gui/traits/InputBuffer.scala:79-98`). Otherwise a right-click on the front of a T2/T3 screen is an in-world touch (`block/Screen.scala:353-357`). `invertTouchMode` is toggled from Lua by `screen.setTouchModeInverted` (`OC/common/component/Screen.scala:10-22`). [V]
- `screen.getKeyboards()` (`OC/common/component/TextBuffer.scala:196-205`; 1.12.64 `:197-206`) returns the addresses of the network neighbours of **every constituent's node** whose host is an `api.internal.Keyboard`, after a 0.25 s pause. [V]

### 1.7 Key delivery and reach checks

`TextBuffer.ServerProxy.keyDown/keyUp/clipboard` (`TextBuffer.scala:831-842`; 1.12.64 `:832-843`) call `sendToKeyboards`, which for a screen host does `screen.screens.foreach(_.node.sendToNeighbors(name, values))` (`:909-916`; 1.12.64 `:912-919`) — the message leaves from every constituent block's node, so a keyboard touching any block of the multi-block hears it. The keyboard component (`OC/server/component/Keyboard.scala:66-104`) turns `keyboard.keyDown/keyUp/clipboard` into `key_down/key_up/clipboard` signals via `computer.checked_signal` (`:142-143`) after `isUseableByPlayer` (`:137-140`: `distanceSq <= 64` to the **keyboard's** host position, or `usableOverride`, which only `OC/common/component/TerminalServer.scala:49-62` sets). The screen itself reach-checks `clipboard` and `dropFile` (`TextBuffer.scala:839-847`) and all mouse events (`:888`, via `isUseableByPlayer :407-412`, any constituent within 8 blocks), but **not** `keyDown/keyUp` — those are reach-checked only by the keyboard, as `03 §2` already records. [V]

### 1.8 Multi-block specifics

Only the origin's buffer node is `Visibility.Network`; the other constituents are set to `Visibility.None` (`Screen.scala:226-237`) but stay in the network as nodes with neighbours, which is what makes `sendToKeyboards`, `hasKeyboard` and `getKeyboards` work across the whole surface. Merging is a deterministic BFS over same tier/pitch/yaw/colour tiles (`:183-246`, `tryMerge :362-397`) run on both sides without persisted state. [V]

### 1.9 Version applicability

`tileentity/Screen.scala`, `tileentity/Keyboard.scala`, `server/component/Keyboard.scala`, `block/Screen.scala`, `block/Keyboard.scala`, `block/Item.scala`, `item/Tablet.scala`, `tileentity/Robot.scala`, `tileentity/RobotProxy.scala`, `tileentity/Microcontroller.scala`, `tileentity/Rack.scala`, `client/gui/Screen.scala`, `client/GuiHandler.scala`, `server/network/Network.scala`, `component/Screen.scala`, `component/TerminalServer.scala`, `API/prefab/DriverItem.java` and `API/internal/Keyboard.java` are byte-identical between 1.12.55 and 1.12.64 (`diff -q`). `common/component/TextBuffer.scala` differs only by the `dropFile(byte[])` change: one import at `:39` (+1 from there) and `:844-846` → `:845-849` (+3 after), so the 1.12.64 numbers are `isUseableByPlayer :408-413`, `keyDown/keyUp/clipboard :832-843`, `mouseDown :852-854`, `sendMouseEvent :890-910`, `sendToKeyboards :912-919`. `server/PacketHandler.scala` moved: `onKeyDown :179`, `onKeyUp :189`, `onClipboard :199`, `onMouseClick :229`, `onMouseUp :245`, `onMouseScroll :259` in 1.12.64 (vs `:189/:199/:209/:230/:246/:260` in 1.12.55). [V]

### 1.10 Corrected sentences

- **`00 §2` (line 49)** "screens accept connections on all faces but the front, `OC/common/tileentity/Screen.scala:63-67`" → "screens accept connections on all faces but the front, and on the front only from an adjacent OC keyboard (`OC/common/tileentity/Screen.scala:63-67`)".
- **`00 §3` (line 69, last sentence)** "Keyboards attach to a screen's front face only (`Screen.scala:67`)." → "A screen offers its node on every face except its front (`Screen.scala:64,67`); the front offers it when, and only when, the adjacent tile is an OC `Keyboard` (`:66-67`), so keyboards connect on any of the six faces and the front is keyboard-only. A keyboard cannot be mounted *on* the front (`block/Screen.scala:31`, `block/Keyboard.scala:59-65`); the front-face case is a keyboard mounted on the floor or wall in front of the picture (`block/Keyboard.scala:107-133`), and the keyboard must expose its node on the touching face (`Keyboard.scala:25-33`). `hasKeyboard` and `getKeyboards()` consider all six faces of every constituent block (`Screen.scala:79-87`, `TextBuffer.scala:196-205`)."
- **`08-A §3` (line 99)** "Keyboards attach to any face but the front;" → "Keyboards attach on any face, the front included; the front is keyboard-only (cables do not connect there), exactly OC's `Screen.scala:63-67` rule;".
- **`08-C §3` (line 98)** "`SidedEnvironment` accepting a keyboard on its front face like OC's screen (`Screen.scala:63-67`)" → "`SidedEnvironment` offering its node on the five non-front faces to anything and on the front face only to an adjacent OC keyboard, like OC's screen (`Screen.scala:63-67`; 15 §2)".
- **`09 §2` (line 34)** "keyboards attach to the screen's *front* face (A §3 inverted it; `Screen.scala:63-67`)" → "keyboards attach on any face including the front, and the front is keyboard-only — A §3's 'any face but the front', C §3's 'on its front face' and this document's first draft were each half of `Screen.scala:63-67` (15 §1)".
- **`09 §3.2` (line 49)** "display block **`opengpu_screen`** (`TileEntityEnvironment`, `Visibility.Network`, keyboard on the front face)" → "display block **`opengpu_screen`** (`TileEntityEnvironment` + `SidedEnvironment`, `Visibility.Network`; node on the five non-front faces, front face keyboard-only, every multi-block constituent keeps its own node — 15 §2)".

## 2. The OpenGPU display rule (`SidedEnvironment` spec)

Design, modelled 1:1 on §1 so that OC's keyboard block, placement rules, reach checks and `getKeyboards` semantics carry over unchanged. [I]

**2.1 Faces.** `opengpu_screen`'s tile entity extends `API/prefab/TileEntityEnvironment` (joins the network on its first `updateEntity`, removes the node on invalidate) *and* implements `API/network/SidedEnvironment` — `Network.getNetworkNode` tests `SidedEnvironment` before `Environment` (`Network.scala:491-497`), so the prefab's plain `node()` is never consulted for connections:

```java
private static final ForgeDirection FRONT = ForgeDirection.SOUTH;   // local frame, as OC

@Override public Node sidedNode(ForgeDirection side) {              // server only
    if (toLocal(side) != FRONT) return node();                      // 5 faces: anything
    int nx = xCoord + side.offsetX, ny = yCoord + side.offsetY, nz = zCoord + side.offsetZ;
    if (!worldObj.blockExists(nx, ny, nz)) return null;
    return isKeyboard(worldObj.getTileEntity(nx, ny, nz)) ? node() : null;   // front: keyboards only
}

@SideOnly(Side.CLIENT) @Override public boolean canConnect(ForgeDirection side) {
    return toLocal(side) != FRONT;                                  // cable rendering only
}

static boolean isKeyboard(TileEntity te) {                          // API-only; no li.cil.oc.common.*
    if (!(te instanceof li.cil.oc.api.network.Environment)) return false;
    Node n = ((li.cil.oc.api.network.Environment) te).node();
    return n != null && n.host() instanceof li.cil.oc.api.internal.Keyboard;
}
```

`isKeyboard` is true for OC's keyboard block on both sides: `tileentity.Keyboard.node` is the node of a `server.component.Keyboard` (an `api.internal.Keyboard`) created by `DriverKeyboard.createEnvironment` without an `isRemote` check (`OC/common/tileentity/Keyboard.scala:18-23`, `OC/integration/opencomputers/DriverKeyboard.scala:15`). The mutual check (the keyboard's `sidedNode(side.getOpposite)`) is done by `Network.joinOrCreateNetwork` itself (§1.5), so the display needs no knowledge of `hasNodeOnSide`. The display's block must report `isSideSolid(front) = false` (as `block/Screen.scala:31`) so OC's keyboard block refuses to be mounted on the picture (`block/Keyboard.scala:59-65`: for a non-OC screen the only remaining test is solidity). [I]

**2.2 Multi-block.** Every constituent tile keeps its own node for its whole life; the origin's is a `Component` with `Visibility.Network`, the others are switched to `Visibility.None` on merge and back on split, exactly `Screen.scala:226-237` (`API/network/Component.setVisibility`). Consequences that must hold from M0 even though merging itself is an M5 item in `09 §4` (a single block is the 1×1 case): a keyboard may touch *any* constituent on *any* of its six faces (front included, keyboard-only); `hasKeyboard = ∃ constituent s, ∃ side d: isKeyboard(neighbour(s,d)) && neighbourOffersNode(d.getOpposite)`; `getKeyboards()` = union over constituents of `node.neighbors()` whose host is an `api.internal.Keyboard` (`TextBuffer.scala:196-205` shape); key forwarding = `for each constituent: node.sendToNeighbors("keyboard.keyDown"|"keyboard.keyUp"|"keyboard.clipboard", player, ...)` with OC's exact argument shapes (`(player, Character, Integer)` and `(player, String)`, `server/component/Keyboard.scala:68-90`). [I]

**2.3 `hasKeyboard` on the client.** Compute it on the server (`sidedNode(d.getOpposite) != null` on the neighbour) and ship it as one boolean in the description packet alongside `displayId`/resolution/tier (`09 §3.6`), because the client decides whether a right-click opens the GUI. `SidedEnvironment.canConnect` (`@SideOnly(CLIENT)`) is the API's own client-side query and may serve as a fallback, but it is not needed. [I]

**2.4 Opening the GUI and touch mode.** Mirror `block/Screen.scala:337-360`: on right-click of any constituent, if `hasKeyboard && (player.isSneaking == invertTouchMode)` open the pixel GUI client-side (no container; reach is enforced server-side per packet), else if the clicked face is the front treat it as an in-world touch (§4); expose `setTouchModeInverted/isTouchModeInverted` on `opengpu_screen` like `component/Screen.scala:10-22`. **Caveat:** right-clicking an OC *keyboard block* opens a screen GUI only for OC's own `block.Screen` (`block/Keyboard.scala:101-133` matches on OC's block class), so a keyboard next to an `opengpu_screen` does nothing on right-click unless OpenGPU subscribes to `PlayerInteractEvent(RIGHT_CLICK_BLOCK)` for OC's keyboard block, repeats the three `adjacencyInfo` layouts against its own display, cancels the event and opens its GUI. Optional parity feature; recommend M1. [I]

**2.5 Reach.** OC's keyboard component applies the 8-block check against *its own* position (`server/component/Keyboard.scala:137-140`), which covers `keyboard.*` messages. OpenGPU's server packet handler should additionally reject any C2S input packet from a player more than 8 blocks from every constituent (OC does this for mouse but not for keys; `03 §2 C10`) and check chunk-watching, `isFinite` and size, as `09 §3.4` already lists. Nothing else about input is OC-internal. [I]

## 3. Can the card be `HostAware` for `Tablet` and `Robot`?

**3.1 What `HostAware` does.** `API/driver/item/HostAware.java:27` adds `worksWith(stack, Class<? extends EnvironmentHost> host)`, consulted when a component is installed (and by the assembler templates). OC's GPU is `HostAware` but inherits the default `worksWith(stack, host) = worksWith(stack) && !blacklisted` (`OC/integration/opencomputers/Item.scala:17-22`), so it is accepted by every host whose template has `Slot.Card` slots: cases, servers, robots (`OC/common/template/RobotTemplate.scala:72-174`), tablets (`TabletTemplate.scala:80-140`), microcontrollers (`MicrocontrollerTemplate.scala:64-117`) and drones (`DroneTemplate.scala:69-123`). The API prefab provides `isComputer/isServer/isRobot/isTablet` (`API/prefab/DriverItem.java:62-84`); `api.internal.Microcontroller`/`Drone` are API marker interfaces too. [V]

**3.2 Which hosts can reach an `opengpu_screen` block.** `bind(address)` is `node.network.node(address)` (OC's GPU: `OC/server/component/GraphicsCard.scala:270-277`), i.e. the display must be in the *same* node network as the card. [V]

| Host | Card's network | Display block reachable? |
|---|---|---|
| Case | machine node joins the wired network on first tick (`traits/Environment.scala:34-39`, `EventHandler.scala:76-79`); components hang off it | yes |
| Server in rack | rack offers nodes on all faces but its front (`OC/common/tileentity/Rack.scala:246-248`), mountables mapped per side by the rack GUI | yes |
| Robot | `Robot.initialize` gives the machine node its **own** network (`OC/common/tileentity/Robot.scala:371-377`); the `RobotProxy` block node (`RobotProxy.scala:30-32`) is what touches cables, and the two networks exchange only `network.message` packets (`RobotProxy.scala:109-115`, `OC/server/component/Robot.scala:145-151`) | **no** |
| Tablet | `TabletWrapper` joins its machine node to a fresh network and connects its own components (`OC/common/item/Tablet.scala:288-293, 297-301`); no block contact at all | **no** |
| Microcontroller | machine node in a fresh network connected to `snooperNode`; the six side plugs are separate nodes; only `network.message` crosses (`Microcontroller.scala:162-200`) | **no** |
| Drone | entity host; same shape as the robot [I, not read] | no [I] |

**3.3 What the built-in screens are.** The robot's screen and keyboard are fixed at assembly (`RobotTemplate.scala:36-37`; `Robot.isItemValidForSlot` bars `DriverScreen`/`DriverKeyboard` from containers, `Robot.scala:709-725`), wired buffer↔keyboard↔GPU inside the robot (`Robot.scala:572-592`), and shown **only in the robot GUI** (`OC/client/gui/Robot.scala:26-30, 104-124`; `hasKeyboard` from `info.components`); `OC/client/renderer/tileentity/RobotRenderer.scala` (525 lines) contains no `TextBuffer` reference, so the in-world robot "screen" is a static texture. The tablet's screen is a forced `ScreenTier1` item in slot 0 (`TabletTemplate.scala:45`, `Tablet.scala:90-91, 336-344`), capped to 80×25 four-bit (`Tablet.scala:303-305, 402-406`), with an optional keyboard (`TabletTemplate.scala:19`, wiring `Tablet.scala:310-321`), and the running tablet opens the ordinary `gui.Screen` on that buffer with `hasKeyboard = () => true` (`GuiHandler.scala:87-96`). Neither host has a pixel surface and neither lets an addon substitute its screen. [V]

**3.4 Recommendation.** For M0–M4 declare

```java
@Override public boolean worksWith(ItemStack stack, Class<? extends EnvironmentHost> host) {
    return worksWith(stack) && (isComputer(host) || isServer(host));   // DriverItem.java:66-68, 78-80
}
```

so the assembler and the robot/tablet/MCU/drone slots refuse the card instead of accepting a component whose `bind` can never succeed. A later **item-hosted display** could reopen Robot/Tablet: an `Slot.Upgrade` item whose environment *is* an `opengpu_screen` (so `bind` finds it inside the isolated network), a `GuiOpenEvent` interception that replaces OC's `gui.Screen`/`gui.Robot` with an OpenGPU GUI when the host holds that upgrade (the only API-visible hook; `TabletWrapper`/`tileentity.Robot` are internals), stream viewers = the holder (tablet) or GUI watchers (robot), and no in-world rendering for the robot without an overlay renderer. That is a self-contained M5+ feature; nothing in the display-block design needs to anticipate it beyond keeping the display environment class independent of the tile entity (the `Device`/`FrameSink` split in `09 §3.8` already does that). [I]

## 4. Touch coordinates through the multi-block projection

### 4.1 OC's pipeline [V]

1. **Trigger.** Right-click on a T2/T3 screen's front face when the GUI case does not apply (`OC/common/block/Screen.scala:353-357`: `tier > 0 && side == screen.facing`; client-only `screen.click(hitX, hitY, hitZ)`, server returns `true`). Arrows hitting the front are routed to `click` for the shooting player (`block/Screen.scala:368-393`, `Screen.scala:175-177, 247-258`). Walking on an UP-facing screen raises `walk(x+1, height-y)` (1-based block coordinates, `computer.signal`, `Screen.scala:165-173`, `block/Screen.scala:362-366`).
2. **Projection** (`Screen.scala:100-139`), with `hitX/Y/Z ∈ [0,1]` block-relative:
   - `hx, hy` = hit projected onto the screen's local EAST (right) and UP axes (`:102-103`); `tx = hx < 0 ? 1 + hx : hx` normalises axes that map to negative world directions, `ty = 1 - (...)` so `ty` grows downward (`:104-105`).
   - `(lx, ly) = localPosition` = this block's offset from the origin in (right, up) block units (`:73-77`); `ax = lx + tx`, `ay = height - 1 - ly + ty` = distance in blocks from the multi-block's left and top edges (`:106-107`).
   - **Bezel**: `border = 2.25/16` block; a hit within the border of the *whole* surface returns `(false, None)` (`:110-113`), and on the server the function returns `(true, None)` (`:114`).
   - **Display area**: `iw = width − 2·border`, `ih = height − 2·border`; `rx = (ax − border)/iw`, `ry = (ay − border)/ih` (`:116-117`).
   - **Letterbox**: `bpw = renderWidth/iw`, `bph = renderHeight/ih` (rendered content pixels per block on each axis, `:120-122`); if `bpw > bph` the content is width-limited: `rh = bph/bpw`, `bry = (ry − (1−rh)/2)/rh`; if `bph > bpw` it is height-limited: `rw = bpw/bph`, `brx = (rx − (1−rw)/2)/rw`; else identity (`:123-135`). This is "scale uniformly to fit, centre".
   - **Result**: `(brx·bw, bry·bh)` with `bw/bh = origin.buffer.getViewportWidth/Height` → **0-based fractional cell coordinates** (`:138`). Clicks in the letterbox bars yield values outside `[0,bw)×[0,bh)`: `:137` computes `inBounds = bry >= 0 && bry <= 1 && brx >= 0 || brx <= 1` (precedence makes it `(…) || brx <= 1`), and `click` (`:154-163`) ignores `inBounds` whenever coordinates are present, so OC sends them anyway.
3. **Wire.** `origin.buffer.mouseDown(x, y, 0, null)` → `ClientProxy` → `sendMouseClick(address, float x, float y, drag, button)` (`TextBuffer.scala:706-709`); server `onMouseClick` rejects NaN/∞ (`server/PacketHandler.scala:230-244`; 1.12.64 `:229-243`) and calls the server proxy.
4. **Signal.** `sendMouseEvent` (`TextBuffer.scala:887-907`; 1.12.64 `:890-910`): reach check against any constituent (`:407-412`), then `Int.box(x.toInt + 1), Int.box(y.toInt + 1)` → **1-based integer cells**, or the raw doubles (0-based fractional cells) when `precisionMode` — `setPrecise` is T3-only (`:207-220`); `computer.checked_signal(player, "touch"|"drag"|"drop"|"scroll", x, y, button|delta[, name])` → `Machine.canInteract` → Lua `touch(addr, x, y, button, player)`.
5. **GUI path** (`OC/client/gui/Screen.scala:72-91`): `bx = (mouseX − x − margin)/scale/charRenderWidth` (0-based fractional cell), out-of-range not sent, drag packets coalesced per cell horizontally and per half-cell vertically (`:74-79`); release outside the buffer sends `mouseUp(−1, −1)` (`:61-64`), which the server turns into `drop(addr, 0, 0, …)`.

### 4.2 OpenGPU convention [I]

Keep step 2 verbatim with the framebuffer standing in for the text buffer: `renderWidth := W`, `renderHeight := H` (the display's current resolution in pixels; only the ratio matters), same `2.25/16` bezel so the hit-test and the TESR share one constant, same letterbox. Then:

```
(brx, bry) as in Screen.scala:116-135 with bpw = W/iw, bph = H/ih
if brx < 0 || brx >= 1 || bry < 0 || bry >= 1: not a touch (return false, let the click fall through)
px = floor(brx * W), py = floor(bry * H)          // 0-based integer pixels, 0 ≤ px < W, 0 ≤ py < H
precise: (brx * W, bry * H) as doubles              // 0-based fractional pixels
```

- **Lua signature** unchanged from `09 §3.2`: `touch/drag/drop(screen, px, py, button, player)`, `scroll(screen, px, py, delta, player)`; `px, py` boxed as `Long` (integer mode) or `Double` (precise) — not `Integer` as OC does (`TextBuffer.scala:898-899`) — because `09 §3.2` forbids `Integer` signal arguments (nil after reload, `04 §3`). Lua sees the same integers on 5.3/5.4 and numbers on 5.2/LuaJ. [I]
- **Wire**: client sends `(address, float px, float py, drag, button)` in **0-based pixel units** (float, so precise mode needs no second packet type); the server re-derives bounds from the display's current `W×H`, rejects NaN/∞ and out-of-range values (no clamping), reach-checks against every constituent, then floors. Clicks in the letterbox bars are rejected at the client (step above) and, if forged, at the server. [I]
- **GUI path** (`03 §2` 1:1 magnification): `px = floor((mouseX − originX)/mag)`, `py = floor((mouseY − originY)/mag)`, not sent when outside `[0,W)×[0,H)`; drag coalescing per pixel instead of per cell would allow up to `W×H` packets per drag, so coalesce per client tick (≤ 20 packets/s) and always send the final position on release; release outside the picture sends `drop` with the last in-picture position rather than OC's `(−1,−1) → (0,0)` quirk. [I]
- **`setPrecise`**: keep on `opengpu_screen` for API parity; allow on every tier (OC's T3 gate exists because sub-cell precision only matters with 16×16 cells; pixel coordinates are already fine). [I]
- **Multi-block**: the clicked tile may be any constituent; it supplies `localPosition` relative to the origin (`Screen.scala:73-77` shape) and forwards the event through the origin's display environment, which owns the node address the packet names. The in-world click is accepted on the front face of any constituent, never on other faces. [I]
- **Optional parity**: arrows (`block/Screen.scala:368-393`) and `walk` are cheap to mirror but not required by any `09` milestone; recommend M4 or later. [I]

## Implications for 09-architecture-synthesis.md

Statements that must change, with replacements:

1. **§2, line 34** — "keyboards attach to the screen's *front* face (A §3 inverted it; `Screen.scala:63-67`)" → "keyboards attach on any face including the front, and the front is keyboard-only; A §3 ('any face but the front'), C §3 ('on its front face') and this document's earlier text were each half of `Screen.scala:63-67` (15 §1)".
2. **§3.2, line 49** — "(`TileEntityEnvironment`, `Visibility.Network`, keyboard on the front face)" → "(`TileEntityEnvironment` + `SidedEnvironment`: node on the five non-front faces to anything, on the front only to an adjacent `api.internal.Keyboard`; `Visibility.Network` on the origin, every multi-block constituent keeps its own node — 15 §2)".
3. **§3.2, line 49** — "`HostAware` Case/Server/Robot/Tablet" → "`HostAware` Case/Server only (`worksWith(stack, host) = isComputer || isServer`, `DriverItem.java:66-80`); robot, tablet, microcontroller and drone hosts live in isolated internal networks where `bind` can never find a display block and their built-in screens are fixed GUI-only text buffers (15 §3); an item-hosted display is a possible M5+ feature".
4. **§3.2, line 62** — "Touch input keeps OC's names and argument order (…) with **0-based integer pixel coordinates** (doubles under `setPrecise`), emitted as `computer.checked_signal` after the 8-block reach check (00 §3)" → "Touch input keeps OC's names and argument order (…) with **0-based integer pixel coordinates** boxed as `Long` (`Double` under `setPrecise`, allowed on every tier), computed by OC's own bezel-and-letterbox projection (`Screen.scala:100-139`) with the framebuffer resolution in place of `renderWidth/renderHeight`, rejected (not clamped) outside the picture, emitted as `computer.checked_signal` after an 8-block reach check against any constituent (15 §4)".
5. **§3.2, line 62** — "keyboard input is forwarded as `keyboard.keyDown/keyUp/clipboard` node messages to adjacent keyboards exactly as `TextBuffer.ServerProxy.sendToKeyboards` does, so OC's keyboard component applies its own reach check (03 §2)" → append: "— from *every* constituent block's node, so a keyboard touching any block of a multi-block display, on any face, receives keys; `hasKeyboard` (GUI gating, synced to the client) and `getKeyboards()` use the same six-face, all-constituents rule (15 §2)".
6. **§3.4, line 82** — "Every C2S packet is validated: chunk watching, reach ≤ 8 blocks, `isFinite`, size." → append "and touch coordinates range-checked against the display's current resolution; key packets are reach-checked by OpenGPU as well as by OC's keyboard component (15 §2.5)".
7. **§4, M1 row** — "pixel input + GUI 1:1" → "pixel input + GUI 1:1 (projection and validation per 15 §4), keyboard forwarding, `hasKeyboard` GUI gating, `setTouchModeInverted`, optional right-click-on-keyboard handler (15 §2.4)".
8. **§4, M5 row** — "multi-block screens" → "multi-block merging (the per-constituent node topology of 15 §2.2 is in place from M0, so M5 adds only the merge/split logic and the origin viewport)".
9. **§7 index** — add `15-gap-keyboard-attachment-and-input-topology.md | OC's screen/keyboard face rule from source, the OpenGPU SidedEnvironment spec, HostAware restricted to Case/Server, the touch projection and pixel convention`.

Corrections outside `09` (same facts): `00 §2` line 49 and `00 §3` line 69, `08-A §3` line 99, `08-C §3` line 98 — replacement text in §1.10.

## Open questions for the user

1. Should right-clicking an OC keyboard block that touches an `opengpu_screen` open the OpenGPU GUI (requires a `PlayerInteractEvent` handler on OC's keyboard block, §2.4)? Default assumed: yes, in M1.
2. Is an item-hosted display for robots/tablets (§3.4) wanted at all, or is "Case and Server only" the permanent scope? Default assumed: Case/Server for v1, item-hosted display not planned.
3. `setPrecise` on every tier (pixel coordinates are already fine-grained) or T3-only like OC? Default assumed: every tier.

## Sources

Local checkouts, read in full or at the cited ranges: `C:\Users\astro\Downloads\OpenComputers-GTNH` (tag `1.12.55-GTNH`): `src/main/scala/li/cil/oc/common/tileentity/{Screen,Keyboard,Robot,RobotProxy,Microcontroller,Rack}.scala`, `common/tileentity/traits/{Rotatable,TextBuffer,Environment}.scala`, `common/block/{Screen,Keyboard,SimpleBlock,Item}.scala`, `common/component/{TextBuffer,Screen,TerminalServer}.scala`, `common/item/Tablet.scala`, `common/item/data/RobotData.scala`, `common/template/{Robot,Tablet,Microcontroller,Drone}Template.scala`, `common/EventHandler.scala`, `server/component/{Keyboard,Tablet,Robot,GraphicsCard}.scala`, `server/network/Network.scala`, `server/PacketHandler.scala`, `client/gui/{Screen,Tablet,Robot}.scala`, `client/gui/traits/InputBuffer.scala`, `client/GuiHandler.scala`, `client/renderer/tileentity/RobotRenderer.scala` (grep only), `integration/opencomputers/{Item,DriverGraphicsCard,DriverKeyboard,DriverScreen}.scala`, `util/RotationHelper.scala`; `src/main/java/li/cil/oc/api/network/SidedEnvironment.java`, `api/driver/item/HostAware.java`, `api/prefab/DriverItem.java`, `api/internal/{Keyboard,TextBuffer,Case,Server,Tablet,Robot,Microcontroller,Drone}.java`. Version check: `diff` of every file above against `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad\ocgtnh-master` (tag `1.12.64-GTNH`, commit `1e4559f`). Research documents reconciled: `00-opencomputers-internals.md` §2–§3, `03-minecraft-1710-rendering-and-networking.md` §2, `08-proposal-A-server-software.md` §3, `08-proposal-C-layered-hybrid.md` §3, `09-architecture-synthesis.md` §2–§4. Nothing in this document was measured in-game; every claim is a source reading or a labelled design inference.
