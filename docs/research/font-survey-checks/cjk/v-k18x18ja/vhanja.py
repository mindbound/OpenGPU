"""Are Daewoo's hanja an independent design? Compare hanglg16 hanja with
jiskan16 / j90-16 / gb16fs / gb16st at the same Unicode code point.
usage: python -I -X utf8 vhanja.py <hanglg16> name=codec:path ...
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vbdf  # noqa: E402

h, _ = vbdf.read_bdf(sys.argv[1])
hanja = {k: v for k, v in h.items() if (k >> 8) >= 0x4A}
hu, _ = vbdf.to_unicode(hanja, 'euc_kr')
print('Daewoo hanja mapped', len(hu))
for spec in sys.argv[2:]:
    name, rest = spec.split('=', 1)
    codec, path = rest.split(':', 1)
    g, _ = vbdf.read_bdf(path)
    gu, _ = vbdf.to_unicode(g, codec)
    common = [u for u in hu if u in gu and hu[u] and gu[u]]
    ex = sum(1 for u in common if hu[u] == gu[u])
    sh = sum(1 for u in common if hu[u] != gu[u] and vbdf.norm(hu[u])[0] == vbdf.norm(gu[u])[0])
    hd = [len(hu[u] ^ gu[u]) for u in common if hu[u] != gu[u]]
    print('%-10s common %5d identical %5d shift-identical %4d mean-dist %.1f' % (
        name, len(common), ex, sh, sum(hd) / len(hd) if hd else 0))
