# Extract a tar or zip archive into a NEW empty directory, safely.
import sys, os, tarfile, zipfile
arc, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
if os.listdir(dest):
    sys.exit("dest not empty: " + dest)
if zipfile.is_zipfile(arc):
    with zipfile.ZipFile(arc) as z:
        for n in z.namelist():
            p = os.path.realpath(os.path.join(dest, n))
            if not p.startswith(os.path.realpath(dest)):
                sys.exit("bad path " + n)
        z.extractall(dest)
        names = z.namelist()
else:
    with tarfile.open(arc) as t:
        t.extractall(dest, filter='data')
        names = t.getnames()
for n in names:
    print(n)
