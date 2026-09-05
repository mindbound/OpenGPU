# tools/interp-cadence

Reproduces the **interpolation cadence measurement** — the table in
[docs/dev/INTERPOLATION-DELAY-MATH.md](../../docs/dev/INTERPOLATION-DELAY-MATH.md) §8. That document
is the analysis; this directory is the part of it a machine can re-run.

```bash
./run.sh
JDK=/c/Program\ Files/Eclipse\ Adoptium/jdk-8.0.462.8-hotspot ./run.sh
```

Needs only a JDK on PATH (any 8+). No gradle, no Forge, no Minecraft, no GL context — the classes it
touches are pure arithmetic over the shared scene model. Note that
`D:/Minecraft/java/eclipse_temurin_jre21.0.8+9` is a **JRE** and has no `javac`.

## What it measures

Four delay policies, over eight node cadences, on a paced 20 tps server with three display frames
per tick. A *cadence* is the node's keyframe gap sequence in ticks: `[1]` is a program updating
every tick, `[2]` is `os.sleep(0.1)`, `[1,3]` alternates.

| policy | delay `D` for the interval after keyframe *i* |
|---|---|
| `FIXED2` | 2 — what ships (`ServerTimeline.INTERPOLATION_DELAY_TICKS`) |
| `PERSIST` | `G_i`, the gap just observed — a persistence forecast |
| `MAX2` | `max(G_i, G_{i-1})` — the hedge |
| `SLEW` | moves toward `G_i` by at most one tick per keyframe |

Two frozen measures are printed, and **the difference between them is the point**:

- **cont** — the *continuous* frozen fraction: the measure of wall time on which the node does not
  move, in closed form, with no frame grid involved. **Quote this one.**
- **floor** — the forced frozen fraction `Δ/ΣG`, where `Δ = Σ max(0, G_{i+1} − G_i)`. No policy can
  go below it, *including one allowed to see the future*. A policy printing `ON FLOOR` cannot be
  improved on by any delay rule whatsoever.
- **frozen** — fraction of *frames* with a zero step. **At or below `cont`, never above:** a run
  of N still frames yields only N−1 zero steps, so each freeze episode can lose up to one frame to
  the motion bordering it. The two agree exactly in most cells.
- **surge** — largest single-frame step ÷ the constant-speed step; `1.00x` is perfect
- **backward** — frames where the node moves *backwards*; should always be 0
- **sd** — spread of step sizes; `0.00` is perfectly smooth

The last four are scored over the second half of each run, once the clock estimate has settled.

## Reading the output

`FIXED2` on `[1]` is **100% frozen** in continuous measure against a floor of 0: a node updating
every tick does not interpolate at all under the shipped delay — it steps at 20 Hz. That is the
largest single finding in the table, and `PERSIST`, `MAX2` and `SLEW` all take it to 0%.

On alternating cadences the picture inverts, and **not in the way an earlier version of this file
claimed.** It said `PERSIST` on `[1,3]` "scores 41.8% / 4.67x, worse than `FIXED2`'s 33.5% / 2.67x —
gap-tracking loses on accelerating cadences." Both halves were wrong. Both policies sit *exactly* on
the 50% floor there; the frame-metric spread was one part instrument defect (below) and one part
denomination — `PERSIST` spends the same 2-tick budget in **one** episode with a 2-tick jump where
`FIXED2` spends it in **two** episodes with 1-tick jumps, and the frame counter discounts one frame
per episode. Same freeze economy, different shape. Which shape looks worse in a moving picture is a
perceptual question this harness cannot answer.

No policy reaches 0% on any alternating cadence. That is Corollary 1.1: when `G_{i+1} > G_i` the
admissible delay interval `[G_{i+1}, G_i]` is empty, so freezing is forced for *every* policy. The
`floor` column is the aggregate form of the same fact, and `PERSIST` attains it on every cadence
here — `FIXED2` does not, on `[1]`, `[3]` and `[1,1,3]`.

## The validation gate, and why it is not optional

`model()` reimplements `sampleGroup`'s arithmetic so the four policies can be compared without
four copies of `NodeInterpolator`. Before comparing anything it runs the `FIXED2` column against
the **real** `NodeInterpolator` over `2 * GLIDE_MAX_GAP_TICKS` cadences (10 today) and aborts unless they agree frame-for-frame to
1e-6. A model that has drifted from the class produces a table that looks fine and means nothing.

**That gate is necessary and not sufficient, and the difference cost a wrong conclusion.** An
earlier version rendered `FRAMES_PER_TICK * G_i` frames after keyframe *i* — the width of the
interval that just *ended* — when the next keyframe actually arrives `G_{i+1}` ticks later. On a
constant cadence those coincide; on an alternating one it truncated each interval before the freeze
at its tail, and reported `PERSIST` as freeze-free everywhere.

The validation passed throughout, because **both the model and the real-class driver shared the
same frame-count error**. A cross-check between two implementations is blind to any mistake they
have in common. What caught it was the closed form in §3 of the analysis: the algebra says freezing
is unavoidable when gaps grow, so a probe reporting 0% had to be wrong.

**It happened a second time in this same file — and this one a closed form did NOT catch.** Frame
instants were computed as `f * (TICK / FRAMES_PER_TICK)`. `TICK/3` is `16_666_666` — two nanoseconds
short — so the error accumulated until the frame that should land exactly on `alpha == 1` landed
4 ns *inside* the window and reported a step of `4e-6` instead of `0`. The frozen counter therefore
missed the first frame of every episode. Six of the eight cadences moved when it was fixed to
`f * TICK / FRAMES_PER_TICK`, and two reversed their policy ranking: on `[1,2]` `PERSIST` stopped
beating `FIXED2` and tied it, and on `[2,1,2,3]` `FIXED2` went from tied to strictly better.

The validation gate passed throughout — it prints a fixed `worst frame-by-frame divergence < 1e-6`
and emits the number only on failure, so no digit can honestly be quoted for either occasion —
because the model and the real-class driver compute that timestamp with the *same expression*. The
identical blindness, one layer down. What sees it is `continuousFrozen`, which has no frame grid at
all, and the guard in `main`, which now calls `frameNanos` — the same helper both arms use — rather
than comparing two compile-time constants as a first version did.

If you extend this harness, keep the gate — and remember it only pins the *shipped* column, and only
against errors the two sides do not share. To trust a new policy's column, graft that policy into a
copy of the real class and diff it against the model, as `NodeInterpolatorP` did for `PERSIST` in the
session that produced this. **And check every column against `cont`.** Being honest about how the
three defects were actually found, since the temptation is to make the record tidier than it was:
the first by algebra contradicting the picture, the second by an independent reimplementation on an
exact frame grid — a picture contradicting a picture, which is the very move this section warns
about, working — and the third by greping the corrected expression for copies. The transferable
rule is not "algebra always wins"; it is that a cross-check between two implementations proves
only that they agree, so it needs a *derived* column beside it, and `cont` is that column.

## Files

- `CadenceProbe.java` — the harness. Declares `package opengpu.v2.mc.client.render` so it can reach
  the package-private `NodeInterpolator`, `ServerTimeline` and `NodeFold`; it compiles against
  `src/main/java` directly, so the numbers move if those classes move.
- `run.sh` — compile and run. Converts the repo path with `cygpath -m`, because under Git Bash
  `pwd` yields `/c/Users/...` and the Windows `javac` reports that as *"package opengpu.v2.scene
  does not exist"* — a path problem wearing a missing-dependency costume.
- `build/` — output, gitignored and regenerable.
