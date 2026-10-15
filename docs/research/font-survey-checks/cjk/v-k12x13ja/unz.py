"""Decode a Unix-compress (.Z, LZW) stream made of concatenated parts, then extract
selected tar members with tarfile (filter='data'). Verifier's own implementation.
usage: unz.py <destdir> <member-substring,...> <part1> [part2 ...]
"""
import sys, io, os, tarfile

def unlzw(data):
    assert data[0] == 0x1F and data[1] == 0x9D, 'not .Z'
    flags = data[2]
    maxbits = flags & 0x1F
    block = bool(flags & 0x80)
    body = data[3:]
    nbits_total = len(body) * 8
    out = bytearray()
    table = [bytes([i]) for i in range(256)]
    if block:
        table.append(b'')  # 256 = CLEAR placeholder
    n = 9
    maxcode = (1 << n) - 1
    maxmax = 1 << maxbits
    p = 0
    group = 0
    prev = None
    while True:
        if len(table) > maxcode and n < maxbits:
            step = n * 8
            p = group + -(-(p - group) // step) * step
            group = p
            n += 1
            maxcode = maxmax if n == maxbits else (1 << n) - 1
            # note: gzip sets maxcode=maxmaxcode at n==maxbits
        if p + n > nbits_total:
            break
        i = p >> 3
        chunk = body[i] | (body[i + 1] << 8 if i + 1 < len(body) else 0) | (body[i + 2] << 16 if i + 2 < len(body) else 0)
        code = (chunk >> (p & 7)) & ((1 << n) - 1)
        p += n
        if prev is None:
            out += table[code]
            prev = table[code]
            continue
        if block and code == 256:
            step = n * 8
            p = group + -(-(p - group) // step) * step
            group = p
            table = table[:257]
            n = 9
            maxcode = (1 << n) - 1
            prev = None
            continue
        if code < len(table):
            entry = table[code]
        elif code == len(table):
            entry = prev + prev[:1]
        else:
            raise ValueError('bad code %d at bit %d (table %d)' % (code, p, len(table)))
        out += entry
        if len(table) < maxmax:
            table.append(prev + entry[:1])
        prev = entry
    return bytes(out)

dest = sys.argv[1]
subs = sys.argv[2].split(',')
data = b''.join(open(f, 'rb').read() for f in sys.argv[3:])
raw = unlzw(data)
print('decompressed bytes', len(raw))
with tarfile.open(fileobj=io.BytesIO(raw)) as t:
    mem = [m for m in t.getmembers() if any(s in m.name for s in subs)]
    for m in mem:
        print(m.name, m.size, m.mtime)
    os.makedirs(dest, exist_ok=True)
    t.extractall(dest, members=mem, filter='data')
