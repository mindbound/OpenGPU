# usage: python -I unzip.py ZIP OUTDIR -- list and extract a zip safely (no absolute paths / .. members)
import sys, zipfile, os, hashlib
z = zipfile.ZipFile(sys.argv[1]); out = os.path.abspath(sys.argv[2])
for i in z.infolist():
    p = os.path.abspath(os.path.join(out, i.filename))
    if not p.startswith(out + os.sep): print('SKIP', i.filename); continue
    if i.is_dir(): continue
    z.extract(i, out)
    print(i.filename, i.file_size, hashlib.sha256(open(p, 'rb').read()).hexdigest())
