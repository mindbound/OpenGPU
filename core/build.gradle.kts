// The engine core (24 §3.1): host-neutral, no Minecraft, OpenComputers or LWJGL; shaded into the mod jar unrelocated.
plugins {
    id("opengpu.java8")
}

VerifyTestsRan.register(project, tasks.test, true)
