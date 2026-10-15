import sys
# usage: dec.py <file> [encodings...]; prints decoded text with the first encoding that works
f = sys.argv[1]
encs = sys.argv[2:] or ['utf-8', 'iso2022_jp', 'euc_jp', 'cp932']
data = open(f, 'rb').read()
for e in encs:
    try:
        t = data.decode(e)
        print('== decoded as', e)
        sys.stdout.write(t)
        break
    except Exception as ex:
        print('== fail', e, ex)
