"""Strip HTML to text and print windows around a keyword. Usage: python -I htmlgrep.py FILE KEYWORD [WIDTH] (data only)"""
import re, sys, html
sys.stdout.reconfigure(encoding='utf-8')
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
t = re.sub(r'<script.*?</script>|<style.*?</style>', '', s, flags=re.S)
t = re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', ' ', t)))
w = int(sys.argv[3]) if len(sys.argv) > 3 else 600
for m in list(re.finditer(sys.argv[2], t, flags=re.I))[:6]:
    print('...', t[max(0, m.start() - w):m.end() + w], '...\n')
