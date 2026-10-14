import sys, json
keys = sys.argv[1:] or ['FONTBOUNDINGBOX', 'PIXEL_SIZE', 'FONT_ASCENT', 'FONT_DESCENT', 'COPYRIGHT', 'WEIGHT_NAME', 'SPACING']
for l in sys.stdin:
    d = json.loads(l)
    if 'props' in d: d['props'] = {k: d['props'].get(k) for k in keys if k in d['props']}
    c = d.pop('coverage', None)
    if c: d['cov'] = ' '.join('%s %s' % (k, v) for k, v in c.items())
    print(json.dumps(d, ensure_ascii=False))
