package io.github.mindbound.opengpu.ocelot

import java.util.Collections
import java.util.concurrent.Callable

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.SurfaceDesc
import io.github.mindbound.opengpu.core.fixtures.device.{ShadowOracle, TeeRenderHost}
import io.github.mindbound.opengpu.core.font.GlyphTables
import io.github.mindbound.opengpu.core.render.{DeviceKey, ReadbackSink, Record, RenderHost}
import io.github.mindbound.opengpu.gltestkit.GlThread
import io.github.mindbound.opengpu.gltestkit.host.{GlHygiene, GlTestHost}

/** One readback a host delivered to a device: its epoch, its frame id and a copy of its pixels (28 §5.7's layout). */
final case class Delivered(epoch: Long, frameId: Long, pixels: Array[Byte]) {
  override def toString: String = s"Delivered[e$epoch #$frameId, ${pixels.length} B]"
}

/**
  * A host that hands every call to `primary` and keeps a copy of every readback the primary delivers to a device,
  * keyed by epoch and frame id, before it passes the readback on: "the bytes GlTestHost read from the FBO" (23:186;
  * S10 plan P11.3). Its lock is a leaf that only its own methods take, so it adds no lock the primary or a device
  * could meet (28 §2.4's contract).
  */
final class CapturingHost(primary: RenderHost) extends RenderHost {
  private val delivered = mutable.LinkedHashMap.empty[DeviceKey, mutable.ArrayBuffer[Delivered]]

  override def available(): Boolean = primary.available()

  override def stalled(): Boolean = primary.stalled()

  override def open(key: DeviceKey, surface: SurfaceDesc, sink: ReadbackSink): Unit = {
    delivered.synchronized(delivered.getOrElseUpdate(key, mutable.ArrayBuffer.empty))
    primary.open(key, surface, new ReadbackSink {
      override def onReadback(session: Long, deviceId: Int, epoch: Long, frameId: Long, pixels: Array[Byte]): Unit = {
        val copy = Delivered(epoch, frameId, pixels.clone())
        delivered.synchronized(delivered(key) += copy)
        sink.onReadback(session, deviceId, epoch, frameId, pixels)
      }

      override def toString: String = s"CapturingHost's sink of $key before $sink"
    })
  }

  override def offer(key: DeviceKey, record: Record): Unit = primary.offer(key, record)

  override def release(key: DeviceKey): Unit = primary.release(key)

  override def bindDisplay(displayId: String, key: DeviceKey): Unit = primary.bindDisplay(displayId, key)

  override def releaseSession(session: Long): Unit = primary.releaseSession(session)

  /** The keys opened through this host, in open order. */
  def keys: Seq[DeviceKey] = delivered.synchronized(delivered.keys.toList)

  /** What the primary delivered to the key, in delivery order. */
  def readbacks(key: DeviceKey): Seq[Delivered] =
    delivered.synchronized(delivered.get(key).map(_.toList).getOrElse(Nil))

  /** Whether a readback of `epoch` at or past `frameId` has reached the key's device: 23:188's arrival. */
  def arrived(key: DeviceKey, epoch: Long, frameId: Long): Boolean =
    readbacks(key).exists(d => d.epoch == epoch && d.frameId >= frameId)

  override def toString: String = s"CapturingHost[$primary]"
}

/**
  * One rig's GL host (S10 plan P11.3): a GlTestHost with font 0's glyph table on the JVM's COMPAT context, which the
  * ocelot GL legs open (`@GlTest`), pumped by the rig's ticker, [[Cadence.pumpsPerTick]] frames a tick; behind a
  * TeeRenderHost whose ShadowOracle checks every readback the host delivers against the tier-E oracle (28's S8
  * fixtures); behind a [[CapturingHost]], the device's host. Every GL entry point is followed by a hygiene check
  * against GlHygiene's MC-like start, as in S8's scenarios [proposal]: cheap insurance that the host behaves under a
  * hostile start. The failures of the tee and the hygiene checks share one list.
  *
  * [[close]] runs after the device's release: it pumps until the released device's GL objects are gone, closes the
  * host, which asserts an empty ledger (24:218), checks and undoes the start, and fails with every failure recorded.
  */
final class GlRigHost(gl: GlThread, val name: String) {
  val failures: java.util.List[String] = Collections.synchronizedList(new java.util.ArrayList[String]())
  private val initial = GlRigHost.onGl(gl)(GlHygiene.snapshot())
  private val start = GlRigHost.onGl(gl)(GlHygiene.mcLikeStart())
  val glHost: GlTestHost = new GlTestHost(gl, GlyphTables.font0()).checkHygiene(start, failures, name)
  val capture: CapturingHost =
    new CapturingHost(new TeeRenderHost(glHost, new ShadowOracle(GlyphTables.font0()), failures))
  private var closed = false

  /** One host frame: a START pass on the GL thread, then the hygiene check there. */
  def pump(): Unit = glHost.pumpFrame()

  /** The failures so far, a copy. */
  def failureList: Seq[String] = failures.synchronized(failures.asScala.toList)

  /**
    * Idempotent; every step runs. The test's own GL state comes back to what it was before the start, so the next
    * test's start and GlContextExtension's error drain see the context's defaults (S8 amendments E).
    *
    * @throws AssertionError naming every failure: the tee's, the hygiene checks', the close's, the start's
    */
  def close(): Unit = if (!closed) {
    closed = true
    try glHost.pumpUntilIdle(capture.keys: _*)
    catch { case e: Throwable => failures.add(s"$name: pumping until idle: $e") }
    try glHost.close()
    catch { case e: Throwable => failures.add(s"$name close: ${e.getMessage}") }
    try {
      val left = GlRigHost.onGl(gl) {
        val found = mutable.ArrayBuffer.empty[String]
        start.diff().asScala.foreach(f => found += s"at the end of the start: $f")
        start.undo()
        GlHygiene.compare(initial).asScala.foreach(f => found += s"after the start's undo: $f")
        found.toList
      }
      left.foreach(f => failures.add(s"$name: $f"))
    } catch { case e: Throwable => failures.add(s"$name: undoing the start: $e") }
    val all = failureList
    if (all.nonEmpty) throw new AssertionError(s"${all.size} GL host failure(s): ${all.mkString("; ")}")
  }

  override def toString: String = s"GlRigHost[$name, $glHost]"
}

object GlRigHost {
  /** Runs work on the GL thread; a checked exception comes back wrapped in an IllegalStateException. */
  def onGl[T](gl: GlThread)(work: => T): T =
    try gl.call(new Callable[T] {
      override def call(): T = work
    })
    catch {
      case e: RuntimeException => throw e
      case e: Error => throw e
      case e: Exception => throw new IllegalStateException(e)
    }
}

/**
  * The GL context of this JVM's leg in its record (S10 plan P9.5; 23 §3.5): GlInfo's renderer, version and vendor,
  * once, and, after the leg's first rig has pumped, the readback glFinish policy its GlBackend resolved for that
  * vendor, which must be what `-Popengpu.gl.readbackFinish` asks (Q-S8-3; GlTestHost.checkReadbackFinish).
  */
object GlLeg {
  private var recorded = false
  private var finishChecked = false

  def record(gl: GlThread): Unit = synchronized {
    if (!recorded) {
      recorded = true
      val info = gl.info()
      Report.line(s"GL ${info.profile}: ${info.renderer}, GL ${info.version}, vendor ${info.vendor}, GLSL " +
        s"${info.glslVersion}, LWJGL ${info.lwjglVersion}; readbacks' glFinish ${GlTestHost.readbackFinish()}")
    }
  }

  /** Once per JVM, after a pump: null, or what differs from what the leg asked. */
  def checkReadbackFinish(gl: GlThread, host: GlTestHost): String = synchronized {
    if (finishChecked) null
    else {
      finishChecked = true
      val mismatch = host.checkReadbackFinish(gl.info().vendor)
      Report.line(s"readback glFinish ${if (host.backend().readbackFinish()) "on" else "off"} " +
        s"(${host.backend().readbackFinishPolicy()}, GL_VENDOR ${host.backend().vendor()})")
      mismatch
    }
  }
}
