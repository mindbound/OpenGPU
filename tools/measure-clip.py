"""Measure the cliptest panels in a GUI screenshot, in SCREEN pixels, by colour runs.

The instrument is deliberately dumb: for each colour of interest it finds the bounding box of
all pixels within a tolerance of that colour, then reports the box and, from the scene scale,
the implied SCENE extent. The scene scale is derived from the same image: the panel background
colour (40,40,60) is drawn on four 161x125 canvases, so the widest run of that colour on a row
through panel A gives 161 scene px in screen px.

Usage:  python tools/measure-clip.py <screenshot.png>

KNOWN LIMITATION (run 1, 2026-09-08): the vertical scale estimate can be broken by a panel
label crossing the colour run (it read 2.97 where the horizontal read 3.37). The GUI scales the
scene uniformly, so use the HORIZONTAL scale for both axes; the per-panel "scene" figures below
are printed with it. The script's printed output is the source of record for FIELD-TEST-CLIP's
results; paste from it, never retype.
"""
import sys
from PIL import Image

path = sys.argv[1]
im = Image.open(path).convert("RGB")
W, H = im.size
px = im.load()

def near(c, ref, tol=28):
    return abs(c[0]-ref[0]) <= tol and abs(c[1]-ref[1]) <= tol and abs(c[2]-ref[2]) <= tol

def bbox(ref, x0=0, y0=0, x1=None, y1=None, tol=28):
    x1 = W if x1 is None else x1; y1 = H if y1 is None else y1
    minx=miny=10**9; maxx=maxy=-1; n=0
    for y in range(y0, y1):
        for x in range(x0, x1):
            if near(px[x, y], ref, tol):
                n += 1
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    return (minx, miny, maxx, maxy, n) if n else None

RED = (200, 90, 90); GREEN = (90, 200, 90); YELLOW = (240, 200, 60); BLUE = (90, 140, 240)
PANEL = (40, 40, 60); WHITE = (255, 255, 255)

# Split the image at the midline between the two red panels: A is left, B is right.
r = bbox(RED)
print("image %dx%d" % (W, H))
print("all red bbox", r)
mid = (r[0] + r[2]) // 2
A = bbox(RED, 0, 0, mid, H); B = bbox(RED, mid, 0, W, H)
def ext(b): return (b[2]-b[0]+1, b[3]-b[1]+1)
print("A red: bbox %s  extent %dx%d" % (A[:4], *ext(A)))
print("B red: bbox %s  extent %dx%d" % (B[:4], *ext(B)))
print("width_A/width_B = %d/%d   height_A/height_B = %d/%d" % (ext(A)[0], ext(B)[0], ext(A)[1], ext(B)[1]))

# Scene scale from panel A's background: widest horizontal run of PANEL colour on the row just
# above A's red (inside the panel margin), and tallest vertical run on the column just left of it.
row = A[1] - 4
runs = []; cur = 0; start = None
for x in range(W):
    if near(px[x, row], PANEL):
        if cur == 0: start = x
        cur += 1
    else:
        if cur: runs.append((cur, start)); cur = 0
if cur: runs.append((cur, start))
runs.sort(reverse=True)
pa_w = runs[0]
col = A[0] - 4
runs = []; cur = 0; start = None
for y in range(H):
    if near(px[col, y], PANEL):
        if cur == 0: start = y
        cur += 1
    else:
        if cur: runs.append((cur, start)); cur = 0
if cur: runs.append((cur, start))
runs.sort(reverse=True)
pa_h = runs[0]
print("panel A background run: %d px wide (x from %d), %d px tall (y from %d)" % (pa_w[0], pa_w[1], pa_h[0], pa_h[1]))
sx = pa_w[0] / 161.0; sy = pa_h[0] / 125.0
print("scale: %.4f px/scene-px horizontally, %.4f vertically" % (sx, sy))
for name, b in (("A", A), ("B", B)):
    w, h = ext(b)
    print("%s red in scene px: %.2f x %.2f ; left edge %.2f from panel, top edge %.2f from panel"
          % (name, w/sx, h/sy, (b[0]-(pa_w[1] if name=="A" else pa_w[1]))/sx, (b[1]-pa_h[1])/sy))

sy = sx  # uniform GUI scaling; see the docstring
for name, ref in (("C green", GREEN), ("D yellow", YELLOW), ("E blue", BLUE)):
    b = bbox(ref)
    if b:
        w, h = ext(b)
        print("%s: bbox %s extent %dx%d -> scene %.2f x %.2f" % (name, b[:4], w, h, w/sx, h/sy))
    else:
        print("%s: NOT FOUND" % name)
# C: offset of the green from its panel's top-left (the panel is the third PANEL-coloured region)
g = bbox(GREEN)
if g is None:
    print("no green found: not a cliptest panel view (the inventory shot, or the terminal)")
    sys.exit(0)
# find panel C's left/top: nearest PANEL run containing the green's row
row = g[1] - 4
xs = [x for x in range(g[0], -1, -1) if not near(px[x, row], PANEL)]
cl = xs[0] + 1 if xs else None
col = g[0] - 4
ys = [y for y in range(g[1], -1, -1) if not near(px[col, y], PANEL)]
ct = ys[0] + 1 if ys else None
if cl is not None and ct is not None:
    print("C green top-left offset from panel C: (%.2f, %.2f) scene px" % ((g[0]-cl)/sx, (g[1]-ct)/sy))
# white markers and text: count white bboxes coarse
wb = bbox(WHITE, tol=10)
print("white pixels bbox (markers+text):", wb)
