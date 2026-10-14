"""usage: python -I props.py FILE.bdf[.gz] -- print the BDF header up to ENDPROPERTIES, as UTF-8-decoded ASCII-escaped text."""
import sys, gzip
f = sys.argv[1]; data = (gzip.open(f) if f.endswith('.gz') else open(f, 'rb')).read(200000)
for line in data.split(b'ENDPROPERTIES')[0].decode('utf-8', 'replace').splitlines():
    print(line.encode('ascii', 'backslashreplace').decode('ascii'))
