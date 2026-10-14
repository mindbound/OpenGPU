# usage: python -I render2.py [--hex-height N] OUT.png SCALE FONTFILE
# Renders an ambiguity/HUD sample with the shared fontmeasure.py renderer (loaded from ../shared).
import sys, importlib.util, os
FM = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'shared', 'fontmeasure.py')
spec = importlib.util.spec_from_file_location('fontmeasure', FM); fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)
a = sys.argv[1:]; hh = 16
if a[0] == '--hex-height': hh = int(a[1]); a = a[2:]
fm.SAMPLE = ["0O oQ 1lI| il!j rn m cl d vv w",
             "5S 2Z 8B 6b 9g gpqy ,.;: '\"` ([{}])",
             "HP 20/20 X:-123 Y:64 Z:7 TPS 19.8",
             "RF 1,234,567/2.5M EU/t 128 #42 @3%",
             "Energy [||||||    ] 63% Status: OK",
             "minimum illumination rnrnrn mmm"]
print(fm.render(a[2], a[0], int(a[1]), hh))
