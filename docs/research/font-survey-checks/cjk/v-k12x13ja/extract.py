import sys, tarfile, os
# usage: extract.py <archive> <destdir> [member-substring ...]
arc, dest = sys.argv[1], sys.argv[2]
subs = sys.argv[3:]
os.makedirs(dest, exist_ok=True)
with tarfile.open(arc) as t:
    mem = t.getmembers()
    if subs:
        mem = [m for m in mem if any(s in m.name for s in subs)]
    for m in mem:
        print(m.name, m.size, m.mtime)
    t.extractall(dest, members=mem, filter='data')
