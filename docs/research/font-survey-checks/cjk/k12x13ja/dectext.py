# Decode a text file with a given codec (errors replaced), print lines a..b
import sys
p, codec = sys.argv[1], sys.argv[2]
a = int(sys.argv[3]) if len(sys.argv) > 3 else 1
b = int(sys.argv[4]) if len(sys.argv) > 4 else 10**9
data = open(p, 'rb').read()
txt = data.decode(codec, errors='replace')
for i, line in enumerate(txt.splitlines(), 1):
    if a <= i <= b:
        print(f"{i}: {line}")
