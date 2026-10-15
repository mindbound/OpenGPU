"""Extract a tar archive with the 'data' filter into a new empty directory.
usage: python -I -X utf8 vextract.py <archive> <outdir> [member-substring ...]
"""
import os
import sys
import tarfile

arc, out = sys.argv[1], sys.argv[2]
subs = sys.argv[3:]
if os.path.exists(out) and os.listdir(out):
    sys.exit("outdir not empty: " + out)
os.makedirs(out, exist_ok=True)
with tarfile.open(arc) as tf:
    members = tf.getmembers()
    if subs:
        members = [m for m in members if any(s in m.name for s in subs)]
    tf.extractall(out, members=members, filter='data')
    for m in members:
        if m.isfile():
            print(m.name, m.size)
