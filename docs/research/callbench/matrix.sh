#!/bin/sh
# The whole measurement campaign, serial so no run competes with another.
S=/c/Users/astro/AppData/Local/Temp/claude/C--Users-astro-Downloads-OpenGPU/170a14cc-5cc1-40e0-a7ec-6130b748eabf/scratchpad/callbench
JDK17="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
cd "$S" || exit 1
echo "=== campaign start $(date -Iseconds)"
# 1. main matrix: JDK 8 (the instance's Java), T3 CPU + 2x T3.5 RAM, both budgets, all five arms, all workloads
STEP=run TAGPFX="m8-" sh run.sh
# 2. JDK 17 cross-check (GTNH packs run 17-21): gtnh budget, all arms, all workloads
STEP=run RUN_JDK="$JDK17" BUDGETS="gtnh" TAGPFX="m17-" sh run.sh
# 3. T1 machine (T1 CPU + 1x T1 RAM): the budget stall at the bottom tier, both budgets, 53 + luajit, limited workloads only
STEP=run CPUTIER=1 RAMSTICKS=1 ARMS="53:on luajit:on" WORKLOADS="invoke_noop,lim_noop256,lim_echo256,sync_noop" TAGPFX="t1-" sh run.sh
# 4. repeat the scalar rows once more on JDK 8 (gtnh), to see run-to-run drift
STEP=run BUDGETS="gtnh" WORKLOADS="invoke_noop,invoke_add,echo_100KB,echoTable_1000" TAGPFX="rep-" sh run.sh
echo "=== campaign end $(date -Iseconds)"
