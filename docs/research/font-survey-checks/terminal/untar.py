"""List or extract an archive safely. Usage: python -I untar.py list ARCHIVE | python -I untar.py extract ARCHIVE DESTDIR [member-substring ...]"""
import sys, tarfile, zipfile, os
mode, arc = sys.argv[1], sys.argv[2]
if arc.endswith('.zip'):
    z = zipfile.ZipFile(arc)
    names = z.namelist()
    if mode == 'list':
        for i in z.infolist(): print(i.file_size, i.filename)
    else:
        dest = sys.argv[3]; pats = sys.argv[4:]
        for n in names:
            if n.endswith('/') or (pats and not any(p in n for p in pats)): continue
            target = os.path.realpath(os.path.join(dest, n))
            if not target.startswith(os.path.realpath(dest) + os.sep): print('skip', n); continue
            z.extract(n, dest); print('x', n)
else:
    t = tarfile.open(arc)
    if mode == 'list':
        for m in t.getmembers(): print(m.size, m.name)
    else:
        dest = sys.argv[3]; pats = sys.argv[4:]
        ms = [m for m in t.getmembers() if m.isfile() and (not pats or any(p in m.name for p in pats))]
        t.extractall(dest, members=ms, filter='data')
        for m in ms: print('x', m.name)
