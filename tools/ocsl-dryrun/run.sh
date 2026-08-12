#!/usr/bin/env bash
# Re-run the OCSL GLSL-120 codegen dry run: drive OpenGPU's four hand-compiled programs through
# Angelica's REAL CompatShaderTransformer, then validate the output with glslang.
#
#   ANGELICA=/c/Users/you/Downloads/Angelica ./run.sh            # 330 core (the GL backends)
#   ANGELICA=... TARGET=460 ./run.sh                             # 460 core (2.2.x SDL GPU)
#   ANGELICA=... GLSLANG=/c/glslang/bin/glslang.exe ./run.sh
#
# Requires: a BUILT Angelica clone (./gradlew :glsm:classes in it) and a JDK 21+ on PATH, because
# Angelica's glsm classes are class-file version 65. Note that is a fact about the DEV clone, not
# about the game -- Minecraft 1.7.10 runs Java 8 and Angelica ships Java 8 bytecode via Jabel.
set -uo pipefail
cd "$(dirname "$0")"

ANGELICA="${ANGELICA:-$HOME/Downloads/Angelica}"
GLSLANG="${GLSLANG:-/c/glslang/bin/glslang.exe}"
TARGET="${TARGET:-330}"
OUT="${OUT:-./build}"

GLSM_CLASSES="$ANGELICA/glsm/build/classes/java/main"
if [ ! -d "$GLSM_CLASSES" ]; then
  echo "FAIL: $GLSM_CLASSES not found."
  echo "  Point ANGELICA= at a clone and build it first:  (cd \"\$ANGELICA\" && ./gradlew :glsm:classes)"
  exit 1
fi

# Find a JDK 21+. NOT negotiable and NOT the system default: Angelica's glsm classes are class-file
# version 65, and this machine's default javac is Java 8 -- which fails with the genuinely
# misleading "wrong version 65.0, should be 52.0". Do not "fix" that by lowering a target; there is
# nothing to lower. (This is a fact about the DEV clone only. The game runs Java 8 and Angelica
# ships Java 8 bytecode via Jabel.)
find_jdk() {
  if [ -n "${JDK:-}" ] && [ -x "$JDK/bin/javac" -o -x "$JDK/bin/javac.exe" ]; then echo "$JDK/bin"; return; fi
  local v
  v=$(javac -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
  if [ -n "$v" ] && [ "$v" -ge 21 ] 2>/dev/null; then echo ""; return; fi   # PATH javac is fine
  for cand in "${GRADLE_USER_HOME:-$HOME/.gradle}"/jdks/*/bin; do
    [ -x "$cand/javac" ] || [ -x "$cand/javac.exe" ] || continue
    v=$("$cand/javac" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
    [ -n "$v" ] && [ "$v" -ge 21 ] 2>/dev/null && { echo "$cand"; return; }
  done
  echo "NONE"
}
JDKBIN=$(find_jdk)
if [ "$JDKBIN" = "NONE" ]; then
  echo "FAIL: no JDK 21+ found (needed to read Angelica's class-file-65 glsm classes)."
  echo "  Set JDK=/path/to/jdk21, or let gradle provision one (it lands in ~/.gradle/jdks)."
  echo "  Found on PATH: $(javac -version 2>&1 | head -1)"
  exit 1
fi
JAVAC="${JDKBIN:+$JDKBIN/}javac"
JAVA="${JDKBIN:+$JDKBIN/}java"
echo "using $("$JAVAC" -version 2>&1 | head -1)"

# The dependency jars live in the gradle cache under content-hashed directories, so they are found
# rather than pinned -- a checked-in absolute path would be one machine's truth and nobody else's.
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
# On Windows, `find` yields POSIX paths (/c/Users/...) that java.exe cannot resolve, and the failure
# is a runtime NoClassDefFoundError long after javac was happy -- so convert to mixed form (C:/...)
# where cygpath exists, and use ';' as the separator there.
winpath() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || echo "$1"; }
SEP=";"; command -v cygpath >/dev/null 2>&1 || SEP=":"
CP="$(winpath "$GLSM_CLASSES")"
missing=0
for artifact in antlr4-runtime glsl-transformation-lib GTNHLib log4j-api lwjgl; do
  jar=$(find "$CACHE" -name "$artifact-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | head -1)
  if [ -z "$jar" ]; then echo "MISSING dependency jar: $artifact"; missing=1; else CP="$CP$SEP$(winpath "$jar")"; fi
done
[ "$missing" = 1 ] && { echo "FAIL: build Angelica once to populate the gradle cache."; exit 1; }

mkdir -p "$OUT/stub"   # javac -d does not create the directory
echo "=== compiling the headless stub backend (target GLSL $TARGET) ==="
# WHY A STUB AT ALL: CompatShaderTransformer reads its target version from
# RENDER_BACKEND.getMinGLSLVersion(), and every real backend needs a GL context. The stub is
# ServiceLoader-registered and reports whatever -Dstub.glsl says, which is also how the same four
# programs get checked against 2.2.x's 460 target without an SDL device.
"$JAVAC" -nowarn -cp "$CP" -d "$OUT/stub" stub/com/gtnewhorizons/angelica/glsm/backend/HeadlessStubBackend.java || exit 1
cp -r stub/META-INF "$OUT/stub/"

echo "=== compiling the harness ==="
"$JAVAC" -nowarn -cp "$CP" -d "$OUT" RunTransform.java || exit 1

echo "=== transforming ==="
cp shaders/dryrun_*.frag "$OUT/"
"$JAVA" -Dstub.glsl="$TARGET" -cp "$(winpath "$OUT")$SEP$(winpath "$OUT/stub")$SEP$CP" RunTransform "$OUT" || exit 1

if [ ! -x "$GLSLANG" ] && ! command -v "$GLSLANG" >/dev/null 2>&1; then
  echo; echo "glslang not found at $GLSLANG -- transform ran, validation SKIPPED."
  echo "  Set GLSLANG= to your glslang binary (pinned copy lives at C:\\glslang\\bin\\glslang.exe)."
  exit 0
fi

echo; echo "=== validating with glslang ==="
rc=0
for f in "$OUT"/angelica_*.frag; do
  n=$(basename "$f")
  if out=$("$GLSLANG" -S frag "$f" 2>&1); then
    printf "  GLSL   OK    %s\n" "$n"
  else
    printf "  GLSL   FAIL  %s\n%s\n" "$n" "$(echo "$out" | head -4)"; rc=1
  fi
  # --aml matches SpirvCompiler.Options.vulkanForced460Core()'s autoMapLocations=true. WITHOUT it
  # all four fail on Angelica's OWN injected angelica_currentAlphaTest uniform, not on anything of
  # ours -- so this flag is load-bearing to the result, not a convenience.
  if out=$("$GLSLANG" -G --aml -S frag "$f" -o "$f.spv" 2>&1); then
    printf "  SPIRV  OK    %s\n" "$n"
  else
    printf "  SPIRV  FAIL  %s\n%s\n" "$n" "$(echo "$out" | head -4)"; rc=1
  fi
done

echo
[ $rc = 0 ] && echo "RESULT: all four programs survive the transformer at GLSL $TARGET." \
            || echo "RESULT: FAILURES above -- see docs/dev/OCSL-GLSL120-DRYRUN.md for the baseline."
exit $rc
