"""usage: python -I cov.py [--hex-height N] [--list] FILE...  -- extra coverage (CJK, kana, Hangul, scripts) using the shared readers."""
import sys, os, json, collections, importlib.util
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
BLOCKS = {
 'hangulSyl AC00-D7A3': (0xAC00, 0xD7A3), 'hiragana 3040-309F': (0x3040, 0x309F), 'katakana 30A0-30FF': (0x30A0, 0x30FF),
 'kana 3040-30FF': (0x3040, 0x30FF), 'cjkSymPunct 3000-303F': (0x3000, 0x303F), 'cjkURO 4E00-9FFF': (0x4E00, 0x9FFF),
 'cjkExtA 3400-4DBF': (0x3400, 0x4DBF), 'halfFull FF00-FFEF': (0xFF00, 0xFFEF), 'hangulJamo 1100-11FF': (0x1100, 0x11FF),
 'compatJamo 3130-318F': (0x3130, 0x318F), 'bopomofo 3100-312F': (0x3100, 0x312F), 'greekAll 0370-03FF': (0x370, 0x3FF),
 'cyrAll 0400-04FF': (0x400, 0x4FF), 'armenian 0530-058F': (0x530, 0x58F), 'hebrew 0590-05FF': (0x590, 0x5FF),
 'arabic 0600-06FF': (0x600, 0x6FF), 'devanagari 0900-097F': (0x900, 0x97F), 'thai 0E00-0E7F': (0xE00, 0xE7F),
 'georgian 10A0-10FF': (0x10A0, 0x10FF), 'latinExtB 0180-024F': (0x180, 0x24F), 'latinExtAdd 1E00-1EFF': (0x1E00, 0x1EFF),
 'mathOps 2200-22FF': (0x2200, 0x22FF), 'miscTech 2300-23FF': (0x2300, 0x23FF), 'miscSym 2600-26FF': (0x2600, 0x26FF),
 'dingbats 2700-27BF': (0x2700, 0x27BF), 'legacyComp 1FB00-1FBFF': (0x1FB00, 0x1FBFF), 'PUA E000-F8FF': (0xE000, 0xF8FF),
 'plane1+ >=10000': (0x10000, 0x10FFFF), 'plane2 20000-2FFFF': (0x20000, 0x2FFFF),
}
def ranges(cps):
    out = []; s = p = None
    for c in sorted(cps):
        if s is None: s = p = c
        elif c == p + 1: p = c
        else: out.append((s, p)); s = p = c
    if s is not None: out.append((s, p))
    return ' '.join('%04X' % a if a == b else '%04X-%04X' % (a, b) for a, b in out)
def main(argv):
    hh = 16; files = []; lst = False; it = iter(argv)
    for a in it:
        if a == '--hex-height': hh = int(next(it))
        elif a == '--list': lst = True
        else: files.append(a)
    for f in files:
        data = fm.opener(f).read(); base = f[:-3] if f.endswith('.gz') else f; ext = os.path.splitext(base)[1].lower()
        g, props = fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, hh) if ext == '.hex' else fm.read_psf(data)
        cps = set(g)
        cov = {}
        for k, (a, b) in BLOCKS.items():
            n = sum(1 for c in cps if a <= c <= b)
            if n: cov[k] = '%d/%d' % (n, b - a + 1)
        wide = collections.Counter(g[c].get('dw') for c in cps if 0x4E00 <= c <= 0x9FFF or 0xAC00 <= c <= 0xD7A3 or 0x3040 <= c <= 0x30FF)
        res = {'file': os.path.basename(f), 'glyphs': len(cps), 'blocks': cov, 'cjkKanaHangulAdvances': dict(wide.most_common(4))}
        if lst:
            res['nonAsciiRanges'] = ranges(c for c in cps if c > 0x7E)[:3000]
        print(json.dumps(res, ensure_ascii=False))
main(sys.argv[1:])
