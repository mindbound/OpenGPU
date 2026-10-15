"""HTML -> text with a given encoding; print lines containing any keyword (with context).
usage: h2t.py <file> <encoding> <ctx> kw1 [kw2 ...]"""
import sys, re, html
data = open(sys.argv[1], 'rb').read().decode(sys.argv[2], errors='replace')
t = re.sub(r'(?is)<(script|style).*?</\1>', '', data)
t = re.sub(r'(?i)<br\s*/?>|</p>|</tr>|</li>|</h\d>|</div>', '\n', t)
t = re.sub(r'<[^>]+>', '', t)
t = html.unescape(t)
lines = [l.strip() for l in t.splitlines() if l.strip()]
ctx = int(sys.argv[3])
kws = sys.argv[4:]
shown = set()
for i, l in enumerate(lines):
    if any(k in l for k in kws):
        for j in range(max(0, i - ctx), min(len(lines), i + ctx + 1)):
            if j not in shown:
                print(f'{j:4d}: {lines[j]}')
                shown.add(j)
        print('----')
