# 29. Font survey

Writer, 2026-10-14, for owner review; revised the same day after the owner's answer to F1, and on 2026-10-15 after their answers to F4, F5 and F8. **Status: draft. F1 (= 28 Q1) is decided (2026-10-14) and F2 is answered for the deferred text console; F4 and F8 are decided and F5 is deferred to M1 (2026-10-15); F3, F6, F7 and F9 stay open** (§8), and nothing else here is decided until the owner accepts it. The note answers the owner's follow-up to 28 Q1 (28:944): OC's screen font has one size, which suits OC's scaled text mode but not a pixel GPU that draws at native pixels on 160×100 to 640×400. It merges four candidate sweeps (terminal, tiny, display, wide coverage), three measurers (terminal, small, display and wide) and a licence critic whose verdicts override the measurers'. The writer re-ran the hashes of every downloaded file, the atlas costs and three tier mock-ups. mcer12/PixelFonts, the owner's candidate, was evaluated separately (RM:51, :353) and appears here only for comparison.

**Revision (2026-10-14).** The first draft judged fonts partly by criteria of OC's character-cell text screens: an 80×25 grid on T3, 1:2 cells whose ▀▄ half-blocks make square "semi-pixels", box-drawing glyphs that join between cells, and an exact 2× size ladder. On them it proposed Spleen 8×16 as font 0. The owner pointed out that OpenGPU is not a character display: `TEXT` draws a string at any pixel (x, y) (28:469), a cell is only a glyph's box and advance, borders are `FILL_RECT` and lines, and pictures are real pixels. 28's 8×16 cell had come only from OC's `font.hex`, M0's original glyph source, not from a need of the protocol (28:944). This revision drops those four criteria. Grid parity with OC is left to the deferred stock-GPU text console (24:14 decision 8; RM:282), and M1's OpenOS-style terminal showcase (RM:94, :248) need not match OC's sizes (the owner). §§1, 2 and 5–8 are rewritten around the decision; §3 keeps its measurements, with misc-fixed's added.

Citations: `NN:L` is line L of `docs/research/NN-*.md`, and `RM:L` is line L of `docs/roadmap.md`, both in the working tree of 2026-10-14 (28 as amended for Q1 that day; its F4 edits of 2026-10-15 moved no line); `NN §x` is a section. `OCT/` = `C:\Users\astro\Downloads\opengpu-refs\ocgtnh-master\src\main\` (1.12.64-GTNH, as in 28). `FS/` = `C:\Users\astro\Downloads\opengpu-refs\font-survey\`, one folder per candidate, all downloads untrusted data, nothing in them executed. `PV/` = `FS/_previews/`. `SP/` = `font-survey-checks/` next to this note (own scripts and their small outputs, archived from the survey's working folder, MIT with the repo, Python 3 standard library, run as `python -I` with the font files passed as arguments). `PF/` = `C:\Users\astro\Downloads\opengpu-refs\pixelfonts\repo\` (344a6cf), the PixelFonts evaluation's checkout. "OC351" is 28's working set U+0020–007E, U+00A0–00FF, U+2500–259F (28:632). Coverage figures count inked glyphs, not encoded placeholders (§9.3). Tags: **[M]** measured, or read in a primary source; **[I]** inference; **[E]** estimate; **[proposal]** a default for the owner.

## Summary

1. **Text needs several sizes.** OC scales one glyph size with the screen (`OCT/resources/application.conf:28-35`) [M]. OpenGPU draws `TEXT` at native pixels at any (x, y) (28:469), so labels and HUD text need faces of 6–10 px, body text a legible 13-px face, and headings and readouts 18–42 px or integer scale (§1) [M, I].
2. **F1 is decided: font 0 is X11 misc-fixed 6x13** in M0 (the owner, 2026-10-14), shipped as the whole face by 28 §7.1's rule (28:632). It is public domain, has 4 121 glyphs with OC351 351/351, full Greek and Cyrillic and its own U+FFFD, and holds 26×7, 53×15 and 106×30 characters per line × lines on T1–T3. misc-fixed 8x13 stays the alternative until v1 freezes at M1's release (§7) [M].
3. **Recommended family for M1** [proposal, F3]: the rest of misc-fixed by use. That is 4×6, 5×8 and 6×10 for small text, 8×13 for a wider face at font 0's height, and 9×18 and 10×20 for larger monospace, with 12x13ja and 18x18ja as their CJK companions (F7). Add Tiny5 and Tiny5 Duo for proportional HUD text, ANAKRON 8×12 for bold labels and Jersey 10–25 for headings, all OFL (F6), plus integer scale (F8). Spleen 12×24 and 16×32 (BSD-2) are an option for native large monospace (F3). M0 owes only a credit line; M1's OFL faces add one NOTICE section and one file each, and 12x13ja's Hangul adds Baekmuk's notice (§4, §5).
4. **unscii-16 is left for the stock-GPU text console.** OC's look is unscii-16: OC351 in OC's `font.hex` is 350/351 viznut's unscii-16, and the 351st glyph is Unifont's (`SP/terminal/ocprov.py`) [M]. The owner accepts it for the deferred console, whose font is chosen then, so M1 has no `oc16` (§8 F2). unscii-8 still fails the provenance rule: 65/94 ASCII glyphs are pixel-identical to Amiga Topaz-8 [M].
5. **PixelFonts is not recommended.** Its extended-Latin variants cover 128/351 of OC351, with no box drawing (`SP/pixelfonts/c2_sets.out`) [M]. Its README says the extended Latin glyphs other than the Czech ones "are AI generated" (`PF/README.md:14`) [M], which fails the provenance rule. Its ASCII-only variants are hand-drawn, but Jersey and Tiny5 cover its display and HUD roles with wider coverage (§5.1).
6. **`TEXT` needs a font operand, cell geometry per font and an advance rule.** The u8 `font` is decided (F4, 2026-10-15) and is in 28's `TEXT` before S6 builds the codec (28:426; RM:52). The advance rule is deferred to M1 (F5, 2026-10-15): M0 advances one cell per codepoint, and the proposal for M1 stays `wcwidth(cp)` cells, OC's own rule (`OCT/scala/li/cil/oc/util/FontUtils.scala:14`) [proposal]. Validation then needs per-font geometry, never glyph data (§6).
7. **Atlas cost.** M0's font 0 is 321 438 B at 1 B per texel, in one 1 024² page. M1's resident set, ids 0–13, is 3 635 391 B at 1 B per texel or 454 428 B at 1 bit per texel. The CJK companions are paged in on demand. M1's fonts, font 0 included, take about 0.79 MB of the jar deflated, of which font 0 is 24 KB (§5.5) [M].

## 1. Why several sizes

OC draws a screen's text with its 8×16 and 16×16 glyphs on a character grid per tier (`OCT/scala/li/cil/oc/Settings.scala:528`) and scales the result to the screen block. Its config speaks of text "rendered at a resolution lower than their native one" when a screen is scaled down (`OCT/resources/application.conf:28-35`) [M]. One glyph size therefore serves every OC resolution [I].

OpenGPU's surfaces are pixels: T1 160×100 index8 (from M1), T2 320×200 index8, T3 640×400 rgb565 (28:37). `TEXT` draws a string at any pixel (x, y), one glyph box per codepoint at native size (28:469). A font's size therefore decides how much text fits:

| Cell | T1 160×100 | T2 320×200 | T3 640×400 | Example font |
|---|---|---|---|---|
| 4×6 | 40×16 = 640 | 80×33 | 160×66 | misc-fixed 4x6 |
| 5×8 | 32×12 = 384 | 64×25 | 128×50 | misc-fixed 5x8 |
| 6×10 | 26×10 = 260 | 53×20 | 106×40 | misc-fixed 6x10 |
| 6×12 | 26×8 = 208 | 53×16 | 106×33 | misc-fixed 6x12 |
| 6×13 | 26×7 = 182 | 53×15 | 106×30 | misc-fixed 6x13, font 0 |
| 8×13 | 20×7 = 140 | 40×15 | 80×30 | misc-fixed 8x13 |
| 8×16 | 20×6 = 120 | 40×12 | 80×25 | Spleen 8x16, unscii-16 |
| 9×18 | 17×5 = 85 | 35×11 | 71×22 | misc-fixed 9x18 |
| 10×20 | 16×5 = 80 | 32×10 | 64×20 | misc-fixed 10x20 |
| 12×24 | 13×4 | 26×8 | 53×16 | Spleen 12x24 |
| 16×32 | 10×3 | 20×6 | 40×12 | Spleen 16x32 |

[M arithmetic, floor(W/w) × floor(H/h); `SP/report/linefit-f1.out` measures the same for the misc-fixed faces and Spleen 12×24 and 16×32.] One size cannot serve both ends. On a 160×100 HUD, font 0 gives 7 lines of 26 characters, so a denser panel needs 4–10 px faces: 4×6 gives 16 lines of 40. On 640×400, font 0 is one of 30 lines, so headings and readouts need 18–42 px, either from native large faces (9×18, 10×20, Jersey's 20–42 px lines) or from integer scale (§6.1) [I]. Previews at native pixels: `PV/f1-t2-6x13-vs-8x13.png` (T2 at ×2) and `PV/f1-t3-6x13-vs-8x13.png` (T3 at ×1) show font 0 and 8x13 (§7). The first draft's mock-ups `PV/plan-t1-160x100.png` (×4), `PV/plan-t2-320x200.png` (×2) and `PV/plan-t3-640x400.png` (×1) show the small and display faces, with Spleen 8×16 where font 0 now is. All are drawn with `SP/report/tiermock.py`.

## 2. Requirements

- **Licence.** The glyph data must be redistributable inside an MIT mod jar that modpacks such as GTNH ship, after conversion to OpenGPU's format and subsetting, without changing OpenGPU's licence. Accepted: public domain, CC0, MIT, BSD, ISC, zlib, Apache-2.0, OFL 1.1 and CC BY 4.0. GPL and CC BY-SA need analysis (§4). Excluded: non-commercial, no-derivatives, personal-use-only, no licence, and rips of proprietary fonts whatever licence a repackager attaches.
- **Format.** A true bitmap (BDF, PCF, PSF, .hex, a PNG sheet), or a pixel-grid outline whose conversion maps 1:1 to pixels, which must be checked.
- **Provenance.** Named authors, no AI-generated or traced glyphs, a maintained or stable source.
- **Roles.** (A) body text: monospace, legible at 1×, OC351 at least; (B) small: cells 10 px tall or less; (C) display: 12–42 px headings, labels and numbers; (D) wide coverage: CJK and other scripts as a fallback.

## 3. Candidates by role

Verdicts are the licence critic's. "ok-w-c" means ok with conditions (§4, §5.4). Sets: G = Greek letters /49, C = Cyrillic /96, Ar = arrows /112, Br = braille /256, F = U+FFFD present.

**(A) Body text: monospace faces**

| Font | Sizes | Licence → verdict | OC351 | Other sets | Mono | Provenance | Preview |
|---|---|---|---|---|---|---|---|
| X11 misc-fixed (Kuhn ucs-fonts) | 6x12, 6x13, 7x13, 7x14, 8x13, 9x15, 9x18, 10x20 (+B) | public domain → ok | 351; bold faces 207–216 | 6x12, 6x13, 8x13, 9x15, 9x18, 10x20: every set complete; 7x13 and 7x14 G 49, C 96, Ar 100 and 21; F in every face. 6x13: 4 121 glyphs, ascent 11, descent 2; 8x13: 3 703, 11/2; 9x18: 4 766, 14/4; 10x20: 5 205, 16/4; every advance = the cell | yes | X11 originals of unknown authorship; Unicode extension by Kuhn and named contributors (§7) | `misc-fixed-6x13.png`, `-8x13`, `-9x18`, `-10x20`; `f1-t2-6x13-vs-8x13.png`, `f1-t3-6x13-vs-8x13.png` |
| Spleen 2.2.0 | 5x8, 6x12, 8x16, 12x24, 16x32, 32x64 | BSD-2 → ok | 351 at 8x16, 12x24, 16x32, 32x64; 6x12 202; 5x8 107 | 8x16/16x32/32x64: G 12, C 72, Ar 7, Br 256; 12x24: G 0; no F | yes | Frederic Cambus, original; in OpenBSD, NetBSD and FreeBSD base | `spleen-8x16.png`, `-12x24`, `-16x32`, `-32x64` |
| UW ttyp0 2.1 | 6x12, 7x14, 8x16 (b, i), 9x18, 11x22, 14x30, 18x40 (+odd) | ttyp0 licence (MIT plus rename-if-modified) → ok-w-c | 351 at every size | G 49, C 96, Ar 82, Br 256, F | yes | Uwe Waldmann, original since 1992 | `uw-ttyp0-8x16.png`, `-11x22`, `-18x40` |
| Terminus 4.49.1 | 6x12, 8x14, 8x16, 10x18, 10x20, 11x22, 12x24, 14x28, 16x32; bold; CRT-VGA bold 8x14/8x16 | OFL, RFN "Terminus Font" → ok-w-c (rename) | 341 (dashed lines, ▔ ▕ missing) | G 49, C 96, F | yes | Dimitar Zhekov; bold 8x16 shares 21/94 glyphs with IBM VGA, within base rate | `terminus-8x16.png`, `-12x24`, `-16x32b` |
| unscii-16 (viznut 2.1) | 8x16 (+243 16x16) | public domain → ok-w-c; the owner accepts it for the deferred console (§8 F2) | 351 | G 49, C 77, Ar 25, Br 256 (mosaic); no F | yes | Fixedsys Excelsior lineage: 27/94 identical, base rate 21–24; IBM VGA 3/94 | `unscii-8x16.png` |
| OC `font.hex` (baseline) | 8x16 + 16x16 | mixed: unscii PD, asie CC0, Unifont OFL, Shinonome PD → do not ship whole | 351 | everything, CJK 20 992 | yes | OC351 350/351 = unscii-16 | `oc-font-hex-8x16.png` |
| ANAKRON 0.3.3 | 8x12 bold (its 2x, 3x builds are exact scales) | OFL, no RFN → ok-w-c | 351 | G 49, C 96, Ar 106, Br 256; no F, a `.notdef` | yes | molarmanful; 28/94 ASCII shapes match one of nine ROMs, mostly one ROM each (unscii-8: 88/94) | `anakron-8x12.png` |
| Cozette 1.30.0 | 6x13 (12-wide symbols) | MIT, plus Proggy's MIT → ok | 351 | every set, CJK 88 | 6/12 | based on Dina, which is based on Proggy | `cozette-6x13.png` |
| Leggie | 6x12, 9x18, 12x24 | CC BY 4.0 → ok-w-c | 239 | G 49, C 96 | yes | Wiktor Kerr; its 9x14/9x16 "VGA-style" sizes unchecked | `leggie-9x18.png` |
| Tamzen 1.11.6 | 5x9 to 10x20 | permissive → ok | 174 | about 190 glyphs | yes | Scott Fial, Suraj N. Kurapati | `tamzen-8x16.png` |
| PixelFonts 0.1.0 (owner's candidate) | proportional, cap 5–21 | MIT | 128 (ext variants), box 0 | ASCII plus 50 | no | extended Latin "AI generated" (`PF/README.md:14`) | `pixelfonts-extended-latin-13x7-thin.png` |

Sources: the terminal measurer's `fontmeasure.py` runs, re-checked by the critic for Spleen, misc-fixed 5x8, Terminus 16 and ttyp0 16 [M]. ttyp0 counts come from `SP/terminal/ttyp0map.py`, which maps its named-glyph BDFs to 3 770 code points against the README's "about 3800" [M]. The misc-fixed sizes, glyph counts and sets come from `cmdstream-checks/q1_font0.py` (6x13, 8x13), `SP/report/ink.py` on every regular face (OC351, G, C; `ink-f1.out`), and `SP/report/fontmeasure-f1.out` (arrows, braille, F, encoded) [M].

**(B) Small: cells 10 px tall or less**

| Font | Cell | Licence → verdict | OC351 | Other sets | Mono | Notes | Preview |
|---|---|---|---|---|---|---|---|
| misc-fixed 4x6 | 4×6 | PD → ok | 341 (quadrants ▖–▟ missing) | G 49, C 92, Br 0, F | yes | 4x6 by Janne V. Kujala; l/I, n/h, u/v differ by 1 px | `misc-fixed-small-4x6.png` |
| misc-fixed 5x7, 5x8 | 5×7, 5×8 | PD → ok | 351, 351 | G 49, C 92, Br 256, F | yes | the small default | `misc-fixed-small-5x8.png` |
| misc-fixed 6x9, 6x10 | 6×9, 6×10 | PD → ok | 276, 351 | G 49; C 96, 92; 6x9 box 53/128; F | yes | 6x10 is the comfortable small size | `misc-fixed-small-6x9.png`, `misc-fixed-6x10.png` |
| Tiny5 / Tiny5 Duo 2.007 | 5 px cap, line 9 | OFL, no RFN → ok-w-c | 190 (no box, blocks or braille) | Latin Ext-A 128, G 49, C 96; no F | no | Stefan Schmidt; I, l and \| identical, Duo 0 = O. Its line is ascent 7 + descent 2, but 250 glyphs, mostly accented capitals, reach up to 12 rows above the baseline and 112 reach up to 4 below (`SP/report/boxcheck-f1.out`) | `tiny5-5px.png`, `tiny5-duo-5px.png` |
| Tom Thumb | 4×6 | CC0 → ok | 190 | Latin-1 95/96 | yes | Brian Swetland, Robey Pointer | `tom-thumb-4x6.png` |
| Spleen 5x8 | 5×8 | BSD-2 → ok | 107 | its Latin-1 code points are blank | yes | dominated by misc-fixed 5x8 | `spleen-5x8-5x8.png` |
| unscii-8 (+thin, alt, mcr, fantasy) | 8×8 | PD → problematic (provenance) | 351 | as unscii-16 | yes | 65/94 identical to Amiga Topaz-8, 88/94 to at least one ROM | `unscii-8-8x8.png` |
| miniwi | 4×8 | GPL-3 with the font exception → problematic | 351 | G 49, C 94, Br 255 | yes | the exception covers documents only | `miniwi-4x8.png` |
| monogram 1.1.0 | 5×7 glyphs, 6×12 cell | CC0, MIT repo notice → ok-w-c | 189 | Latin Ext-A 127, C 66, G 0 | yes | needs a 10–12 px pitch, so it is a role C face | `monogram-6x12.png` |
| Misaki / k6x8 | 4×8 + 8×8, 3×8 + 6×8 | Little Limit free licence → ok | 170 | Latin-1 15/96, CJK 6 356 | yes | a role D fallback at 8 px | `misaki-k6x8-4x8-gothic.png` |
| funscii 6x8 | 6×8 | CC0 + Little Limit → ok | ≥ 296 (own reader, lower bound) | — | yes | poor fit | `funscii-6x8.png` |

Sources: the small measurer [M] (`SP/small/measure.jsonl`, `SP/small/inked.py`, `SP/small/romcompare.py`); the critic's `SP/critic/cmp8.py` reproduced the unscii-8 figures [M]; the misc-fixed G, C and OC351 figures are `SP/report/ink.py`'s (`ink-f1.out`) [M].

**(C) Display**

| Font | Sizes | Licence → verdict | OC351 | Coverage | Mono | Notes | Preview |
|---|---|---|---|---|---|---|---|
| Jersey 10/15/20/25 | caps 10, 15, 20, 25; lines 20, 27, 34, 42 | OFL, no RFN → ok-w-c | 179 | Latin only, Latin Ext-A 99; no F in the converted tables | no | TTF only; outlines pixel-exact for 10, 15 and 20, and for 25 except U+2014 (`SP/measure-cd/ttfgrid.py`) | `jersey-10.png`, `jersey-20.png` |
| ANAKRON 8x12 | 8×12 bold, ×2–×4 by scaling | OFL → ok-w-c | 351 | see (A) | yes | bold labels and readouts | `anakron-8x12.png` |
| Spleen 16x32, 32x64 | 16×32, 32×64 | BSD-2 → ok | 351 | see (A) | yes | native large monospace (§8 F3 option) | `spleen-32x64.png` |
| M+ BITMAP 2.2.4 (f10, f12, h10, h12, s10, r/b) | 6×13 mono; 10, 12 px proportional | M+ licence → ok | 191 | Latin-1 only | both | plain X11 look | `mplus-bitmap-h12b.png` |
| Retro Pixel Font 2026.09.18 | Thick 16 px, Cute 11 px, Petty 5 px | OFL → ok-w-c; Arcade problematic | 95–104 | no accented letters | mixed | Arcade: 27/36 of A–Z, 0–9 match the Atari arcade ROM (`SP/critic/arcadecheck.py`) | `retro-pixel-font-thick.png` |

Not measured, all outline-only: Pixel Operator, Pixelzone, Public Pixel, Pixeloid, Departure Mono, Silkscreen and Kenney Fonts. `ttfgrid.py` can check any of them [I].

**(D) Wide coverage**

| Font | Cells | Licence → verdict | OC351 | Coverage | Notes | Preview |
|---|---|---|---|---|---|---|
| misc-fixed 12x13ja, 18x18ja, 18x18ko | 12×13, 18×18, full width throughout | PD label → ok-w-c; 12x13ja Hangul needs Baekmuk's notice; 18x18ko problematic | 295, 341, 341 | 12x13ja: kana 179/192, URO 6 356, Hangul 11 172; 18x18ja: kana 179, URO 14 655, Hangul 2 350 (`SP/report/f7-cjk.out`) | companions of 6x13 and 9x18: 12x13ja has 6x13's ascent 11 and descent 2; 18x18ja has ascent 15 and descent 3 against 9x18's 14 and 4 | `combo-miscfixed6x13+12x13ja.png`, `combo-miscfixed9x18+18x18ko.png` |
| GNU Unifont 18.0.01 | 8×16 / 16×16 | OFL or GPL-2+ with the font exception → ok-w-c (OFL option) | 351, F | all of plane 0: URO 20 992, Hangul 11 172, kana 192 | 57 086 plane-0 glyphs; uneven multi-author style | `unifont-8x16-cjk.png`, `combo-spleen8x16+unifont.png` |
| Ark Pixel 12px monospaced 2026.09.25 | 6×12 / 12×12 | OFL, no RFN → ok-w-c | 350 | URO 18 299, kana 189, Hangul 0 | box, arrows and blocks are full width | `ark-pixel-font-12px-cjk.png`, `combo-spleen6x12+ark12.png` |
| M+ j10, j12 | 10×11, 12×13 | M+ → ok | (no ASCII) | URO 6 356, kana 177 | Japanese only | `combo-mplus-f12r+j12r-cjk.png` |
| Misaki 8x8 | 8×8 | ok | 170 | URO 6 356 | the only clean CJK at 8 px | `misaki-k6x8-4x8-gothic.png` |

Excluded by the sweeps on licence or provenance [M, the sweeps' reads]: Tewi, Fixedsys Excelsior, unscii-16-full, Envypn, Termsyn, ctrld, Lemon/Antidote/Uushi, the Linux and kbd console fonts, FreeBSD `vgarom-*`, the Plan 9 Lucida sets, Oldschool PC and hoard-of-bitfonts, Galmuri and Fusion Pixel's Hangul (Nintendo DS), Press Start 2P (Namco), VT323 (DEC), Sixtyfour/Workbench, Zpix, Minecraftia, Monocraft and every Minecraft-derived font, m3x6, 04b03, ZX Origins, Terrarum, naga10.

## 4. Licence findings

The critic's general rules [M: `FS/licence-refs/ofl-faq.html`, `ofl-text.html`, `gpl-faq.html`, `cc-by-sa-4.0-legalcode.html`, `cc-faq.html`]:

1. **OFL conversion and subsetting are modification, and both are allowed.** The definition of a Modified Version includes "changing formats" and deleting glyphs. FAQ 2.2 and 2.6 say neither may keep a Reserved Font Name, and FAQ 1.21 adds "pick your own font name".
2. **OFL inside an MIT jar.** "Only the portions based on the Font Software are required to be released under the OFL" (FAQ 1.3). Selling a package with the fonts, games included, is fine (FAQ 1.4); the font may not be "sold by itself" (condition 1). Each copy carries the copyright notice and the licence, as a file or a readable header (condition 2; FAQ 1.20). OpenGPU's screens, as rendered output, are not covered (FAQ 1.13).
3. **OFL is contagious per font file.** Condition 5 and FAQ 3.2 make any table that holds OFL glyphs OFL as a whole. So keep one file per OFL source, and merge no OFL glyph into another table [I].
4. **Reserved Font Names.** Of the candidates, only Terminus declares one ("Terminus Font"). Unifont's OFL header is the unfilled template. FAQ 5.3 counts names that "specify a font"; a Lua font identifier is one [I]. OpenGPU's font names should therefore be role names, never upstream names (§5.3).
5. **GPL.** The GNU font exception covers "a document which uses this font", unaltered portions only, so it does not cover a jar shipping converted tables. A GPL glyph file loaded as data is probably aggregation, but it still needs the GPL text and Corresponding Source, and every modpack inherits those duties [I]. So avoid GPL-only fonts (miniwi, unscii-16-full), and take the OFL option for Unifont.
6. **CC.** Under CC BY 4.0 a format change "never produce[s] Adapted Material" (§2(a)(4)), so Leggie would need only the §3(a)(1) attribution items. Under BY-SA, share-alike binds adapted tables only. No recommended font is CC-licensed.
7. **Public-domain notices.** They are reliable when the author's own distribution carries them and contributors confirm them, as for misc-fixed (Kuhn's README, `FS/misc-fixed/x/README:279-281`; `FS/licence-refs/upstream/kuhn-ucs-fonts.html`). They are unreliable on compilations. misc-fixed 12x13ja's notice covers Baekmuk Hangul. 18x18ja's comments credit its kanji to "Japanese Industrial Standard, JIS X 9051-1984" and say "Some Korean symbols derived from Daewoo's X11 font contribution"; they name no other source for its Hangul, and this survey did not check Daewoo's terms (`FS/misc-fixed-cjk/x/18x18ja.bdf`, its COMMENT lines) [M]. funscii's CC0 covers Unifont 17 glyphs, and Fixedsys Excelsior imitates Microsoft's Fixedsys. CC0 has a fallback licence where waivers fail; a bare "public domain" does not.
8. **Provenance by glyph identity.** Base rates [M]: independent 2-px 8×16 fonts share 21–24/94 pixel-identical ASCII glyphs, 8×8 ROM fonts up to 43/94 pairwise, 3×5 fonts 25–43/94. Well above those rates: unscii-8 vs Topaz-8 at 65/94 and Retro Pixel Arcade vs the Atari arcade ROM at 27/36. unscii-16 vs FSEX at 27/94 is borderline (`SP/critic/cmp16.py`, `cmp8.py`, `arcadecheck.py`). Braille and box drawing are grid-forced and prove nothing.
9. **AI.** No recommended font mentions generative AI. Spleen, misc-fixed, Terminus, ttyp0, unscii and M+ predate it; Tiny5, ANAKRON, Ark, Jersey and monogram ship native pixel sources with long changelogs [I]. PixelFonts states AI-generated glyphs (`PF/README.md:14`) [M].

## 5. Recommended font set [proposal unless marked decided]

### 5.1 By use

- **Body text: font 0, misc-fixed 6×13** [decided, F1; the whole face by 28's rule, 28:632]. For M1, misc-fixed adds 8×13, a wider face at font 0's height, and 9×18 and 10×20 for larger monospace text, all public domain.
  - 6×13, 8×13, 9×18 and 10×20 each have OC351 351/351, Greek 49/49 and Cyrillic 96/96 inked, U+FFFD, one advance per face and every glyph inside the cell [M: `q1_font0.py`; `SP/report/ink-f1.out`; `SP/report/fontmeasure-f1.out`, `boxcheck-f1.out`]. One source with one credit line thus covers body text from 13 to 20 px.
- **Small text and HUD: misc-fixed 4×6, 5×8 and 6×10** (PD), with **Tiny5 and Tiny5 Duo** (OFL, F6) for proportional HUD text.
  - 5×8 and 6×10 give OC351 351/351, Greek 49/49 and Cyrillic 92/96, with braille and U+FFFD [M].
  - 4×6 lacks only the ten quadrants ▖–▟ (341/351). Those draw 4×6's missing glyph (§6.4).
  - Tiny5 and Tiny5 Duo fit 47 and 31 characters of prose per T1 line (`SP/report/linefit-f1.out`) [M], and cover Latin Ext-A, Greek and Cyrillic. Their I, l and | are identical; OpenGPU may patch them, and the patched table stays OFL.
  - Tiny5's own line of 9 px cuts the marks of the accented capitals that reach above it; a 16-px line holds every glyph (§3 (B)) [M]. Which line height `hud` declares is M1's choice [I].
- **Headings and readouts: Jersey 10/15/20/25** (OFL, F6) for headings and numbers, **ANAKRON 8×12** (OFL) for bold monospace labels, and integer scale ×2–×4 of any font (§6.1, F8).
- **Native large monospace (F3 option): Spleen 12×24 and 16×32** (BSD-2), OC351 351/351 but Greek 0/49 and 12/49 and no U+FFFD. They get a NOTICE section only if adopted. Spleen 32×64 stays for later, given its atlas cost (§5.5).
- **Wide (CJK): misc-fixed 12x13ja with font 0, and 18x18ja with 9×18** (F7).
- Spleen 8×16, the first draft's font 0, is no longer recommended for anything; §3 keeps its measurements.

### 5.2 Per tier

| Use | T1 160×100 | T2 320×200 | T3 640×400 |
|---|---|---|---|
| small labels and HUD | 4×6 40×16, 5×8 32×12, Tiny5 47×11 | 4×6 80×33, 5×8 64×25, 6×10 53×20, Tiny5 97×22 | 5×8 128×50, 6×10 106×40, Tiny5 191×44 |
| body text | 6×13 26×7 (font 0), 8×13 20×7 | 6×13 53×15, 8×13 40×15 | 6×13 106×30, 8×13 80×30, 9×18 71×22 |
| headings and readouts | ANAKRON 20×8, Jersey 10 23×5, Tiny5 Duo ×2 15×5 | Jersey 10/15/20 51×10, 31×7, 23×5; 9×18 35×11; 10×20 32×10; ANAKRON 40×16 | Jersey 15/20/25 65×14, 51×11, 39×9; 10×20 64×20; any font ×2–×4 |
| CJK, wide characters | 12x13ja in font 0's lines 13×7 | 12x13ja 26×15 | 12x13ja 53×30; 18x18ja in 9×18's lines 35×22 |

Entries are characters per line × lines, at scale 1 unless marked. For proportional faces they count characters of `linefit.py`'s English sample; for monospace faces they equal §1's arithmetic [M: `SP/report/linefit-f1.out`]. Tiny5's line counts assume its 9-px pitch (§5.1). `SP/report/m1-t1.spec`, `m1-t2.spec` and `m1-t3.spec` draw this table's faces with `tiermock.py` as `PV/m1-t1-160x100.png` (×4), `PV/m1-t2-320x200.png` (×2) and `PV/m1-t3-640x400.png` (×1), with 12x13ja as the fallback, aligned by cell top and drawn in blue.

### 5.3 What ships when

Font ids name roles and geometry, never upstream fonts. A release may swap the source behind an id if the geometry is kept (§6.7), and no Reserved Font Name reaches the Lua API (§4 item 4).

| Id | Role name | Source | Geometry | Licence | Ships |
|---|---|---|---|---|---|
| 0 | `text13` | misc-fixed 6x13, the whole face, its own U+FFFD as the missing glyph (28:632) | 6×13 cells | PD | **M0** (§7) |
| 1 | `tiny6` | misc-fixed 4x6 | 4×6 cells | PD | M1 |
| 2 | `small8` | misc-fixed 5x8 | 5×8 cells | PD | M1 |
| 3 | `small10` | misc-fixed 6x10 | 6×10 cells | PD | M1 |
| 4 | `text13w` ("w" for the wider cell, not wide glyphs) | misc-fixed 8x13 | 8×13 cells | PD | M1 |
| 5 | `text18` | misc-fixed 9x18 | 9×18 cells | PD | M1 |
| 6 | `text20` | misc-fixed 10x20 | 10×20 cells | PD | M1 |
| 7, 8 | `hud`, `hud-bold` | Tiny5, Tiny5 Duo 2.007 | proportional, line 9 (§5.1) | OFL | M1 |
| 9 | `label12` | ANAKRON 8x12 | 8×12 cells | OFL | M1 |
| 10–13 | `display10` … `display25` | Jersey 10/15/20/25, converted at build time | proportional, lines 20/27/34/42 | OFL | M1 |
| — | wide fallback for 0 (and 4, centred) | misc-fixed 12x13ja | 12×13 | PD; Hangul under Baekmuk's notice | M1 (F7) |
| — | wide fallback for 5 | misc-fixed 18x18ja | 18×18 | PD label (§4 item 7) | M1 (F7) |
| later | `text24`, `text32` | Spleen 12x24, 16x32 | 12×24, 16×32 cells | BSD-2 | if F3 adopts them |
| later | more CJK sizes, `text64` | Misaki 8×8, Unifont, Ark 12; Spleen 32x64 | — | free, OFL, BSD-2 | on demand |

Fonts that users bring stay sprite sheets drawn with `BLIT` from M0's texture path (28 §5.5, §6), so the built-ins need not cover every style [I]. The stock-GPU text console chooses its own font when it comes (§8 F2).

### 5.4 Obligations

- **M0** owes one credit line in NOTICE for misc-fixed: Markus Kuhn and contributors, with the public-domain statement [M: the BDF's COPYRIGHT property, `x/AUTHORS`; I]. F5 is deferred to M1, so M0 owes no musl section (§8). In the repository, `6x13.bdf` sits with its upstream `README` and `AUTHORS` beside it (§7).
- **Jar layout** [proposal]: one converted file per source font (§4 item 3). Each starts with a readable header naming the source, version, copyright line and licence, which OFL condition 2 accepts. OpenGPU's NOTICE already reproduces every bundled component's licence in full, one section per component, and is packed into the jar (`NOTICE:6-13`, the ASM section) [M].
- **Later additions owe:**
  - M1's misc-fixed faces: the same credit line, adding 4x6 by Janne V. Kujala;
  - one OFL 1.1 section listing the copyright line of each OFL font: Tiny5 (the line of the copy converted: the repo has 2026, Google Fonts 2022–2024), ANAKRON and Jersey;
  - musl's MIT section, if F5 reuses OC's `wcwidth` table (`FontUtils.scala:20-30`; §6.2);
  - Baekmuk's notice plus its trademark line, for 12x13ja's Hangul (`FS/misc-fixed-cjk/COPYRIGHT-baekmuk.txt`);
  - Spleen's BSD-2 section (the copyright, both conditions and the disclaimer, `FS/spleen/LICENSE`), only if F3 adopts Spleen;
  - on demand: Misaki's licence text, Unifont's OFL line (the BDF COPYRIGHT line, noting the OFL option), ttyp0's licence and rename clause, Terminus's rename away from "Terminus", and unscii-16's credit line (viznut) if the console adopts it.
- **In the repository** the source BDFs sit with their upstream licence or README files. RM:246's clean-room rule covers `examples/`, not bundled components, which already take the NOTICE route [M: RM:246, NOTICE:6-13].
- **OFL tables** stay OFL when converted or patched, and are never sold alone; a free mod jar is not a sale [I].

### 5.5 Atlas and jar cost

From `SP/report/atlascost.py`, which counts each glyph at max(advance, box width) × line height (`SP/report/atlascost-f1.out`, summed by `setcost.py` into `setcost-f1.out`), and `SP/report/bdfpack.py`, which packs each font as hexpack.py packs `.hex`: rows padded to bytes, a 4-byte index entry per glyph, deflated at level 9 (`SP/report/bdfpack-f1.out`) [M]:

| Set | Glyphs | R8 (1 B per texel, 28:663) | 1 bit per texel | Jar, deflated |
|---|---|---|---|---|
| **M0 font 0: misc-fixed 6×13, whole** | 4 121 | **321 438 B** | 40 180 B | 24 038 B |
| 6×13's OC351 + U+FFFD only (28:632) | 352 | 27 456 B | — | — |
| misc-fixed 4×6 / 5×8 / 6×10 | 919 / 1 426 / 1 597 | 22 056 / 57 040 / 95 820 B | 2 757 / 7 130 / 11 978 B | 3 934 / 6 743 / 8 158 B |
| misc-fixed 8×13 / 9×18 / 10×20 | 3 703 / 4 766 / 5 205 | 385 112 / 772 092 / 1 041 000 B | 48 139 / 96 512 / 130 125 B | 23 393 / 37 866 / 44 740 B |
| Tiny5 + Duo; ANAKRON; Jersey 10–25 | 3 884; 2 006; 4 × 332 | 187 929; 192 576; 560 328 B | 23 492; 24 072; 70 043 B | 20 488; 11 847; 16 069 B |
| **M1 resident set, ids 0–13** | 28 955 | **3 635 391 B** (3.47 pages of 1 024²) | **454 428 B** | **197 276 B** |
| CJK fallbacks: 12x13ja; 18x18ja | 19 208; 19 168 | 2 996 448; 6 210 432 B | 374 556; 776 304 B | 172 775; 420 709 B |
| F3 option: Spleen 12×24 + 16×32 | 926 + 978 | 266 688 + 500 736 B | 33 336 + 62 592 B | 6 389 + 7 862 B |
| Unifont 18 plane 0 (only if F7 adds it) | 57 086 | 13 678 464 B | 1 709 808 B | 814 857 B for the all-planes `.hex`'s 60 518 glyphs below U+10000, PUA included (`hexpack.out`) |
| Spleen 32×64 (later) | 978 | 2 002 944 B (`atlascost.out`) | 250 368 B | — |

- **Atlas.** M0 fits 28 §7.5 as written: one R8 page of 1 024², whose 13 260 cells of 6×13 hold the 4 121 glyphs (28:632). M1's resident set needs at least four 1 024² R8 pages, or one R8UI page at 1 bit per texel (454 428 of 1 048 576 B) with a bit extract in the fragment shader, which is exact under `texelFetch` [I]. The page counts divide bytes; packing whole cells loses each page's edges, as 10×20 cells use 1 020 × 1 020 of a page [M arithmetic].
- **CJK fallbacks** are never resident. Their glyphs enter an LRU page cache of their own cells, 6 630 cells of 12×13 or 3 136 of 18×18 per 1 024² R8 page [proposal; M arithmetic]. Page choice is invisible to pixels, so tier E holds (23:137) [I].
- **Tiny5's figures** count its 9-px line, which some glyphs exceed (§3 (B)); bdfpack clipped 694 and 696 set pixels from Tiny5 and Duo. A 16-px box would cost 148 800 + 185 296 B at R8 [M arithmetic].
- **Jar.** The resident set packs to 777 436 B raw, or 197 276 B deflated, and the CJK fallbacks to 1 687 984 B raw, or 593 484 B deflated. M1's fonts, font 0 included, therefore take 790 760 B, about 0.79 MB, of the jar. M0's font 0 is 24 038 B of that, so M1 adds 766 722 B [M].

## 6. Implications for 28's `TEXT`

### 6.1 The font selector

| Option | Wire change | Validator | Cost | Verdict |
|---|---|---|---|---|
| (a) font id in `TEXT`'s flags bits 1–4 | none; defining reserved bits is additive (28:373) | `BAD_FLAGS_FIELD` for an undefined id | uses 4 of 7 free bits, leaving 3 for scale and styles | workable, cramped |
| (b) a new opcode in 0x36–0x3F (28:396) with a u8 `font` | additive | a new `UNKNOWN_FONT` | two text opcodes for good; 0x33 stays font 0 | workable |
| (c) `TEXT` 0x33 gains a u8 `font` at offset 14: `1: i16 x · 3: i16 y · 5: fg · 9: bg · 13: u8 flags · 14: u8 font · 15: u16 n · 17: bytes[n]` | an in-place edit, allowed while v1 is a draft until M1's release (28:379, Q8 accepted) and free before S6 (RM:52) | `UNKNOWN_FONT` after the flags check; M0 accepts font 0 only | 1 B per record: 28:694's 3 180 records for a full T3 screen grow from 54 060 to 57 240 B, about 53 to 56 KB | **decided** (F4, 2026-10-15) |
| (d) a `SET_FONT` state opcode in 0x05–0x0F | additive | frame state carried across segments (28 §3.2) | the validator and the oracle gain state; splitting tests (P5) depend on it | not recommended |

With (c), defining a new font id in M1 is a relaxed check, so it needs no version bump (28:373). 28 §9.2's `text()` builder gains a `font` argument, and 28 §9.4's worked frame grows from 63 to 64 B [M: `cmdstream-checks/rev_checks.py`, re-run 2026-10-15]. Both are applied (28's section "F4 taken, F5 deferred").

**Integer scale** [decided 2026-10-15 (F8), M1]: `TEXT` flags bits 1–2 give s ∈ {1, 2, 3, 4}, one factor for both axes, and each glyph pixel becomes an s×s block; `BLIT` uses the same bits and range (28:859). Defining them is additive (28:373), and the result stays tier E (23:137).

### 6.2 Cell sizes, advances and wide glyphs

- Each font id has a fixed geometry: cell w×h for cell fonts, or maximum advance and line height for proportional fonts.
- **Advance rule for cell fonts** [proposal for M1; F5 was deferred on 2026-10-15, so M0 advances one cell per codepoint, 28:650]: codepoint k advances `wcwidth(cp)` cells, which is 1 or 2.
  - This is OC's rule. `FontUtils.wcwidth` returns 2 for double-wide codepoints by a table taken from musl (`OCT/scala/li/cil/oc/util/FontUtils.scala:14`, `:18-221`). OC's hex parser drops any glyph whose width differs from it (`OCT/scala/li/cil/oc/client/renderer/font/FontParserHex.java:60-66`) [M].
  - The table is musl's code under MIT, "Copyright © 2005-2020 Rich Felker, et al." (`FontUtils.scala:20-30`) [M]. Reusing it adds a musl section to NOTICE. Generating the same table from Unicode's EastAsianWidth data would need the Unicode licence notice instead, and could differ from OC's copy by Unicode version [I].
  - The advance therefore depends on the codepoint and the font's cell, never on glyph data.
  - It settles 28:384's open point, the advance of codepoints without a narrow glyph: a wide codepoint takes 2 cells whether or not the font draws it.
- The cell origin of codepoint k becomes x + w·Σ`wcwidth` over the codepoints before it, instead of x + 6k (28:469). The oracle's `TEXT` loop (28:502-505) takes w, h and the advance from the font.

### 6.3 Proportional fonts and the validator's fill accounting

28 charges 78 px per codepoint, one 6×13 cell, unclipped (28:284, :655).

- **Cell fonts:** charge `wcwidth(cp)`·w·h·s². For font 0 at s = 1 that is 78 px per narrow codepoint, as 28 charges now, and 156 px per wide one. It is exact, and it is a function of the record and the release's constants.
- **Proportional fonts:** charge maxAdvance·lineHeight·s² per codepoint, an upper bound that is constant per font. The validator then needs no advance table.
- Energy per "set" pixel (28 §8.3) uses the same totals; OC itself charges per codepoint (28:645).
- Proportional pixel results depend on per-glyph advances, which the oracle already reads from the font. Programs need them too, to centre or right-align: `textWidth(font, s[, scale])` [proposal, M1].
- Advances therefore become API, frozen with the layouts at M4 (24:253). Bitmaps stay revisable (28:376).

### 6.4 Missing glyphs and fallbacks across fonts

- Each built-in font has a fixed chain [proposal]. A codepoint is drawn from the font's own table if it is there. A wide codepoint (`wcwidth` 2) the font lacks goes to fallback tables of the same cell height and a full width of 2w. Anything else draws the font's missing glyph in `wcwidth(cp)` cells.
- The chain belongs to the font's definition and is versioned with it, like the glyph source (28:376). The protocol does not name it.
- For font 0 the chain is 6x13, then 12x13ja, then 6x13's own U+FFFD.
- Fallback glyphs are placed by cell top, not baseline. For font 0 the two coincide: 6x13 and 12x13ja are both 13 px tall with ascent 11 and descent 2 (`SP/report/f7-cjk.out`; `PV/combo-miscfixed6x13+12x13ja.png`) [M]. They differ for 9x18 and 18x18ja, both 18 px tall with ascents 14 and 15, where baseline alignment would push the wide glyph 1 px above the line [M].
- Narrower fallback glyphs are centred at a fixed offset, such as 12x13ja in 8×13's 16×13 double cell, or Misaki 8×8 in a 10×8 double cell of 5×8 [proposal].
- Fonts draw their own box-drawing and block glyphs where they have them. Every recommended cell font with an id has U+2500–259F complete except 4×6, which lacks the ten quadrants. The wide fallbacks, used only for wide codepoints, lack some (12x13ja 72/128 box drawing, 18x18ja 22/32 blocks), and Tiny5 and Jersey have none [M: `SP/report/fontmeasure-f1.out`]. Codepoints a font lacks draw its missing glyph. Programs draw borders and bars with `FILL_RECT` (28 §5.4), and lines with M1's `LINE` (28:396), exact at any size (23:137).
- The misc-fixed faces and the CJK fallbacks have U+FFFD, and ANAKRON has a `.notdef`. Tiny5, Tiny5 Duo and the converted Jersey tables have neither [M: `fontmeasure-f1.out`; a text search of the BDFs]. Their missing glyph is a box at the font's line height and maximum advance, drawn by OpenGPU's code, as 28's synthetic table draws its box (28:633) [proposal].

### 6.5 The oracle, the host atlas and the goldens

- The oracle and the GL atlas take one injected `FontSet` (id → geometry, tables, chain) instead of one G (28:634), so they still cannot diverge.
- CI's synthetic G (28:633) becomes synthetic font 0. Add a synthetic proportional font and a synthetic wide glyph, so the advance and fill rules are tested without real fonts.
- Goldens may now use the real built-in tables, because they live in OpenGPU's repo under their own licences. Each sidecar records the sha256 of every table used (23:147; 28:376), so revising a glyph invalidates exactly the goldens that draw it.
- The host atlas follows §5.5: lazy pages, a page cache for the CJK fallbacks, and all of it exact.

### 6.6 `getCaps`

`getCaps` gains one key, `fonts`, the number of built-in fonts, which keeps the small fixed table (28:692; 09:59). A `fontInfo(id)` callback returns `name`, `kind` (`"cell"` or `"proportional"`), `width`, `height`, `ascent` and `maxScale` [proposal, M1]. `commandFormat` stays 1 as ids are added; a program detects a missing font as `UNKNOWN_FONT`, as it detects a missing opcode (28:386).

### 6.7 Why validation may now depend on font geometry

28 keeps validation independent of glyph data (28:655). Before F4 the protocol named no font; since F4 (2026-10-15) it names font ids and their cells, but still not their bitmaps (28:628). That is the one bounded change fixed built-in fonts make.

1. Programs lay text out in pixels, so a font's advance and line height are API anyway: a label placed right after a 10-character string at x + 60 relies on font 0's 6-px advance.
2. The fill charge must be computable before execution. Geometry and `wcwidth` are constants of the release, like the caps (28 §8.1).
3. Revising a glyph changes no acceptance and no advance, only pixels, as 28:376 already allows. Swapping a source behind an id is legal if the geometry is kept.

User-supplied fonts never enter the validator: they are textures, validated by `BLIT`'s rules.

## 7. F1 = 28 Q1: font 0, decided 2026-10-14

- **Decision.** Font 0 is X11 misc-fixed 6x13, shipped in OpenGPU's jar from M0. misc-fixed 8x13 is the alternative "if it doesn't look right as the default size" (the owner; §8 F1). 28 §7.1 records it (28:630, :641), and ships the whole face by its own rule (28:632, a [proposal] within the decision).
- **Reasons.** OpenGPU draws text at any pixel (revision note), so font 0 needs legibility at 1×, OC351 and a clean licence, not a character-grid cell. For 6x13 [M, I]:
  - it is public domain and owes only a credit line, so M0 adds no licence section (§5.4);
  - it is the largest of misc-fixed's three regular 13-px faces (6x13 4 121, 8x13 3 703, 7x13 3 226 glyphs; `SP/report/fontmeasure-f1.out`);
  - it puts 53 characters on a T2 line and 106 on a T3 line, against 40 and 80 at 8 px wide (§1);
  - it has a double-width companion of the same height, 12x13ja (§8 F7).
- **Checked** [M: `cmdstream-checks/q1_font0.py` and `SP/report/ink.py`, run on the BDFs as data]:
  - `$Id: 6x13.bdf,v 1.115 2009-04-06`, sha256 d00cf1cb… (§9.1), inside `ucs-fonts.tar.gz` (sha256 702fd1cd…). COPYRIGHT reads "Public domain font. Share and enjoy."
  - Cell 6×13 (FONTBOUNDINGBOX 6 13 0 −2), ascent 11, descent 2. All 4 121 glyphs advance 6 and fit the cell, and U+FFFD is present.
  - OC351 is 351/351 inked, with box drawing 128/128, blocks 32/32, Greek 49/49 and Cyrillic 96/96.
  - Fill is 78 px per codepoint. Lines are 26×7 on T1, 53×15 on T2 and 106×30 on T3.
- **Provenance** [M: `FS/misc-fixed/x/AUTHORS`; `x/README:279-281`]:
  - The designers of the original ASCII and Latin-1 repertoire have "been lost in history", likely MIT's Project Athena or DEC in the 1980s.
  - "Markus Kuhn … did most 6x13 glyphs", and Constantine Stathopoulos contributed all the Greek. Serge V. Vakulenko donated the original Cyrillic from his 6x13 ISO 8859-5 font. Mark Leisher contributed 6x13 Armenian, Georgian, the first Latin Extended-A and some Cyrillic, and Nozomi Ytow the 6x13 halfwidth Katakana.
  - Contributors agree that their glyphs "will remain in the public domain for everyone's free use".
- **What ships in M0:**
  - the whole face, with its own U+FFFD as the missing glyph (28:632);
  - its atlas, 321 438 B at 1 B per texel in one 1 024² page of 13 260 cells (28 §7.5; §5.5) [M];
  - a build-time BDF-to-table converter (own code, about 0.5 d [E]), with `6x13.bdf` in the repository and its upstream `README` and `AUTHORS` beside it, which carry the public-domain terms and the credits;
  - a credit line in NOTICE (§5.4).
- **What goes.** 28 Q1's old default, the dev-only read of OC's `font.hex` (28:944), is dropped.
  - No glyph from OC's mixed-licence file reaches OpenGPU's screenshots or goldens: that file holds public-domain unscii, CC0 asie glyphs, OFL Unifont and public-domain Shinonome (§3) [I].
  - M0's in-game text shows the font M1 keeps, and M0's criteria still need only a visible pattern and sprite (RM:66).
  - CI is unchanged: S6 builds on 28's synthetic table at 6×13 (28:633).
- **The alternative, 8x13** [M: `q1_font0.py`]:
  - `$Id` v1.57 2006-01-05, sha256 436d4227… (§9.1).
  - Cell 8×13, ascent 11, descent 2. All 3 703 glyphs advance 8 and fit the cell, U+FFFD is present, and every measured set is complete.
  - Fill is 104 px per codepoint, and lines are 20×7, 40×15 and 80×30. Its atlas is 385 112 B whole, or 36 608 B for OC351 plus U+FFFD.
  - The cell is part of the format (28:376, :655), so a switch is an in-place edit only while v1 is a draft. It must come before v1 freezes at M1's release (28 §4.3, 28:379). It would change 28's cell, its fill charge from 78 to 104 px (28:284) and its synthetic table, which `q1_font0.py` already checks at 8×13.
  - If F3's default stands, M1 ships 8x13 anyway as `text13w` (§5.3).
- **Previews.** `PV/f1-t2-6x13-vs-8x13.png` (T2 at ×2) and `PV/f1-t3-6x13-vs-8x13.png` (T3 at ×1) were drawn by `SP/report/tiermock.py` from `SP/report/f1-t2.spec` and `f1-t3.spec`. On T2, the 44-character pangram line fits 6x13's 53 cells and clips at 8x13's 40 [M].
- **28 changed in place**, with no line added or removed (28's section "Q1 answered"): the status (28:3), summary item 9 (:17), the fill rule (:284), §4.3's glyph row (:376), `TEXT`'s result and oracle loop (:469, :503), §7.1 (:628-641), §7.2's wide-glyph item (:651), §7.3 (:655), §8.1's full-screen example (:694), §12's opening and Q1 (:942, :944), and the scripts list (:990).
- F4 is decided (2026-10-15): M0's `TEXT` carries the font byte and accepts 0 only, and M1 adds ids 1–13 as a relaxed check (28:373, :426).
- The first draft's default, Spleen 8×16, and its alternatives (unscii-16, ttyp0, Unifont and Terminus at 8×16) are not taken; §3 keeps their measurements.

## 8. Owner questions

F1, F4 and F8 are decided, F2 is answered and F5 is deferred to M1; each other default stands until the owner decides.

**F4 and F5 came first**, because they change `TEXT`'s layout and its advance and fill rule, which S6's codec, validator and oracle implement (RM:52). The owner answered both on 2026-10-15, before S6. F4 is taken: `TEXT` is 17 + n B with the font byte at offset 14 (28:426). F5 is deferred: M0 advances one cell per codepoint and charges 78 px per codepoint (28:650, :284), ships no `wcwidth` table and owes no musl section, and M1 settles the advance of wide codepoints before v1 freezes (28:384), with F7. F3, F6 and F7 concern M1; F8 was decided the same day; F9 is housekeeping.

1. **F1 = 28 Q1. The M0 glyph source.** *Decided 2026-10-14:* "I think we can go with the default of 6*13 and adjust it to 8*13 if it doesn't look right as the default size." (the owner). Font 0 is misc-fixed 6x13 in M0, shipped as the whole face by 28's rule (28:632); 8x13 is the alternative until v1 freezes (§7).
2. **F2. unscii provenance (24 §8 risk 16, 24:320).** *Answered 2026-10-14 for the deferred text console:* "If/when we get to using the OC GPUs as text consoles on an OpenGPU screen, yes, we can decide about the font use then (using unscii-16 in that case would be perfectly acceptable)." (the owner).
   - unscii-16 is therefore acceptable for the stock-GPU text console (24:14 decision 8; RM:282), whose font is chosen when that work starts. M1 ships no `oc16`.
   - The showcase terminal "doesn't have to match OC builtin sizes" (the owner), so it uses the built-ins (§5).
   - *Default that stays:* reject unscii-8, -8-tall and -8-thin.
   - *Facts for the later choice:* unscii-16 is public domain, and OC and GTNH have shipped it for years [I]. It is not an IBM VGA copy (3/94). Its overlap with Fixedsys Excelsior, itself an imitation of Fixedsys, is only slightly above the base rate (27/94 against 21–24). unscii-8 is a per-glyph selection of ROM glyphs (65/94 Topaz-8; 88/94 at least one ROM).
3. **F3. The family beyond font 0.** *Default:* the misc-fixed family for M1: 4×6, 5×8 and 6×10 small, 8×13 at font 0's height, 9×18 and 10×20 larger, with the CJK companions of F7. *Why:* one public-domain source with one credit line; OC351, Greek and Cyrillic complete with U+FFFD at 13, 18 and 20 px; CJK companions at 13 and 18 px; the X11 look of font 0 [M, I].
   - *Option:* Spleen 12×24 and 16×32 (BSD-2) for native monospace above 20 px, 767 424 B of R8 atlas together (§5.5). They have OC351 351/351 but Greek 0/49 and 12/49, Cyrillic 72/96 at 16×32 and no U+FFFD, and they bring a NOTICE section. Without them, text above 20 px is Jersey or integer scale (F8).
   - *Alternatives:* UW ttyp0, with full Greek, Cyrillic and braille at seven sizes but a rename clause; Terminus, with nine sizes and bold but an OFL rename and 341/351.
4. **F4. `TEXT`'s font operand.** *Default:* §6.1 (c), a u8 `font` at offset 14, giving 17 + n B, with `UNKNOWN_FONT` checked after the flags; M0 accepts 0 only. *Why:* it is free before S6, keeps one text opcode, and later ids are additive. *Decided 2026-10-15:* "yes, let's add the ID byte now" (the owner). 28 is amended (28's section "F4 taken, F5 deferred").
5. **F5. Advance and fill.** *Default:* `wcwidth(cp)` cells with OC's musl table, with musl's MIT notice in NOTICE; fill is `wcwidth`·w·h·s², 78 px per narrow codepoint for font 0; proportional fonts are charged maxAdvance·lineHeight·s² per codepoint. *Why:* it settles 28:384 now, matches OC, and keeps glyph data out of the validator. *Deferred to M1 on 2026-10-15:* "undecided but I *think* we can skip CJK support for M0" (the owner). M0 keeps one cell per codepoint (28:650); the default above stays M1's proposal, settled with F7 before v1 freezes (28:384).
6. **F6. Proportional and OFL fonts in M1.** *Default:* yes, Tiny5, Tiny5 Duo, ANAKRON and Jersey, each OFL table in its own file with a header, and the OFL section in NOTICE. *Why:* role B's densest Latin, Greek and Cyrillic, role C's bold monospace labels and its four heading sizes have no permissive equal. *The alternative* is a permissive-only set (misc-fixed, Spleen, M+, Misaki, Tom Thumb).
7. **F7. CJK in M1.** *Default:*
   - Font 0's wide fallback is misc-fixed 12x13ja: 12×13 is two 6×13 cells, with the same ascent 11 and descent 2. It has kana 179/192, URO 6 356 and Hangul 11 172 inked (`SP/report/f7-cjk.out`) [M]. Its Hangul needs Baekmuk's notice (§5.4).
   - 9×18 pairs with 18x18ja: 18×18 is two 9×18 cells, with ascent 15 against 14 (§6.4). It has kana 179, URO 14 655 and Hangul 2 350 inked [M]. Its comments give no checked source for the Hangul (§4 item 7), so its Hangul ships only after that check [proposal].
   - 8×13 may take 12x13ja centred (§6.4).
   - Other faces draw the missing glyph for wide codepoints until a same-height source is added. Unifont comes in only if a 16-px-tall face is adopted, or Spleen 16×32 through ×2 (F3), at 815 KB deflated (§5.5); Misaki 8×8 covers 8 px later.
   - *Why:* one public-domain source at the height of the faces most text uses, with no OFL in the fallback.
8. **F8. Integer scale for `TEXT` in M1** (flags bits 1–2). *Default:* yes. *Why:* it gives display sizes from every font at no atlas cost, and stays tier E. *Decided 2026-10-15:* "I think the defaults make sense, we can record them." (the owner), on these defaults: s ∈ {1, 2, 3, 4} in bits 1–2 (×1–×8 would take a third bit), one factor for both axes rather than separate ones, the same bits and range on `BLIT`, and fill counted on the scaled area (28:859).
9. **F9. Housekeeping.** *Default:* once F2 is recorded, delete the comparison-only ROM transcriptions in `FS/unscii-8/compare-only/` and `FS/unscii-critic-compare/`. *Why:* they are proprietary ROM data, kept only for the identity checks.

## 9. Sources

### 9.1 Downloads

Every file under `FS/` is listed with its size, sha256 and source in `SP/report/29-font-survey-files.tsv` (327 files, written by `SP/report/manifest.py`) [M]. The measurers' download logs, `SP/small/downloads.tsv` and `SP/measure-cd/downloads.tsv`, give the URL per file. Upstreams by folder:

| Folder | Upstream | Version | Key file sha256 |
|---|---|---|---|
| `spleen`, `spleen-5x8` | github.com/fcambus/spleen | tag 2.2.0 = 0493c34 | `spleen-8x16.bdf` 4a3d97ee…; `LICENSE` f33fe867… |
| `misc-fixed` | cl.cam.ac.uk/~mgk25/download/ucs-fonts.tar.gz; Debian xfonts-base 1:1.0.5+nmu1 COPYING | 2009-04-06 | tarball 702fd1cd…; `x/6x13.bdf` d00cf1cb… (`$Id` v1.115, 2009-04-06); `x/8x13.bdf` 436d4227… (v1.57, 2006-01-05); `x/5x8.bdf` a126e263… |
| `misc-fixed-small` | sources.debian.org xfonts-base 1:1.0.5+nmu1 font-misc-misc | 4x6 v1.5, 5x8 v1.32 | `5x8.bdf` 72dbfa53…; `COPYING` 1711d038… |
| `unscii`, `unscii-8` | github.com/viznut/unscii | 257b4df (2.1) | `unscii-16.hex` 2642c8b7…; `unscii-8.hex` 03094f7f… |
| `funscii` | github.com/asiekierka/funscii | opencomputers 233661e; master 397417b | `oc-LICENSE-font.txt` 36ffd9dc… |
| `terminus` | downloads.sourceforge.net terminus-font-4.49.1.tar.gz | 4.49.1 | tarball d961c1b7… |
| `uw-ttyp0` | people.mpi-inf.mpg.de/~uwe/misc/uw-ttyp0/uw-ttyp0-2.1.tar.gz | 2.1 | tarball 99506dd8… |
| `cozette`, `dina` | github.com/the-moonwitch/Cozette; dcmembers.com/jibsen | v.1.30.0 | `cozette.bdf` 8d740166… |
| `leggie`, `tamzen` | github.com/wikkyk/leggie; github.com/sunaku/tamzen-font | 05aeb28; Tamzen-1.11.6 = caa2a5d | `leggie-18.bdf` 95203e7c…; `Tamzen8x16r.bdf` a44184f2… |
| `tiny5` | github.com/Gissio/font_tiny5 | 1f124ca (2.007) | `Tiny5.bdf` d9f5458d…; `OFL.txt` 6e244080… |
| `tom-thumb` | robey.lag.net; freebsd-src share/vt/fonts | d0b2dbf (FreeBSD) | `tom-thumb.bdf` d2c8c15d… |
| `misaki-k6x8` | littlelimit.net | 2021-05-05; 2023-10-19 | zips a275f173…, 12a30719… |
| `monogram`, `miniwi` | gitlab.com/datagoblin/monogram; github.com/a780201/miniwi | 19fde16; 6dc0fec | `monogram-bitmap.json` e31793b0…; `miniwi.bdf` 61652616… |
| `jersey` | github.com/scfried/soft-type-jersey | d8446c4 | `Jersey10-Regular.ttf` db9cbd09…; `OFL.txt` 436d5836… |
| `anakron` | github.com/molarmanful/ANAKRON | v0.3.3 = 6308ba7 | `ANAKRON.bdf` d792885a…; `LICENSE` 40517c13… |
| `mplus-bitmap` | deb.debian.org xfonts-mplus_2.2.4.orig.tar.xz | 2.2.4 | tarball 4c973ef8… |
| `retro-pixel-font` | github.com/TakWolf/retro-pixel-font | 2026.09.18 = 0f16fdb | bdf zip a819d526… |
| `unifont`, `unifont-17`, `unifont-15` | ftp.gnu.org/gnu/unifont; unifoundry.com/LICENSE.txt | 18.0.01; 17.0.05; 15.1.05 | `unifont-18.0.01.bdf.gz` 2989e021…; `LICENSE.txt` 1e74cb82… |
| `ark-pixel-font` | github.com/TakWolf/ark-pixel-font | 2026.09.25 = 3ff688b | 12px zip 0caf9f3e…; `LICENSE-OFL` 3ab41567… |
| `misc-fixed-cjk` | cl.cam.ac.uk ucs-fonts-asian.tar.gz; Debian fonts-baekmuk 2.2-15 COPYRIGHT | 2009-04-06 | tarball a1283ebe…; `x/12x13ja.bdf` 01b78d34… (`$Id` v1.27); `x/18x18ja.bdf` 12bc0c0a… (v1.17) |
| `shinonome` | sources.debian.org xfonts-shinonome 1:0.9.11-7 | 0.9.11 | `LICENSE.txt` 86fbc28a… |
| `licence-refs` | openfontlicense.org, gnu.org, creativecommons.org, unifoundry.com; upstream licence files | 2026-10-14 | `ofl-faq.html` ed217cd0…; `gpl-faq.html` 5053fe23… |
| `unscii-critic-compare` | viznut/unscii src/font-pc16.txt, font-arcade.txt | 257b4df | comparison only, never ship (§8 F9) |

Font 0's source and its alternative in full [M, matching the manifest]: `misc-fixed/x/6x13.bdf` sha256 d00cf1cbaed055bb91d11c80e063d5d2d3b66552a7f2ead7161ee3780f9123f0; `misc-fixed/x/8x13.bdf` sha256 436d42272e19e0d0dc2fa8ce8a19b0629ee4e05670bcf7e8cc46dabae6b9000b.

Fetch notes [M]:
- gitlab.freedesktop.org is behind an Anubis bot check, and xorg.freedesktop.org refused connections, so the X11 fonts came from Kuhn's site and Debian.
- openlab.jp refused connections, and the M+ OSDN site redirects away.
- The anonymous GitHub API hit its rate limit, so the measurers used `git ls-remote` (read-only) and raw files.
- OC's README link to asiekierka/unscii-asie is dead; funscii's `opencomputers` branch is its evident successor [I].
- Nothing downloaded was built, imported or executed. Archives were extracted with Python's `zipfile` and `tarfile` (`filter='data'`).

### 9.2 Scripts and previews

- Writer: `SP/report/manifest.py`, `atlascost.py` (+ `.out`), `hexpack.py` (+ `.out`), `tiermock.py` with `t1.spec`, `t2.spec`, `t3.spec`. One inline `python -I -c` call filtered `fontmeasure.py`'s own output; everything else ran as script files.
- F1 (main session): `cmdstream-checks/q1_font0.py` (font 0's cell, coverage with Greek and Cyrillic, atlas and lines, and 28's synthetic table at 6×13 and 8×13), `SP/report/ink.py` (inked OC351, box, blocks, Greek and Cyrillic of the 13 regular misc-fixed faces, `ink-f1.out`), and `SP/report/f1-t2.spec` and `f1-t3.spec`, which draw `PV/f1-t2-6x13-vs-8x13.png` and `PV/f1-t3-6x13-vs-8x13.png`.
- Revision (reviser), all in `SP/report/` and run as `python -I -X utf8`:
  - `atlascost.py` on the revised set (`atlascost-f1.out`), summed by `setcost.py` (`setcost-f1.out`);
  - `bdfpack.py`, jar sizes of BDF tables (`bdfpack-f1.out`);
  - `boxcheck.py`, glyph boxes against ascent and descent (`boxcheck-f1.out`);
  - `linefit.py`, characters per line and lines per tier (`linefit-f1.out`);
  - `f7_cjk.py`, the CJK companions against 6x13, 8x13 and 9x18 (`f7-cjk.out`);
  - `SP/shared/fontmeasure.py` on the revised set (`fontmeasure-f1.out`);
  - `m1-t1.spec`, `m1-t2.spec` and `m1-t3.spec` for `tiermock.py`, which drew `PV/m1-t*.png` (§5.2).
  - Inline `python -I -c` calls only filtered `fontmeasure-f1.out` and counted the f1 specs' line lengths; `.notdef` was found by a text search of the BDFs.
- Shared tool: `SP/shared/fontmeasure.py`.
- Terminal measurer: `SP/terminal/` (`ocprov.py`, `ocprov2.py`, `cellcmp.py`, `lookalike.py`, `ttyp0map.py`, `unstxt.py`).
- Small measurer: `SP/small/` (`inked.py`, `romcompare.py`, `rommatrix.py`, `selfdup.py`, `measure.jsonl`).
- Display and wide measurer: `SP/measure-cd/` (`ttfgrid.py`, `cov.py`, `probe.py`, `scalecheck.py`, `jis2uni.py`, `stack.py`).
- Critic: `SP/critic/` (`cmp16.py`, `cmp8.py`, `shapecmp.py`, `arcadecheck.py`, `borrowcheck.py`, `inkcheck.py`).
- PixelFonts evaluation: `SP/pixelfonts/` (`c1_bdf.py` and `c2_sets.py` with its output, the coverage check this note cites; that evaluation's other scripts stayed in the session's working folder).
- Every script was copied unchanged except for line endings and one line: the small measurer's scripts loaded `fontmeasure.py` by an absolute path into the working folder, and now load it from `../shared` (`render2.py`'s comment says so too).
- Previews: `PV/`, 110 PNGs; the main ones are named in §3's tables, `plan-t*.png` are the first draft's, `f1-t*.png` the main session's and `m1-t*.png` the revision's.

### 9.3 Measurement caveats

- **Blank glyphs.** `fontmeasure.py` counts encoded glyphs, including blank ones. That changes one result: Spleen 5×8 reports 202/351 but only 107/351 have ink (`SP/critic/inkcheck.py`) [M]. The ink check found no other blank OC351 glyph in the fonts it covered: Spleen 6×12 to 16×32, misc-fixed 4×6, 5×7 and 6×10, ttyp0, ANAKRON, Tiny5 and unscii-16 [M]. misc-fixed 5×8's Ѝ and ѝ are blank, which §3's Cyrillic count already reflects [M]. The misc-fixed OC351, Greek and Cyrillic figures are inked; their arrows and braille are encoded counts [M].
- **Glyphs beyond the line.** atlascost.py and bdfpack.py size every glyph by ascent + descent. Every face in the revised set keeps its ink inside that line except Tiny5 and Tiny5 Duo (§3 (B)), whose atlas figures are therefore lower bounds and whose packed tables drop 694 and 696 set pixels (`SP/report/boxcheck-f1.out`, `bdfpack-f1.out`) [M].
- **FreeBSD .hex widths.** `--hex-height N` derives the width from the byte count, so FreeBSD's 4-px `.hex` files read as 8 px wide. Tom Thumb was converted using its `# Width` header instead [M].
- **Console encoding.** `fontmeasure.py` prints with `ensure_ascii=False`, which fails on a cp1252 console for Unifont's `Æ`. The measurers wrapped it (`SP/measure-cd/fma.py`); the revision ran it with `-X utf8` [M].
- **Own readers.** The measurers' readers for ttyp0's named glyphs and funscii's `.txt` sources re-implement upstream build logic without running it. Their counts are approximations: ttyp0 3 770 against about 3 800, funscii a lower bound [M].
