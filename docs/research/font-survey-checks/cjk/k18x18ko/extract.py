"""Safely extract a tar/zip archive into a destination directory.

Usage: python -I -X utf8 extract.py <archive> <dest_dir>
tar: tarfile.extractall(filter='data'); zip: zipfile with path checks.
Also lists the members (name, size) to stdout.
"""
import os
import sys
import tarfile
import zipfile


def main():
    arc, dest = sys.argv[1], sys.argv[2]
    os.makedirs(dest, exist_ok=True)
    if zipfile.is_zipfile(arc):
        with zipfile.ZipFile(arc) as z:
            for info in z.infolist():
                target = os.path.realpath(os.path.join(dest, info.filename))
                if not target.startswith(os.path.realpath(dest) + os.sep) and target != os.path.realpath(dest):
                    print("SKIP unsafe", info.filename)
                    continue
                print(f"{info.file_size:10d} {info.filename}")
                z.extract(info, dest)
    else:
        with tarfile.open(arc) as t:
            for m in t.getmembers():
                print(f"{m.size:10d} {m.name}")
            t.extractall(dest, filter='data')


if __name__ == '__main__':
    main()
