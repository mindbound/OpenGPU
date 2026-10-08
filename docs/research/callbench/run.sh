#!/bin/sh
# callbench driver -- compile once, then run the (budget x arch x jit) matrix,
# one fresh machine per workload inside each JVM.
# Modelled on OC-LuaJIT/test/native/smoke-test.sh steps 1-4 (additive arm).
set -u
S=/c/Users/astro/AppData/Local/Temp/claude/C--Users-astro-Downloads-OpenGPU/170a14cc-5cc1-40e0-a7ec-6130b748eabf/scratchpad/callbench
BRAIN=/c/Users/astro/Downloads/ocelot-brain
OCLJ=/c/Users/astro/Downloads/OC-LuaJIT
JDK8="/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot"
JDK17="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
: "${RUN_JDK:=$JDK8}"
: "${STEP:=all}"
: "${ARMS:=52:on 53:on 54:on luajit:on luajit:off}"
: "${BUDGETS:=default gtnh}"
: "${WORKLOADS:=all}"
: "${TAGPFX:=}"
: "${RAMSTICKS:=2}"
: "${CPUTIER:=3}"
: "${TOTAL:=100000}"

w() { cygpath -w "$1"; }
wm() { cygpath -m "$1"; }
SEP=';'
say() { echo "[callbench] $*"; }
fail() { echo "CALLBENCH FAIL: $*" >&2; exit 1; }

CP="$(w $BRAIN/target/classes)$SEP$(w $BRAIN/src/main/resources)"
for j in "$S"/lib/*.jar; do
  case "$(basename "$j")" in scala-compiler*|scala-reflect*|scala-asm*) continue;; esac
  CP="$CP$SEP$(w "$j")"
done
SCALAC_CP="$(w $S/lib/scala-compiler-2.13.11.jar)$SEP$(w $S/lib/scala-reflect-2.13.11.jar)$SEP$(w $S/lib/scala-library-2.13.11.jar)$SEP$(w $S/lib/scala-asm-9.5.0-scala-1.jar)"

if [ "$STEP" = all ] || [ "$STEP" = build ]; then
  say "=== compile (JDK 8 javac, scalac 2.13.11 on JDK 8 -> Java 8 bytecode) ==="
  rm -rf "$S/classes"; mkdir -p "$S/classes" "$S/work" "$S/logs"
  "$JDK8/bin/javac" -nowarn -cp "$CP" -d "$(w $S/classes)" "$(w $OCLJ/src/main/java/li/cil/repack/com/naef/jnlua/LuaStateLuaJIT.java)" > "$S/work/javac.log" 2>&1 || { cat "$S/work/javac.log"; fail "javac"; }
  [ -f "$S/classes/li/cil/repack/com/naef/jnlua/LuaStateLuaJIT\$LuaDebug.class" ] || fail "no LuaStateLuaJIT\$LuaDebug"
  "$JDK8/bin/java" -Xmx2g -cp "$SCALAC_CP" scala.tools.nsc.Main -classpath "$(w $S/classes)$SEP$CP" -d "$(w $S/classes)" \
    "$(w $OCLJ/test/native/OcljArch.scala)" "$(w $S/src/Bench.scala)" > "$S/work/scalac.log" 2>&1 || { grep -E "error" -A 3 "$S/work/scalac.log" | head -60; fail "scalac"; }
  [ -f "$S/classes/callbench/Main.class" ] || fail "no callbench.Main"
  [ -f "$S/classes/ocljit/arch/OCLuaJITArchitecture.class" ] || fail "no OCLuaJITArchitecture"
  say "compiled: $(find $S/classes -name '*.class' | wc -l) classes"

  say "=== kernel: patch ocelot-brain's machine.lua into OUR resource domain (additive arm) ==="
  KDIR="$S/classes/assets/ocluajit/lua"; mkdir -p "$KDIR"
  "$OCLJ/build/native/luajit-windows-x86_64/src/luajit.exe" "$OCLJ/native/kernel/patch-machine-lua.lua" \
    "$BRAIN/src/main/resources/assets/opencomputers/lua/machine.lua" "$KDIR/machine.lua" || fail "kernel patcher refused"
  say "kernel: $(wc -c < "$KDIR/machine.lua") bytes at $KDIR/machine.lua"

  say "=== stage the additive native (only the windows DLL) ==="
  rm -rf "$S/stage"; mkdir -p "$S/stage"
  cp "$OCLJ/build/native/libdir-additive/libjnluajit52-windows-x86_64.dll" "$S/stage/" || fail "no additive DLL"
  [ "$(ls "$S/stage" | wc -l)" = 1 ] || fail "stage must hold exactly one file"
  sha256sum "$S/stage/libjnluajit52-windows-x86_64.dll"

  say "=== configs ==="
  for b in default gtnh; do
    case $b in default) LIST="0.5, 1.0, 1.5";; gtnh) LIST="1, 2, 4";; esac
    CONF="$S/work/ocelot-$b.conf"
    cp "$BRAIN/src/main/resources/application.conf" "$CONF"
    {
      echo ""
      echo "# ---- appended by callbench/run.sh ----"
      echo "opencomputers.debug.forceNativeLibPathFirst = \"$(wm $S/stage)\""
      echo "opencomputers.computer.lua.allowBytecode = false"
      echo "opencomputers.computer.lua.ramScaleFor64Bit = 1.8"
      echo "opencomputers.computer.callBudgets = [$LIST]"
    } >> "$CONF"
    say "conf-$b: callBudgets=[$LIST]"
  done
fi

if [ "$STEP" = all ] || [ "$STEP" = run ]; then
  JV=$("$RUN_JDK/bin/java" -version 2>&1 | head -1)
  say "=== run matrix on $JV ==="
  cd "$S/work" || fail "no work dir"
  for b in $BUDGETS; do
    for arm in $ARMS; do
      a=${arm%%:*}; j=${arm##*:}
      tag="${TAGPFX}$a-jit$j-$b"
      say "--- $tag"
      "$RUN_JDK/bin/java" -Xmx1g -Dlog4j2.level=WARN -cp "$(w $S/classes)$SEP$CP" callbench.Main \
        "$(wm $S/work/ocelot-$b.conf)" "$a" "$(wm $S/src/bench.lua)" "$WORKLOADS" "$j" "$tag" "$RAMSTICKS" "$CPUTIER" "$TOTAL" > "$S/logs/$tag.log" 2>&1
      rc=$?
      grep -E "^BENCH\| (U |L |FINGERPRINT|END|!!|FATAL|budget |jit )" "$S/logs/$tag.log"
      say "exit=$rc log=$S/logs/$tag.log"
    done
  done
fi
