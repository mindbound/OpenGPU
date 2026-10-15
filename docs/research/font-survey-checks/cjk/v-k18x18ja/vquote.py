"""Check the investigator's ISAS quote against COPYING, byte for byte.
usage: python -I -X utf8 vquote.py <COPYING>
"""
import sys

QUOTE = """Copyright (C) 1988  The Institute of Software, Academia Sinica.

Correspondence Address:  P.O.Box 8718, Beijing, China 100080.

Permission to use, copy, modify, and distribute this software and
its documentation for any purpose and without fee is hereby granted,
provided that the above copyright notices appear in all copies and
that both those copyright notices and this permission notice appear
in supporting documentation, and that the name of "the Institute of
Software, Academia Sinica" not be used in advertising or publicity
pertaining to distribution of the software without specific, written
prior permission.  The Institute of Software, Academia Sinica,
makes no representations about the suitability of this software
for any purpose.  It is provided "as is" without express or
implied warranty.

THE INSTITUTE OF SOFTWARE, ACADEMIA SINICA, DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS, IN NO EVENT SHALL THE INSTITUTE OF
SOFTWARE, ACADEMIA SINICA, BE LIABLE FOR ANY SPECIAL, INDIRECT OR
CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
WITH THE USE OR PERFORMANCE OF THIS SOFTWARE."""

with open(sys.argv[1], encoding='latin-1') as f:
    txt = f.read()
lines = [ln.rstrip() for ln in txt.strip('\n').split('\n')]
q = QUOTE.split('\n')
print('COPYING lines', len(lines), 'quote lines', len(q))
bad = [(i, a, b) for i, (a, b) in enumerate(zip(lines, q)) if a != b]
print('mismatches', bad[:5])
print('exact (modulo trailing spaces)', not bad and len(lines) == len(q))
