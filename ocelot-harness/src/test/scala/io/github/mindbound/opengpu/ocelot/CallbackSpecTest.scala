package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.fixtures.device.{DeviceRig, M0Callbacks, M0Replay}
import io.github.mindbound.opengpu.core.render.DeviceKey
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse}
import org.junit.jupiter.api.{Tag, Test, Timeout}
import totoro.ocelot.brain.entity.machine.Callbacks

import scala.jdk.CollectionConverters._

/**
  * OpenGpuEntity's callbacks are `:core`'s M0Callbacks, the spec the root's card is held to as well (S11 plan P15.4;
  * amendments F-1; Q-S11-11): every M0 callback but `bind`, which is the card's alone, as brain's own scan finds them
  * (Callbacks.scala:47-77: public, `(Context, Arguments)`, `Object[]`, on the class or a superclass, named by `value`
  * or the method), each with the spec's `direct`, `limit` and `doc`, a blank `value` and no getter or setter. Brain's
  * annotation has OC's six elements with OC's defaults (S11 api.md §6.5). And `getCaps` gives M0Replay's table, F-1's
  * parity part, as the root's CallbackDispatchParityTest holds the card to it. No boot: the scan reads the class, and
  * `getCaps` needs no machine.
  */
@Tag("ocelot-unit")
class CallbackSpecTest {
  @Test
  @Timeout(30)
  def theEntitysCallbacksAreM0CallbacksButBind(): Unit = {
    val found = Callbacks.fromClass(classOf[OpenGpuEntity])
    val shared = M0Callbacks.shared.asScala.toList
    assertEquals(shared.map(_.name).sorted, found.keys.toList.sorted, "the callbacks brain finds")
    for (spec <- shared) {
      val a = found(spec.name).annotation
      assertEquals(spec.direct, a.direct, s"${spec.name}'s direct")
      assertEquals(spec.limit, a.limit, s"${spec.name}'s limit")
      assertEquals(spec.doc, a.doc, s"${spec.name}'s doc")
      assertEquals("", a.value, s"${spec.name}'s value: the method's name is the Lua name")
      assertFalse(a.getter || a.setter, s"${spec.name} is no getter or setter")
    }
    Report.line(s"CallbackSpecTest: OpenGpuEntity's ${found.size} callbacks are M0Callbacks' but bind, with their " +
      "direct, limit and doc")
  }

  // F-1: getCaps' parity part is Device's 15 keys plus renderer on both sides, the same values of the same classes in
  // the same order; the harness adds only hostRenderer, when its rig gives one (S11 review)
  @Test
  @Timeout(30)
  def theEntitysGetCapsIsM0ReplaysPlusHostRenderer(): Unit = {
    var tables = 0
    for (tier <- Seq(2, 3); fake <- Seq(false, true); hostRenderer <- Seq(None, Some("Test GL 1.0"))) {
      val rig = if (fake) DeviceRig.tier(tier) else DeviceRig.noRenderer(tier)
      val entity = new OpenGpuEntity(tier, rig.renderHost, new DeviceKey(2, 1), rig.folds, rig.clock, hostRenderer)
      val caps = entity.getCaps(null, null)(0).asInstanceOf[java.util.Map[String, AnyRef]].asScala.toList
      val replay = M0Replay.getCaps(rig)
      val what = s"T$tier on ${rig.renderHost.getClass.getSimpleName}, hostRenderer $hostRenderer"
      assertEquals(
        CallbackSpecTest.describe(replay.asScala.toList),
        CallbackSpecTest.describe(caps.filter(e => replay.containsKey(e._1))),
        what)
      assertEquals(
        hostRenderer.map(r => s"hostRenderer=String $r").toList,
        CallbackSpecTest.describe(caps.filterNot(e => replay.containsKey(e._1))),
        s"$what: the harness's own keys")
      tables += 1
    }
    Report.line(s"CallbackSpecTest: OpenGpuEntity's getCaps is M0Replay's plus hostRenderer in $tables tables")
  }
}

object CallbackSpecTest {

  /** Each entry as "key=Class value", so equal lists are equal values of equal classes in the same order. */
  private def describe(entries: List[(String, AnyRef)]): List[String] =
    entries.map { case (k, v) => s"$k=${v.getClass.getSimpleName} $v" }
}
