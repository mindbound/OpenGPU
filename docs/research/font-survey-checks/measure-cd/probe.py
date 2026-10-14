"""usage: python -I probe.py FILE [--hex-height N] -- missing OC351 code points, advance per sample code point, odd advances."""
import sys, os, collections, importlib.util
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
args = sys.argv[1:]; hh = 16
if '--hex-height' in args: i = args.index('--hex-height'); hh = int(args[i + 1]); del args[i:i + 2]
f = args[0]; data = fm.opener(f).read(); base = f[:-3] if f.endswith('.gz') else f; ext = os.path.splitext(base)[1].lower()
g = (fm.read_bdf(data) if ext == '.bdf' else fm.read_hex(data, hh))[0]
miss = [c for c in fm.TARGETS['oc351'] if c not in g]
adv = collections.Counter(g[c].get('dw') for c in g)
common = collections.Counter(g[c].get('dw') for c in range(0x21, 0x7F) if c in g).most_common(1)[0][0]
odd = ['%04X:%s' % (c, g[c].get('dw')) for c in sorted(g) if g[c].get('dw') not in (common, 2 * common)]
samples = {name: collections.Counter(g[c].get('dw') for c in range(lo, hi + 1) if c in g) for name, lo, hi in
           (('box2500', 0x2500, 0x257F), ('blocks2580', 0x2580, 0x259F), ('arrows2190', 0x2190, 0x21FF), ('geom25A0', 0x25A0, 0x25FF), ('braille', 0x2800, 0x28FF), ('greek', 0x391, 0x3C9), ('cyr', 0x400, 0x45F), ('latin1', 0xA0, 0xFF))}
print({'file': os.path.basename(f), 'missingOC351': ['%04X' % c for c in miss], 'oddAdvances': odd[:12], 'nOdd': len(odd), 'advPerBlock': {k: dict(v) for k, v in samples.items() if v}})
