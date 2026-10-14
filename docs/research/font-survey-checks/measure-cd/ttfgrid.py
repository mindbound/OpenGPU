"""Minimal TrueType (glyf) reader: checks whether an outline font is drawn on an integer pixel grid
(all points on-curve, all edges axis-aligned, all coordinates multiples of one unit) and, if --bdf OUT is given,
rasterises it 1 unit = 1 px (pixel-centre sampling, nonzero winding) into a BDF for measurement.
usage: python -I ttfgrid.py FONT.ttf [--unit U] [--bdf OUT.bdf]   -- reads data only."""
import sys, struct, json, math, collections


def u16(b, o): return struct.unpack_from('>H', b, o)[0]
def i16(b, o): return struct.unpack_from('>h', b, o)[0]
def u32(b, o): return struct.unpack_from('>I', b, o)[0]


def tables(b):
    n = u16(b, 4); t = {}
    for i in range(n):
        o = 12 + 16 * i
        t[b[o:o + 4].decode('latin-1')] = (u32(b, o + 8), u32(b, o + 12))
    return t


def read_cmap(b, off):
    n = u16(b, off + 2); subs = {}
    for i in range(n):
        o = off + 4 + 8 * i
        subs[(u16(b, o), u16(b, o + 2))] = off + u32(b, o + 4)
    m = {}
    for key in [(3, 10), (0, 4), (3, 1), (0, 3), (0, 6), (0, 1), (0, 0)]:
        if key not in subs:
            continue
        s = subs[key]; fmt = u16(b, s)
        if fmt == 12:
            ng = u32(b, s + 12)
            for g in range(ng):
                o = s + 16 + 12 * g
                a, z, gid = u32(b, o), u32(b, o + 4), u32(b, o + 8)
                for c in range(a, z + 1):
                    m.setdefault(c, gid + c - a)
        elif fmt == 4:
            seg = u16(b, s + 6) // 2
            e = s + 14; st = e + 2 * seg + 2; dl = st + 2 * seg; ro = dl + 2 * seg
            for k in range(seg):
                end, start, delta, rof = u16(b, e + 2 * k), u16(b, st + 2 * k), i16(b, dl + 2 * k), u16(b, ro + 2 * k)
                for c in range(start, end + 1):
                    if c == 0xFFFF:
                        continue
                    if rof == 0:
                        gid = (c + delta) & 0xFFFF
                    else:
                        gid = u16(b, ro + 2 * k + rof + 2 * (c - start))
                        if gid:
                            gid = (gid + delta) & 0xFFFF
                    if gid:
                        m.setdefault(c, gid)
        else:
            continue
        if m:
            break
    return m


class Font:
    def __init__(self, b):
        self.b = b; t = tables(b); self.t = t
        h = t['head'][0]; self.upm = u16(b, h + 18); self.locfmt = i16(b, h + 50)
        self.ng = u16(b, t['maxp'][0] + 4)
        hh = t['hhea'][0]; self.asc = i16(b, hh + 4); self.desc = i16(b, hh + 6); nhm = u16(b, hh + 34)
        hm = t['hmtx'][0]
        self.adv = [u16(b, hm + 4 * min(i, nhm - 1)) for i in range(self.ng)]
        lo = t['loca'][0]
        if self.locfmt == 0:
            self.loca = [u16(b, lo + 2 * i) * 2 for i in range(self.ng + 1)]
        else:
            self.loca = [u32(b, lo + 4 * i) for i in range(self.ng + 1)]
        self.glyf = t['glyf'][0]; self.cmap = read_cmap(b, t['cmap'][0])
        self.os2 = None
        if 'OS/2' in t:
            o = t['OS/2'][0]; ver = u16(b, o)
            self.os2 = {'typoAsc': i16(b, o + 68), 'typoDesc': i16(b, o + 70), 'winAsc': u16(b, o + 74), 'winDesc': u16(b, o + 76)}
            if ver >= 2:
                self.os2.update({'xHeight': i16(b, o + 86), 'capHeight': i16(b, o + 88)})
        self.stats = collections.Counter()

    def contours(self, gid, depth=0):
        b = self.b; s = self.glyf + self.loca[gid]; e = self.glyf + self.loca[gid + 1]
        if s == e or depth > 8:
            return []
        nc = i16(b, s); p = s + 10
        if nc >= 0:
            ends = [u16(b, p + 2 * i) for i in range(nc)]; p += 2 * nc
            il = u16(b, p); p += 2 + il
            npts = ends[-1] + 1 if ends else 0
            flags = []
            while len(flags) < npts:
                f = b[p]; p += 1; flags.append(f)
                if f & 8:
                    r = b[p]; p += 1; flags.extend([f] * r)
            flags = flags[:npts]
            pos = [p]

            def coords(short, same):
                v = 0; out = []; q = pos[0]
                for f in flags:
                    if f & short:
                        d = b[q]; q += 1
                        v += d if f & same else -d
                    elif not f & same:
                        v += i16(b, q); q += 2
                    out.append(v)
                pos[0] = q
                return out
            xs = coords(2, 16); ys = coords(4, 32)
            res = []; st = 0
            for en in ends:
                res.append([(xs[i], ys[i], flags[i] & 1) for i in range(st, en + 1)]); st = en + 1
            return res
        res = []
        while True:
            fl = u16(b, p); gi = u16(b, p + 2); p += 4
            if fl & 1:
                a1, a2 = i16(b, p), i16(b, p + 2); p += 4
            else:
                a1, a2 = struct.unpack_from('>bb', b, p); p += 2
            scaled = False
            if fl & 8:
                p += 2; scaled = True
            elif fl & 0x40:
                p += 4; scaled = True
            elif fl & 0x80:
                p += 8; scaled = True
            if scaled:
                self.stats['scaledComponents'] += 1
            if not fl & 2:
                self.stats['pointMatchedComponents'] += 1; a1 = a2 = 0
            for c in self.contours(gi, depth + 1):
                res.append([(x + a1, y + a2, on) for x, y, on in c])
            if not fl & 0x20:
                break
        return res


def raster(cs, unit):
    """cs in font units; returns (rows top-down as int bitmasks, xmin, ymax, w, h) in pixels."""
    pts = [(x // unit, y // unit) for c in cs for x, y, _ in c]
    if not pts:
        return None
    xmin = min(p[0] for p in pts); xmax = max(p[0] for p in pts)
    ymin = min(p[1] for p in pts); ymax = max(p[1] for p in pts)
    edges = []
    for c in cs:
        q = [(x / unit, y / unit) for x, y, _ in c]
        for i in range(len(q)):
            (x0, y0), (x1, y1) = q[i], q[(i + 1) % len(q)]
            if y0 != y1:
                edges.append((x0, y0, x1, y1))
    rows = []
    for yy in range(ymax - 1, ymin - 1, -1):
        yc = yy + 0.5; xs = []
        for x0, y0, x1, y1 in edges:
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                xs.append((x0 + (yc - y0) * (x1 - x0) / (y1 - y0), 1 if y1 > y0 else -1))
        xs.sort(); row = 0; wind = 0; k = 0
        for xx in range(xmin, xmax):
            xc = xx + 0.5
            while k < len(xs) and xs[k][0] <= xc:
                wind += xs[k][1]; k += 1
            if wind != 0:
                row |= 1 << (xmax - 1 - xx)
        rows.append(row)
    return rows, xmin, ymax, xmax - xmin, ymax - ymin


def main(argv):
    path = argv[0]; unit = None; out = None; it = iter(argv[1:])
    for a in it:
        if a == '--unit':
            unit = int(next(it))
        elif a == '--bdf':
            out = next(it)
    f = Font(open(path, 'rb').read())
    gids = sorted(set(f.cmap.values()))
    g = 0; offc = 0; diag = 0; diagglyphs = []; allc = {}
    for gid in gids:
        cs = f.contours(gid); allc[gid] = cs
        for c in cs:
            for i, (x, y, on) in enumerate(c):
                g = math.gcd(g, x); g = math.gcd(g, y)
                if not on:
                    offc += 1
                x1, y1, _ = c[(i + 1) % len(c)]
                if x != x1 and y != y1:
                    diag += 1; diagglyphs.append(gid)
    ga = 0
    for gid in gids:
        ga = math.gcd(ga, f.adv[gid])
    cps_by_gid = collections.defaultdict(list)
    for c, gid in f.cmap.items():
        cps_by_gid[gid].append(c)
    unit = unit or math.gcd(g, ga)
    capH = None
    if ord('H') in f.cmap:
        cs = allc.get(f.cmap[ord('H')])
        if cs:
            capH = max(y for c in cs for _, y, _ in c) - min(y for c in cs for _, y, _ in c)
    offgrid = sum(1 for gid in gids for c in allc[gid] for x, y, _ in c if x % unit or y % unit)
    rep = {'file': path.replace('\\', '/').split('/')[-1], 'unitsPerEm': f.upm, 'mappedCodepoints': len(f.cmap),
           'glyphsChecked': len(gids), 'gcdCoords': g, 'gcdAdvances': ga, 'unit': unit,
           'pxPerEm': f.upm / unit if unit else None, 'offGridPoints': offgrid,
           'offGridGlyphs': sorted({'U+%04X' % min(cps_by_gid[gid]) + str([(x, y) for c in allc[gid] for x, y, _ in c if x % unit or y % unit][:4])
                                    for gid in gids if any(x % unit or y % unit for c in allc[gid] for x, y, _ in c)})[:10],
           'offCurvePoints': offc, 'nonAxisAlignedEdges': diag,
           'glyphsWithDiagonals(first20)': sorted({'U+%04X' % min(cps_by_gid[x]) for x in diagglyphs})[:20],
           'nGlyphsWithDiagonals': len(set(diagglyphs)),
           'capHeightPx_H': capH / unit if capH and unit else None,
           'hheaAscDescPx': [f.asc / unit, f.desc / unit] if unit else None,
           'os2': f.os2, 'componentStats': dict(f.stats)}
    print(json.dumps(rep))
    if out:
        a = math.ceil(f.asc / unit); d = math.ceil(-f.desc / unit)
        L = ['STARTFONT 2.1', 'FONT converted-from-' + rep['file'], 'SIZE %d 75 75' % (a + d),
             'FONTBOUNDINGBOX %d %d 0 %d' % (a + d, a + d, -d),
             'STARTPROPERTIES 3', 'FONT_ASCENT %d' % a, 'FONT_DESCENT %d' % d,
             'COPYRIGHT "converted for measurement only"', 'ENDPROPERTIES']
        body = []
        for c in sorted(f.cmap):
            gid = f.cmap[c]; cs = allc.get(gid)
            if cs is None:
                cs = f.contours(gid)
            ongrid = all(x % unit == 0 and y % unit == 0 for cc in cs for x, y, _ in cc)
            r = raster(cs, unit) if ongrid else None
            dw = f.adv[gid] // unit
            if r is None:
                rows, w, h, xo, yo = [], 0, 0, 0, 0
            else:
                rows, xmin, ymax, w, h = r; xo, yo = xmin, ymax - h
            nb = (w + 7) // 8
            body += ['STARTCHAR U+%04X' % c, 'ENCODING %d' % c, 'SWIDTH 500 0', 'DWIDTH %d 0' % dw,
                     'BBX %d %d %d %d' % (w, h, xo, yo), 'BITMAP']
            body += ['%0*X' % (nb * 2, row << (nb * 8 - w)) for row in rows] + ['ENDCHAR']
        L += ['CHARS %d' % len(f.cmap)] + body + ['ENDFONT']
        open(out, 'w', encoding='ascii').write('\n'.join(L) + '\n')


main(sys.argv[1:])
