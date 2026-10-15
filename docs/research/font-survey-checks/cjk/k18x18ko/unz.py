"""Decompress a Unix compress(1) .Z file (LZW) to an output file.

Usage: python -I -X utf8 unz.py <in.Z> <out>
Standard library only; mirrors ncompress's decoder, including the
code-group padding that compress(1) emits when the code width changes
or a CLEAR code is seen.
"""
import sys


def unz(data):
    if data[:2] != b'\x1f\x9d':
        raise ValueError('not a .Z file')
    flags = data[2]
    maxbits = flags & 0x1f
    block = bool(flags & 0x80)
    pos = 3 * 8  # bit position
    nbits = 9
    maxcode = (1 << nbits) - 1
    maxmaxcode = 1 << maxbits
    first = 257 if block else 256
    prefix = [0] * maxmaxcode
    suffix = bytearray(maxmaxcode)
    for i in range(256):
        suffix[i] = i
    free = first
    out = bytearray()
    total_bits = len(data) * 8
    # ncompress reads codes in groups of nbits bytes (8 codes per group).
    group_start = pos
    oldcode = -1
    finchar = 0

    def get_string(code):
        s = bytearray()
        while code >= 256:
            s.append(suffix[code])
            code = prefix[code]
        s.append(code)
        s.reverse()
        return s

    while True:
        if free > maxcode and nbits < maxbits or False:
            pass
        if pos + nbits > total_bits:
            break
        # read code
        byte_i = pos >> 3
        chunk = int.from_bytes(data[byte_i:byte_i + 3].ljust(3, b'\0'), 'little')
        code = (chunk >> (pos & 7)) & ((1 << nbits) - 1)
        pos += nbits
        if oldcode == -1:
            if code >= 256:
                raise ValueError('bad first code')
            out.append(code)
            oldcode = code
            finchar = code
            continue
        if code == 256 and block:
            # CLEAR: skip to end of current code group, reset
            groupbits = nbits * 8
            used = (pos - group_start) % groupbits
            if used:
                pos += groupbits - used
            free = first - 1
            nbits = 9
            maxcode = (1 << nbits) - 1
            group_start = pos
            # next code is read with oldcode semantics restart
            oldcode = -2
            continue
        if oldcode == -2:
            if code >= 256:
                raise ValueError('bad code after clear')
            out.append(code)
            oldcode = code
            finchar = code
            free = first
            continue
        incode = code
        if code >= free:
            if code > free:
                raise ValueError('corrupt input at bit %d' % pos)
            s = get_string(oldcode)
            s.append(finchar)
        else:
            s = get_string(code)
        finchar = s[0]
        out += s
        if free < maxmaxcode:
            prefix[free] = oldcode
            suffix[free] = finchar
            free += 1
        oldcode = incode
        if free > maxcode and nbits < maxbits:
            # width change: skip to end of current group
            groupbits = nbits * 8
            used = (pos - group_start) % groupbits
            if used:
                pos += groupbits - used
            nbits += 1
            maxcode = (1 << nbits) - 1 if nbits < maxbits else maxmaxcode
            group_start = pos
    return bytes(out)


def main():
    with open(sys.argv[1], 'rb') as f:
        data = f.read()
    out = unz(data)
    with open(sys.argv[2], 'wb') as f:
        f.write(out)
    print(len(data), '->', len(out))


if __name__ == '__main__':
    main()
