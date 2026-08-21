#!/usr/bin/env bash
#
# build-jar.sh — build the mod jar and VERIFY it, because BUILD SUCCESSFUL is not a loadable jar.
#
# Encodes the checks that were being done by hand every time, which is exactly the kind of
# obligation that gets skipped on the one build where it mattered:
#
#   1. tree state      — a dirty tree produces a `-dirty` jar; you should know before installing
#   2. one gradle      — WARNS if several java processes are up; it cannot tell an idle daemon
#                        from a running build, so this is a nudge, not a guard. Honouring
#                        "one gradle at a time" is still yours.
#   3. the build       — full `build` (tests included) unless you opt out loudly
#   4. archive         — `unzip -t` on the produced jar
#   5. provenance      — the jar's embedded git describe must match HEAD
#   6. contents        — a named class must actually be inside (catches an empty/partial jar)
#   7. housekeeping    — lists superseded jars, because two jars differing only by suffix is how
#                        a stale one ends up in the mods folder
#
# Usage:
#   tools/build-jar.sh              full build with tests, then verify
#   tools/build-jar.sh --fast       skip tests (jar only) — prints what that forfeits
#   tools/build-jar.sh --clean      remove superseded jars from build/libs after a good build
#
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

FAST=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --fast)  FAST=1 ;;
    --clean) CLEAN=1 ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)"; exit 2 ;;
  esac
done

say()  { printf '\n=== %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*"; exit 1; }

# ---------------------------------------------------------------- 1. tree state
say "tree"
DIRTY="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
HEAD_SHORT="$(git rev-parse --short HEAD 2>/dev/null)" || fail "not a git repo"
if [ "$DIRTY" != "0" ]; then
  echo "  WARNING: $DIRTY uncommitted path(s) — the jar will be stamped -dirty and its name"
  echo "           will NOT identify a commit. Fine for a scratch build, not for installing."
  git status --short | sed 's/^/    /' | head -12
else
  echo "  clean at $HEAD_SHORT"
fi

# ---------------------------------------------------------------- 2. one gradle at a time
if command -v tasklist >/dev/null 2>&1; then
  RUNNING="$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c "java.exe" || true)"
  # A daemon idles as a java.exe too, so this only warns; it cannot distinguish idle from busy.
  if [ "${RUNNING:-0}" -gt 3 ]; then
    echo "  note: $RUNNING java processes running — if a build is already in flight, stop here."
  fi
fi

# ---------------------------------------------------------------- 3. the build
# PREFERS the known JDK 21 over whatever JAVA_HOME happens to be, and that is the fix for a
# real defect in the first version of this script: this machine's ambient JAVA_HOME is
# jdk1.8.0_211 (the JVM the GAME runs), the fallback only applied when JAVA_HOME was UNSET, so
# the script built under Java 8 and announced it without complaint. The build survived — a
# warm daemon or a toolchain almost certainly covered for it — which is exactly why it would
# have gone unnoticed until the day it did not.
BUILD_JDK="D:/Minecraft/java/eclipse_temurin_jre21.0.8+9"
if [ -x "$BUILD_JDK/bin/java" ]; then
  export JAVA_HOME="$BUILD_JDK"
elif [ -n "${JAVA_HOME:-}" ]; then
  echo "  note: the known build JDK is missing; falling back to the ambient JAVA_HOME"
else
  fail "no build JDK found (build needs 21; the game runs 8)"
fi
JAVA_VER="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "  JAVA_HOME=$JAVA_HOME"
echo "  $JAVA_VER"
case "$JAVA_VER" in
  *'"21'*|*'"2'[2-9]*) : ;;
  *) echo "  WARNING: this is not a JDK 21. The build targets 21 (the game runs 8) — if the"
     echo "           build succeeds anyway it is a warm daemon or a toolchain covering for it." ;;
esac

if [ "$FAST" = "1" ]; then
  say "building (--fast: jar only)"
  echo "  FORFEITS the test verdict. A jar that builds is not a jar that works, and this"
  echo "  path produces no fresh test XMLs — do not report a green suite after using it."
  TASK="jar"
else
  say "building (full, tests included)"
  TASK="build"
fi

./gradlew "$TASK" 2>&1 | tail -15
# ${PIPESTATUS[0]} is gradle's own status; $? here would be tail's.
[ "${PIPESTATUS[0]}" = "0" ] || fail "gradle $TASK failed (see output above)"

# ---------------------------------------------------------------- 4-6. verify the artifact
say "artifact"
# The shipping jar is the one WITHOUT -dev/-sources: those are the deobfuscated and source jars.
JAR="$(ls -t build/libs/*.jar 2>/dev/null | grep -v -- '-dev\.jar$' | grep -v -- '-sources\.jar$' | head -1)"
[ -n "$JAR" ] || fail "no jar in build/libs"
echo "  $JAR"
echo "  $(stat -c '%s bytes, built %y' "$JAR")"

unzip -t "$JAR" >/dev/null 2>&1 || fail "archive is corrupt (unzip -t)"
echo "  archive OK"

# DIRTY IS TESTED FIRST, and the order is the whole point: a -dirty jar's name still CONTAINS
# the head hash (`...-g0d8a36c-dirty.jar`), so a hash-first case matches it and prints
# "provenance OK" over a jar built from uncommitted code. The first version of this script did
# exactly that, and its `*dirty*` arm was unreachable for every jar this build can produce.
case "$JAR" in
  *dirty*)
    echo "  provenance: -DIRTY — built from uncommitted code; the name identifies no commit."
    echo "              Do not install this if you need to know later what it was built from."
    ;;
  *"$HEAD_SHORT"*)
    echo "  provenance OK — name carries $HEAD_SHORT and the tree was clean"
    ;;
  *)
    fail "jar name carries neither HEAD ($HEAD_SHORT) nor -dirty — stale build/libs?"
    ;;
esac

PROBE="opengpu/v2/mc/client/render/AnimatorOverlay.class"
unzip -l "$JAR" 2>/dev/null | grep -q "$PROBE" \
  || fail "jar does not contain $PROBE — partial or misassembled"
CLASSES="$(unzip -l "$JAR" 2>/dev/null | grep -c 'opengpu/v2/.*\.class' || true)"
echo "  contents OK — $CLASSES opengpu/v2 classes, probe class present"

# ---------------------------------------------------------------- 7. housekeeping
OTHERS="$(ls build/libs/*.jar 2>/dev/null | grep -v -- '-dev\.jar$' | grep -v -- '-sources\.jar$' | grep -v -F "$JAR" || true)"
if [ -n "$OTHERS" ]; then
  say "superseded jars"
  echo "$OTHERS" | sed 's/^/    /'
  if [ "$CLEAN" = "1" ]; then
    echo "$OTHERS" | while read -r old; do rm -f "$old" "${old%.jar}-dev.jar" "${old%.jar}-sources.jar"; done
    echo "  removed (--clean)"
  else
    echo "  two jars differing only by suffix is how a stale one reaches the mods folder."
    echo "  re-run with --clean to remove them."
  fi
fi

say "INSTALL THIS"
printf '  %s\n\n' "$JAR"
