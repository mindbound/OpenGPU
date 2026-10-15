# Verify BSD sums of X11R4 split parts, concatenate, LZW-decompress (.Z, compress 4.0 semantics),
# and extract selected members from the resulting tar with tarfile's 'data' filter.
# usage: python -I -X utf8 unz_r4.py <dir with tape-1.NN and CHECKSUMS> <out dir (new, empty)> <member prefix>...
import sys, os, re, io, glob, tarfile, hashlib

src, dest = sys.argv[1], sys.argv[2]
prefixes = sys.argv[3:]


def bsd_sum(b):
    s = 0
    for c in b:
        s = (s >> 1) + ((s & 1) << 15)
        s = (s + c) & 0xFFFF
    return s, (len(b) + 1023) // 1024

parts = sorted(glob.glob(os.path.join(src, 'tape-1.[0-9][0-9]')))
cks = {}
for line in open(os.path.join(src, 'CHECKSUMS'), encoding='latin-1'):
    m = re.match(r'\s*(\d+)\s+(\d+)\s+(tape-1\.\d\d)\s*$', line)
    if m:
        cks[m.group(3)] = (int(m.group(1)), int(m.group(2)))
data = bytearray()
bad = 0
for p in parts:
    b = open(p, 'rb').read()
    s = bsd_sum(b)
    name = os.path.basename(p)
    ok = cks.get(name) == s
    bad += not ok
    data += b
print('parts', len(parts), 'BSD sum mismatches', bad, 'of', len(cks), 'listed; total bytes', len(data))
print('sha256 of concatenation', hashlib.sha256(data).hexdigest())


def unlzw(d):
    if d[0:2] != b'\x1f\x9d':
        raise ValueError('not .Z')
    flags = d[2]
    maxbits = flags & 0x1F
    block = bool(flags & 0x80)
    maxmaxcode = 1 << maxbits
    INIT = 9
    CLEAR, FIRST = 256, 257
    prefix = [0] * maxmaxcode
    suffix = bytearray(maxmaxcode)
    for i in range(256):
        suffix[i] = i
    st = {'n_bits': INIT, 'maxcode': (1 << INIT) - 1, 'free_ent': FIRST if block else 256,
          'clear_flg': 0, 'pos': 3, 'buf': b'', 'offset': 0, 'size': 0}
    n = len(d)

    def getcode():
        if st['clear_flg'] > 0 or st['offset'] >= st['size'] or st['free_ent'] > st['maxcode']:
            if st['free_ent'] > st['maxcode']:
                st['n_bits'] += 1
                st['maxcode'] = maxmaxcode if st['n_bits'] == maxbits else (1 << st['n_bits']) - 1
            if st['clear_flg'] > 0:
                st['n_bits'] = INIT
                st['maxcode'] = (1 << INIT) - 1
                st['clear_flg'] = 0
            nb = st['n_bits']
            buf = d[st['pos']:st['pos'] + nb]
            st['pos'] += len(buf)
            if len(buf) <= 0:
                return -1
            st['buf'] = int.from_bytes(buf, 'little')
            st['offset'] = 0
            st['size'] = (len(buf) << 3) - (nb - 1)
        nb = st['n_bits']
        code = (st['buf'] >> st['offset']) & ((1 << nb) - 1)
        st['offset'] += nb
        return code

    out = bytearray()
    oldcode = getcode()
    if oldcode == -1:
        return bytes(out)
    finchar = oldcode
    out.append(finchar)
    while True:
        code = getcode()
        if code < 0:
            break
        if code == CLEAR and block:
            for c in range(256):
                prefix[c] = 0
            st['clear_flg'] = 1
            st['free_ent'] = FIRST - 1
            code = getcode()
            if code == -1:
                break
        incode = code
        stack = bytearray()
        if code >= st['free_ent']:
            stack.append(finchar)
            code = oldcode
        while code >= 256:
            stack.append(suffix[code])
            code = prefix[code]
        finchar = suffix[code]
        stack.append(finchar)
        stack.reverse()
        out += stack
        fe = st['free_ent']
        if fe < maxmaxcode:
            prefix[fe] = oldcode
            suffix[fe] = finchar
            st['free_ent'] = fe + 1
        oldcode = incode
    return bytes(out)

raw = unlzw(bytes(data))
print('decompressed bytes', len(raw))
os.makedirs(dest, exist_ok=True)
if os.listdir(dest):
    sys.exit('dest not empty')
with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as t:
    members = t.getmembers()
    print('tar members', len(members))
    sel = [m for m in members if any(m.name.lstrip('./').startswith(p) for p in prefixes)]
    t.extractall(dest, members=sel, filter='data')
    for m in sel:
        print('  extracted', m.name, m.size, m.mtime)
