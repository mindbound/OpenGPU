#!/usr/bin/env bash
# tools/check-installed-versions.sh -- what is INSTALLED in the game versus what the clones and
# this checkout say. Every "read the source" this project does is only as good as the answer to
# "which source?", and the answer has been wrong repeatedly: the Angelica clone at 2.2.8 against
# an installed 2.2.11, the OpenComputers clone at 1.12.55 against an installed 1.12.61, and jars
# in mods/ three commits behind HEAD (docs/dev/CASEBOOK.md D12; memory
# read-the-shipped-version-not-the-checkout). This prints the three pairs side by side and exits
# non-zero on any mismatch, so it can gate a field test.
#
#   tools/check-installed-versions.sh            # table + exit 0/1
#   MC_MODS=... OC_CLONE=... ANGELICA_CLONE=...  # override the defaults below
#
# It does NOT fetch or check anything out. A mismatch is a fact to act on by hand: move the clone
# to the installed tag, or rebuild and reinstall the jar, or say "read at <ref>" in the record.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
MC_MODS="${MC_MODS:-D:/Minecraft/instances/Main/minecraft/mods}"
OC_CLONE="${OC_CLONE:-$REPO/../OpenComputers-GTNH}"
ANGELICA_CLONE="${ANGELICA_CLONE:-$REPO/../Angelica}"

status=0
row() { printf "%-14s %-26s %-26s %s\n" "$1" "$2" "$3" "$4"; }

# Installed version from the jar's file name. One jar per mod is the assumption; more than one
# is itself a finding (a stale duplicate loads unpredictably), so every match is listed.
installed() {   # $1 = glob under MC_MODS, $2 = sed expression that extracts the version
  local found=""
  for f in "$MC_MODS"/$1; do
    [ -e "$f" ] || continue
    local v
    v="$(basename "$f" | sed -E "$2")"
    found="${found:+$found,}$v"
  done
  printf "%s" "${found:-<none>}"
}

describe() {    # $1 = git dir
  if [ -d "$1/.git" ] || git -C "$1" rev-parse --git-dir >/dev/null 2>&1; then
    git -C "$1" describe --tags --always --dirty 2>/dev/null || printf "<no describe>"
  else
    printf "<no clone>"
  fi
}

compare() {     # $1 = label, $2 = installed, $3 = reference, $4 = what the reference is
  if [ "$2" = "$3" ]; then
    row "$1" "$2" "$3" "ok ($4)"
  else
    row "$1" "$2" "$3" "MISMATCH ($4)"
    status=1
  fi
}

echo "installed under: $MC_MODS"
row "mod" "installed" "reference" "verdict"
row "---" "---------" "---------" "-------"

# OpenComputers: OpenComputers-1.12.61-GTNH.jar -> 1.12.61-GTNH ; clone tag 1.12.55-GTNH
compare "OpenComputers" \
  "$(installed 'OpenComputers-*.jar' 's/^OpenComputers-(.*)\.jar$/\1/')" \
  "$(describe "$OC_CLONE")" "clone $OC_CLONE"

# Angelica: angelica-2.2.11.jar -> 2.2.11 ; clone describe 2.2.8
compare "Angelica" \
  "$(installed 'angelica-*.jar' 's/^angelica-(.*)\.jar$/\1/')" \
  "$(describe "$ANGELICA_CLONE")" "clone $ANGELICA_CLONE"

# OpenGPU: OpenGPU-mc1.7.10-0.1.0-85-gab0cbbf.jar -> 0.1.0-85-gab0cbbf ; HEAD describe
compare "OpenGPU" \
  "$(installed 'OpenGPU-*.jar' 's/^OpenGPU-mc1\.7\.10-(.*)\.jar$/\1/')" \
  "$(describe "$REPO")" "HEAD of $REPO"

# The newest built jar, so "built but not installed" is visible too.
newest="$(ls -t "$REPO"/build/libs/OpenGPU-mc1.7.10-*.jar 2>/dev/null | grep -v -- '-dev\.jar$\|-sources\.jar$' | head -1)"
if [ -n "$newest" ]; then
  row "  build/libs" "$(basename "$newest" | sed -E 's/^OpenGPU-mc1\.7\.10-(.*)\.jar$/\1/')" "" "(newest built jar, for reference)"
fi

if [ "$status" -ne 0 ]; then
  echo
  echo "MISMATCH: read the clone at its ref and NAME the ref in the record, or move the clone;"
  echo "for OpenGPU, rebuild and reinstall before any field test (S0b would discard the run)."
fi
exit "$status"
