"""Extract a tar/zip archive into a destination directory with safe filters.

Usage: python -I -X utf8 extract.py <archive> <destdir> [member-substring ...]
If member substrings are given, only members whose name contains one of them
are extracted.
"""
import sys
import tarfile
import zipfile
import os


def main():
    arc, dest = sys.argv[1], sys.argv[2]
    subs = sys.argv[3:]
    os.makedirs(dest, exist_ok=True)
    if zipfile.is_zipfile(arc):
        with zipfile.ZipFile(arc) as z:
            names = [n for n in z.namelist() if not subs or any(s in n for s in subs)]
            for n in names:
                if n.startswith('/') or '..' in n.split('/'):
                    print('skip unsafe', n)
                    continue
                z.extract(n, dest)
            print('extracted', len(names))
    else:
        with tarfile.open(arc) as t:
            members = [m for m in t.getmembers() if not subs or any(s in m.name for s in subs)]
            t.extractall(dest, members=members, filter='data')
            print('extracted', len(members))


if __name__ == '__main__':
    main()
