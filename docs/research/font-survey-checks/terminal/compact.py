"""Compact view of fontmeasure.py JSON lines read from stdin (data only)."""
import sys, json
for line in sys.stdin:
    line = line.strip()
    if not line.startswith('{'): continue
    d = json.loads(line)
    if 'error' in d: print(d['file'], 'ERROR', d['error']); continue
    c = d['coverage']
    keys = ['ascii','latin1','latinExtA','greekLetters','cyrillic','genPunct','arrows','boxDrawing','blocks','geometric','braille','oc351','cjkUnified']
    p = d.get('props', {})
    print('%s: %d glyphs, mono=%s adv=%s %s lh=%s bbxH=%s over=%d FFFD=%s | %s | %s' % (d['file'], d['glyphs'], d['monospace'], d['commonAdvance'], d['advances'], d['lineHeight'], d['bbxHeights'], d['glyphsWiderThanAdvance'], d['hasFFFD'],
        ' '.join('%s %s' % (k, c[k]) for k in keys), {k: p[k] for k in p if k in ('FONTBOUNDINGBOX','SPACING','COPYRIGHT','FONT_ASCENT','FONT_DESCENT','psfCell')}))
