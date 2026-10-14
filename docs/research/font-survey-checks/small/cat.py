# usage: python -I cat.py FILE [maxlines] -- print a text file, trying utf-8, shift_jis, euc-jp (data only)
import sys
sys.stdout.reconfigure(encoding='utf-8')
raw = open(sys.argv[1], 'rb').read()
for e in ('utf-8', 'shift_jis', 'euc-jp', 'latin-1'):
    try: t = raw.decode(e); break
    except UnicodeDecodeError: pass
n = int(sys.argv[2]) if len(sys.argv) > 2 else 10**9
print('--- %s [%s]' % (sys.argv[1].split('/')[-1], e)); print('\n'.join(t.splitlines()[:n]))
