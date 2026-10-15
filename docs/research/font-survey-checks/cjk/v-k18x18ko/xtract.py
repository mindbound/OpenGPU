"""Extract an archive into a new, empty directory.
Usage: xtract.py <archive> <outdir>
Supports .tar.gz/.tgz/.tar (tarfile, filter='data') and .Z (Unix compress, own LZW decoder) wrapping a tar.
"""
import sys, os, io, tarfile


def unlzw(data):
    if data[:2] != b'\x1f\x9d':
        raise ValueError('not a .Z file')
    flags = data[2]
    maxbits = flags & 0x1f
    block = bool(flags & 0x80)
    pos = 3
    out = bytearray()
    nbits = 9
    table = [bytes([i]) for i in range(256)]
    if block:
        table.append(b'')  # 256 = CLEAR
    free_ent = len(table)
    bitbuf = 0
    bitcnt = 0
    prev = None
    # compress reads codes in groups of nbits*8 bits; on code-width change/clear it discards to group boundary
    group_start_bitpos = 0
    bitpos = 0
    total_bits = (len(data) - pos) * 8
    src = data[pos:]

    def read_code(bp, nb):
        byte = bp >> 3
        off = bp & 7
        val = 0
        for i in range(3):
            if byte + i < len(src):
                val |= src[byte + i] << (8 * i)
        return (val >> off) & ((1 << nb) - 1)

    while bitpos + nbits <= total_bits:
        code = read_code(bitpos, nbits)
        bitpos += nbits
        if block and code == 256:
            # skip to end of current group
            gbits = nbits * 8
            used = bitpos - group_start_bitpos
            bitpos = group_start_bitpos + ((used + gbits - 1) // gbits) * gbits
            table = table[:257]
            free_ent = 257
            nbits = 9
            group_start_bitpos = bitpos
            prev = None
            continue
        if prev is None:
            entry = table[code]
            out += entry
            prev = entry
            continue
        if code < free_ent:
            entry = table[code]
            newent = prev + entry[:1]
        elif code == free_ent:
            entry = prev + prev[:1]
            newent = entry
        else:
            raise ValueError('bad code %d at bit %d' % (code, bitpos))
        out += entry
        if free_ent < (1 << maxbits):
            table.append(newent)
            free_ent += 1
        prev = entry
        if free_ent > (1 << nbits) - 1 and nbits < maxbits:
            gbits = nbits * 8
            used = bitpos - group_start_bitpos
            bitpos = group_start_bitpos + ((used + gbits - 1) // gbits) * gbits
            nbits += 1
            group_start_bitpos = bitpos
    return bytes(out)


def main():
    arc, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=False)
    with open(arc, 'rb') as f:
        data = f.read()
    if arc.endswith('.Z'):
        raw = unlzw(data)
        tf = tarfile.open(fileobj=io.BytesIO(raw), mode='r:')
    else:
        tf = tarfile.open(arc, mode='r:*')
    names = tf.getnames()
    tf.extractall(outdir, filter='data')
    print('extracted', len(names), 'members to', outdir)


if __name__ == '__main__':
    main()
