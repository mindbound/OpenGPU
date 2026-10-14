"""usage: python -I unzip.py ARCHIVE DESTDIR [--list] [substring...]  -- safe extract of zip/tar members (data only)."""
import sys, os, zipfile, tarfile
arc, dest = sys.argv[1], sys.argv[2]
args = sys.argv[3:]
listonly = '--list' in args
pats = [a for a in args if a != '--list']
destabs = os.path.abspath(dest)
def ok(name):
    p = os.path.abspath(os.path.join(destabs, name))
    return p.startswith(destabs + os.sep) and (not pats or any(s in name for s in pats))
if zipfile.is_zipfile(arc):
    z = zipfile.ZipFile(arc)
    for i in z.infolist():
        if listonly: print(i.filename, i.file_size); continue
        if not i.is_dir() and ok(i.filename):
            z.extract(i, destabs); print('x', i.filename, i.file_size)
else:
    t = tarfile.open(arc)
    for m in t:
        if listonly: print(m.name, m.size); continue
        if m.isfile() and ok(m.name):
            t.extract(m, destabs, filter='data'); print('x', m.name, m.size)
