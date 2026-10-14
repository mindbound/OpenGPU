"""Per-file sha256 manifest of the font-survey folder (own code, MIT with the repo).

Usage: python -I manifest.py <font-survey dir> <small downloads.tsv> <measure-cd downloads.tsv> <out.tsv>
Reads files as bytes only; executes nothing.
"""
import hashlib
import os
import sys

FOLDER_SOURCES = {
    "unscii": "https://github.com/viznut/unscii @257b4dfeb50651510fe8b9cf4f151a148a4490e4 (raw.githubusercontent.com)",
    "funscii": "https://github.com/asiekierka/funscii opencomputers @233661e9bbb6eb2852d32bda3573c78b2006ec58; master @397417bbfc9fba765bc3c99e05554be685f8f155",
    "spleen": "https://github.com/fcambus/spleen tag 2.2.0 = 0493c34e22791824767c618fed42c434b477662c",
    "terminus": "https://downloads.sourceforge.net/project/terminus-font/terminus-font-4.49/terminus-font-4.49.1.tar.gz",
    "misc-fixed": "https://www.cl.cam.ac.uk/~mgk25/download/ucs-fonts.tar.gz (Last-Modified 2009-04-06); COPYING from https://sources.debian.org/data/main/x/xfonts-base/1:1.0.5+nmu1/font-misc-misc/COPYING",
    "uw-ttyp0": "https://people.mpi-inf.mpg.de/~uwe/misc/uw-ttyp0/uw-ttyp0-2.1.tar.gz",
    "cozette": "https://github.com/the-moonwitch/Cozette tag v.1.30.0 (release asset cozette.bdf; LICENSE, README.md at the tag)",
    "dina": "https://www.dcmembers.com/jibsen/download/61/ (author page, licence text only)",
    "leggie": "https://github.com/wikkyk/leggie master @05aeb2867ade1fce43bb49aa78c76fae7fc9fd76",
    "tamzen": "https://github.com/sunaku/tamzen-font tag Tamzen-1.11.6 = caa2a5dcf65f82deba1c08cfc7147d76b807eeb6",
    "unifont-17": "https://ftp.gnu.org/gnu/unifont/unifont-17.0.05/unifont_all-17.0.05.hex.gz; https://unifoundry.com/LICENSE.txt",
    "unifont-15": "https://ftp.gnu.org/gnu/unifont/unifont-15.1.05/unifont_all-15.1.05.hex.gz (OC provenance diff only)",
    "shinonome": "https://sources.debian.org/data/main/x/xfonts-shinonome/1:0.9.11-7/ (licence files only)",
    "licence-refs": "licence critic: openfontlicense.org (OFL text, OFL-FAQ), gnu.org gpl-faq, creativecommons.org (BY-SA 4.0, FAQ), unifoundry.com/unifont, upstream licence files",
    "ark-pixel-font": "ark-pixel-font-{10,12}px-monospaced-bdf-v2026.09.25.zip in this folder (GitHub release 2026.09.25)",
    "misc-fixed-cjk": "ucs-fonts-asian.tar.gz in this folder (https://www.cl.cam.ac.uk/~mgk25/download/ucs-fonts-asian.tar.gz)",
    "mplus-bitmap": "xfonts-mplus_2.2.4.orig.tar.xz in this folder (Debian pool)",
    "retro-pixel-font": "retro-pixel-font-bdf-v2026.09.18.zip in this folder (GitHub release 2026.09.18)",
    "misaki-k6x8": "misaki_bdf_2021-05-05.zip and k6x8_bdf_2023-10-19.zip in this folder (littlelimit.net)",
    "unscii-critic-compare":"viznut/unscii @257b4df src/font-pc16.txt, src/font-arcade.txt (comparison only, never ship)",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    root, small_tsv, cd_tsv, out = sys.argv[1:5]
    root = os.path.abspath(root)
    urls = {}
    with open(small_tsv, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3:
                urls[os.path.normcase(os.path.join(root, p[0], p[1].replace("/", os.sep)))] = p[2]
    with open(cd_tsv, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2 and p[0].startswith("http"):
                urls[os.path.normcase(os.path.abspath(p[1]))] = p[0]
    rows = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel_dir = os.path.relpath(dirpath, root)
        if rel_dir.split(os.sep)[0] == "_previews":
            continue
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            folder = rel.split("/")[0]
            key = os.path.normcase(os.path.abspath(full))
            low = rel.lower()
            if key in urls:
                kind, src = "download", urls[key]
            elif "/converted" in low or "/derived-" in low or low.split("/")[-1].startswith("derived-"):
                kind, src = "derived (own conversion, measurement only)", ""
            elif "/x/" in low or "/x-" in low or "/terminus-font-4.49.1/" in low or "/uw-ttyp0-2.1/" in low:
                kind, src = "extracted from the folder's archive", FOLDER_SOURCES.get(folder, "")
            else:
                kind, src = "download", FOLDER_SOURCES.get(folder, "see the measurer's notes")
            rows.append((rel, os.path.getsize(full), sha256(full), kind, src))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("path\tbytes\tsha256\tkind\tsource\n")
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(len(rows), "files")


if __name__ == "__main__":
    main()
