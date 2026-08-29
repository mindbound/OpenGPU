#!/usr/bin/env bash
# Re-run the interpolation cadence measurement: four delay policies over a range of node
# cadences, scored on freeze / surge / backward-step / spread.
#
#   ./run.sh
#   JDK=/c/Program\ Files/Eclipse\ Adoptium/jdk-8.0.462.8-hotspot ./run.sh
#
# The written analysis is ../../docs/dev/INTERPOLATION-DELAY-MATH.md; this is the part of it a
# machine can re-run. It compiles the REAL NodeInterpolator / ServerTimeline / NodeFold from
# src/main/java -- no stubs, no copies -- so the numbers move if those classes move.
#
# Requires only a JDK on PATH (any 8+). It does NOT need gradle, Forge, Minecraft or a GL context:
# the classes it touches are pure arithmetic over the shared scene model.
#
# IMPORTANT: compile with -encoding UTF-8. Several sources contain em-dashes and the platform
# default on Windows is cp1252, which fails the build with "unmappable character".
set -uo pipefail
cd "$(dirname "$0")"

# WINDOWS PATH, not the POSIX one. Under Git Bash `pwd` yields /c/Users/... which the Windows
# javac cannot resolve -- it reports "package opengpu.v2.scene does not exist", which reads like a
# missing dependency rather than a path problem. cygpath -m gives C:/Users/... which javac accepts
# and which is still safe inside double quotes.
REPO="$(cd ../.. && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  REPO="$(cygpath -m "$REPO")"
fi
OUT="${OUT:-./build}"
JAVAC="javac"
JAVA="java"
if [ -n "${JDK:-}" ]; then
  JAVAC="$JDK/bin/javac"
  JAVA="$JDK/bin/java"
fi

if ! command -v "$JAVAC" >/dev/null 2>&1 && [ ! -x "$JAVAC" ]; then
  echo "FAIL: no javac. Put a JDK on PATH or set JDK=/path/to/jdk"
  echo "  (note D:/Minecraft/java/eclipse_temurin_jre21.0.8+9 is a JRE -- no javac in it)"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVAC" -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath ".;$REPO/src/main/java" \
  CadenceProbe.java || {
    echo "FAIL: compile error. If it is 'unmappable character', the -encoding flag was dropped."
    exit 1
  }

"$JAVA" -cp "$OUT" opengpu.v2.mc.client.render.CadenceProbe
