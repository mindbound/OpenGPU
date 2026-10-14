# usage: python -I htmltext.py FILE [encoding] -- print links and visible text of an HTML file (data only)
import sys, re, html
sys.stdout.reconfigure(encoding="utf-8")
raw = open(sys.argv[1], 'rb').read()
enc = sys.argv[2] if len(sys.argv) > 2 else None
if not enc:
    m = re.search(rb'charset=["\']?([A-Za-z0-9_\-]+)', raw)
    enc = m.group(1).decode() if m else 'utf-8'
t = raw.decode(enc, 'replace')
print('ENCODING', enc)
for h in re.findall(r'href=["\']([^"\']+)', t): print('LINK', h)
t = re.sub(r'(?is)<(script|style).*?</\1>', '', t)
t = re.sub(r'(?s)<br\s*/?>|</p>|</tr>|</li>|</h\d>|</div>', '\n', t)
t = html.unescape(re.sub(r'(?s)<[^>]+>', '', t))
print(re.sub(r'\n\s*\n+', '\n', t))
