package io.github.mindbound.opengpu.ocelot

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.Executor
import java.util.function.LongSupplier

import scala.collection.mutable

import io.github.mindbound.opengpu.core.device.{
  CallBudget,
  CallResult,
  Device,
  DeviceCaps,
  FrameListener,
  LimitReached,
  Statuses
}
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener
import io.github.mindbound.opengpu.core.render.{DeviceKey, RenderHost}
import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context, LimitReachedException}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.{Component, Message, Network, Node, Visibility}

/**
  * The `opengpu` component over `:core`'s Device (S10's part of 28 §2.1; S10 plan P7): the M0 callbacks but `bind`,
  * whose messages belong to the display block (28:95), each direct (28:84), reading its arguments only through
  * ocelot's `checkInteger`, `checkByteArray` and `optString` and returning `CallResult.toLua()`. A second `@Callback`
  * layer beside the root's card, on ocelot's API: the two cannot share code, so they share a spec, `:core`'s
  * M0Callbacks, whose names, flags and doc strings CallbackSpecTest holds this class to, and a model, M0Replay, which
  * CallReplay checks these callbacks against (S11 plan P15.4); the in-game self-test is the later cross-check (S10
  * plan P23).
  *
  * The host is injected by constructor, which settles 05:101's open choice; there is no static registry, no DeviceInfo
  * and no no-arg constructor, since S10 never saves a workspace, and [[save]] throws so an accidental save fails loudly
  * [proposal]. Device's own LimitReached, the two-phase pattern's first attempt, is rethrown as ocelot's
  * LimitReachedException, so OC retries the call synchronized on the next tick, as the root's adapter will (28:1077).
  *
  * Lifecycle [proposal, after 28:150-151]: `update()` calls `device.tick()` once per tick, which delivers `"dropped"`
  * and any waiting statuses; `computer.started` and `computer.stopped` from the neighbouring machine reset the device,
  * as ocelot's GPU tests the source (GenericGPU.scala:529-535), so every boot offers a RESET before its first frame;
  * and `dispose()` releases the device.
  *
  * Frame statuses go to `:core`'s RecordingListener and, as the root's `opengpu_frame(card, frameId, status)` will
  * (24:127; 28:110-112), to the machine as a signal with String and Long arguments only (19:202), through the Context
  * the last callback gave, which a start, a stop or a disconnect clears (12:131, :175) [proposal; S10 amendments F11].
  * A signal the full queue refuses (Machine.scala:307) is counted, never retried.
  */
final class OpenGpuEntity(
    tier: Int,
    host: RenderHost,
    key: DeviceKey,
    folds: Executor,
    nanoClock: LongSupplier,
    hostRenderer: Option[String] = None,
    budgets: (Context, Charges) => CallBudget = (context, charges) => new OcelotBudget(context, charges))
  extends Entity with Environment {

  override val node: Component = Network.newNode(this, Visibility.Network)
    .withComponent("opengpu", Visibility.Network)
    .create()

  /** The device's statuses as `:core`'s reference model writes them: "done N" and "dropped N" (S10 plan P7.5). */
  val statuses = new RecordingListener

  /** Every charge the device made through OC (S10 plan P7.3). */
  val charges = new Charges

  private val context = new AtomicReference[Context]()
  private val starts = new AtomicInteger()
  private val stops = new AtomicInteger()
  private val signals = mutable.ArrayBuffer.empty[String]

  private val frames: FrameListener = new FrameListener {
    override def frameDone(frameId: Long): Unit = {
      statuses.frameDone(frameId)
      signal(frameId, Statuses.DONE)
    }

    override def frameDropped(frameId: Long): Unit = {
      statuses.frameDropped(frameId)
      signal(frameId, Statuses.DROPPED)
    }
  }

  val device: Device = Device.create(key, DeviceCaps.forTier(tier), host, folds, frames, nanoClock)

  // ---- The M0 callbacks (28 §2.1)

  // `renderer` and `hostRenderer` are the harness's [proposal; S10 plan P7.2], beside 24:125's names: "host-gl"
  // whenever the host is available, so also on FakeRenderHost, which has no GL, and "none" otherwise. The root's card
  // adds readback and graphics, so the doc is M0Callbacks' generic one, and parity is the 15 keys plus renderer
  // (S11 amendments F-1)
  @Callback(direct = true, doc = "function():table -- the device's caps (28 §8.1) plus the host keys of 24:125")
  def getCaps(context: Context, args: Arguments): Array[AnyRef] = {
    touch(context)
    val caps = new java.util.LinkedHashMap[String, AnyRef](device.getCaps())
    caps.put("renderer", if (host.available()) "host-gl" else "none")
    hostRenderer.foreach(caps.put("hostRenderer", _))
    Array(caps)
  }

  @Callback(direct = true, doc = "function(stream:string):boolean or nil, string -- validates the segment whole and " +
    "appends it to the open frame (28 §3.5)")
  def submit(context: Context, args: Arguments): Array[AnyRef] = {
    val stream = args.checkByteArray(0)
    call(context)(device.submit(_, stream))
  }

  @Callback(direct = true, doc = "function():number or nil, string -- seals the open frame into FRAME(frameId); " +
    "\"busy\", \"no renderer\" or \"host render stalled\" otherwise (24:126)")
  def present(context: Context, args: Arguments): Array[AnyRef] = call(context)(device.present(_))

  @Callback(direct = true, doc = "function(x:number, y:number, w:number, h:number):string, number or nil, string " +
    "-- a region of the newest confirmed frame in the surface byte layout, and its frame id (28 §5.7)")
  def readPixels(context: Context, args: Arguments): Array[AnyRef] = {
    val x = args.checkInteger(0)
    val y = args.checkInteger(1)
    val w = args.checkInteger(2)
    val h = args.checkInteger(3)
    call(context)(device.readPixels(_, x, y, w, h))
  }

  @Callback(direct = true, doc = "function(w:number, h:number[, format:string]):number or nil, string -- a texture " +
    "handle (28 §6.2)")
  def createTexture(context: Context, args: Arguments): Array[AnyRef] = {
    val w = args.checkInteger(0)
    val h = args.checkInteger(1)
    val format = args.optString(2, null)
    call(context)(device.createTexture(_, w, h, format))
  }

  @Callback(direct = true, doc = "function(tex:number, x:number, y:number, w:number, h:number, data:string):" +
    "boolean or nil, string -- a region of the texture's rows (28 §6.3)")
  def updateTexture(context: Context, args: Arguments): Array[AnyRef] = {
    val tex = args.checkInteger(0)
    val x = args.checkInteger(1)
    val y = args.checkInteger(2)
    val w = args.checkInteger(3)
    val h = args.checkInteger(4)
    val data = args.checkByteArray(5)
    call(context)(device.updateTexture(_, tex, x, y, w, h, data))
  }

  @Callback(direct = true, doc = "function(tex:number):boolean or nil, string -- frees the texture, deferred while a " +
    "frame is open (28 §6.4)")
  def free(context: Context, args: Arguments): Array[AnyRef] = {
    val tex = args.checkInteger(0)
    touch(context)
    device.free(tex).toLua()
  }

  // ---- Lifecycle

  override def needUpdate: Boolean = true

  override def update(): Unit = {
    super.update()
    device.tick()
  }

  override def onMessage(message: Message): Unit = {
    super.onMessage(message)
    val name = message.name
    if ((name == "computer.started" || name == "computer.stopped") && node.isNeighborOf(message.source)) {
      context.set(null)
      device.reset()
      (if (name == "computer.started") starts else stops).incrementAndGet()
    }
  }

  override def onDisconnect(node: Node): Unit = {
    super.onDisconnect(node)
    if (node == this.node) context.set(null)
  }

  override def dispose(): Unit = {
    device.release()
    super.dispose()
  }

  override def save(nbt: NBTTagCompound): Unit = throw new UnsupportedOperationException("S10: no persistence; M1")

  // ---- What the tests read

  /** `computer.started` messages from the neighbouring machine so far. */
  def machineStarts: Int = starts.get

  /** `computer.stopped` messages so far: the machine is closed when this has caught up with [[machineStarts]]. */
  def machineStops: Int = stops.get

  /**
    * Per frame status, in order: `"sent <status> <frameId>"`, `"refused <status> <frameId>"` (a full queue or a
    * stopping machine) or `"unsent <status> <frameId>"` (no Context since the last start, stop or disconnect).
    */
  def frameSignals: Seq[String] = signals.synchronized(signals.toList)

  override def toString: String = s"OpenGpuEntity[$key, T$tier, $host]"

  private def touch(context: Context): Unit = this.context.set(context)

  private def call(context: Context)(f: CallBudget => CallResult): Array[AnyRef] = {
    touch(context)
    try f(budgets(context, charges)).toLua()
    catch {
      case _: LimitReached =>
        charges.twoPhase()
        throw new LimitReachedException()
    }
  }

  private def signal(frameId: Long, status: String): Unit = {
    val c = context.get
    val outcome =
      if (c == null) "unsent"
      else if (c.signal("opengpu_frame", node.address, java.lang.Long.valueOf(frameId), status)) "sent"
      else "refused"
    signals.synchronized(signals += s"$outcome $status $frameId")
  }
}
