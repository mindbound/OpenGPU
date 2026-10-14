"""ASCII-safe wrapper around the shared fontmeasure.py (same arguments); avoids cp1252 console errors."""
import sys, os, importlib.util
spec = importlib.util.spec_from_file_location('fm', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py'))
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
_print = print


def esc(s):
    return ''.join(c if ord(c) < 128 else '\\u%04x' % (ord(c) & 0xFFFF) for c in s)


def aprint(s, *a, **k):
    _print(esc(s) if isinstance(s, str) else s, *a, **k)


fm.print = aprint
fm.main(sys.argv[1:])
