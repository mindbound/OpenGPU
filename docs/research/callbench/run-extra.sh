#!/bin/sh
# extra runs after the campaign: bare JNI upcall floor, yield cadence, and the table-proxy leak vs Java GC cadence
S=/c/Users/astro/AppData/Local/Temp/claude/C--Users-astro-Downloads-OpenGPU/170a14cc-5cc1-40e0-a7ec-6130b748eabf/scratchpad/callbench
BRAIN=/c/Users/astro/Downloads/ocelot-brain
JDK8="/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot"
: "${RUN_JDK:=$JDK8}"
: "${JVM_OPTS:=-Xmx1g}"
: "${ARMS:=52:on 53:on 54:on luajit:on luajit:off}"
: "${BUDGETS:=gtnh}"
: "${WORKLOADS:=jni_uptime,jni_freemem,yield0}"
: "${TAGPFX:=x-}"
: "${RAMSTICKS:=2}"
: "${CPUTIER:=3}"
: "${TOTAL:=100000}"
w() { cygpath -w "$1"; }
wm() { cygpath -m "$1"; }
SEP=';'
CP="$(w $BRAIN/target/classes)$SEP$(w $BRAIN/src/main/resources)"
for j in "$S"/lib/*.jar; do
  case "$(basename "$j")" in scala-compiler*|scala-reflect*|scala-asm*) continue;; esac
  CP="$CP$SEP$(w "$j")"
done
cd "$S/work" || exit 1
for b in $BUDGETS; do
  for arm in $ARMS; do
    a=${arm%%:*}; j=${arm##*:}
    tag="${TAGPFX}$a-jit$j-$b"
    echo "[extra] --- $tag ($JVM_OPTS)"
    "$RUN_JDK/bin/java" $JVM_OPTS -Dlog4j2.level=WARN -cp "$(w $S/classes)$SEP$CP" callbench.Main \
      "$(wm $S/work/ocelot-$b.conf)" "$a" "$(wm $S/src/bench.lua)" "$WORKLOADS" "$j" "$tag" "$RAMSTICKS" "$CPUTIER" "$TOTAL" > "$S/logs/$tag.log" 2>&1
    echo "[extra] exit=$? log=$S/logs/$tag.log"
    grep -E "^BENCH\| (U |L |!!|FATAL)" "$S/logs/$tag.log" | cut -c1-300
  done
done
