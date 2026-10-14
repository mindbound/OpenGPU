import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
filt = sys.argv[2].lower() if len(sys.argv) > 2 else ''
print(d.get('tag_name'), d.get('published_at'), d.get('target_commitish'))
for a in d.get('assets', []):
    if filt in a['name'].lower():
        print(a['name'], a['size'], a['browser_download_url'])
