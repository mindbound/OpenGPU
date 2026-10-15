# Strip HTML to text with a given codec; print lines containing any of the keywords with context
import sys, re, html
p, codec = sys.argv[1], sys.argv[2]
kws = sys.argv[3:]
t = open(p, 'rb').read().decode(codec, errors='replace')
t = re.sub(r'(?is)<script.*?</script>|<style.*?</style>', '', t)
t = re.sub(r'(?i)<br\s*/?>|</p>|</div>|</li>|</tr>', '\n', t)
t = re.sub(r'<[^>]+>', '', t)
t = html.unescape(t)
lines = [l.rstrip() for l in t.splitlines() if l.strip()]
if not kws:
    print('\n'.join(lines)); sys.exit()
hit = set()
for i, l in enumerate(lines):
    if any(k in l for k in kws):
        for j in range(max(0, i-3), min(len(lines), i+4)):
            hit.add(j)
for j in sorted(hit):
    print(f"{j}: {lines[j]}")
