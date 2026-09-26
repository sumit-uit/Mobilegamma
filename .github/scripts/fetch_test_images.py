"""Downloads a few cake and non-cake photos from Wikimedia Commons for the emulator test."""
import json
import os
import sys
import urllib.parse
import urllib.request

UA = "CakeSyncCI/0.1 (https://github.com/sumit-uit/mobilegamma)"
OUT = sys.argv[1] if len(sys.argv) > 1 else "test-images"
SETS = {
    "cake": ["Category:Birthday cakes", "Category:Chocolate cakes", "Category:Cakes"],
    "other": ["Category:Labrador Retrievers", "Category:Beaches", "Category:Bicycles"],
}
PER_SET = 4


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as res:
        return res.read()


def image_urls(category, limit):
    params = {
        "action": "query", "format": "json", "generator": "categorymembers",
        "gcmtitle": category, "gcmtype": "file", "gcmlimit": "30",
        "prop": "imageinfo", "iiprop": "url|mime", "iiurlwidth": "640",
    }
    data = json.loads(get("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)))
    urls = []
    for page in data.get("query", {}).get("pages", {}).values():
        info = (page.get("imageinfo") or [{}])[0]
        if info.get("mime") == "image/jpeg" and info.get("thumburl"):
            urls.append(info["thumburl"])
    return urls[:limit]


os.makedirs(OUT, exist_ok=True)
counts = {}
for kind, categories in SETS.items():
    n = 0
    for category in categories:
        if n >= PER_SET:
            break
        try:
            for url in image_urls(category, PER_SET - n):
                with open(os.path.join(OUT, f"{kind}_{n + 1}.jpg"), "wb") as f:
                    f.write(get(url))
                print(f"{kind}_{n + 1}.jpg <- {url}")
                n += 1
        except Exception as e:  # keep going with other categories
            print(f"warning: {category}: {e}", file=sys.stderr)
    counts[kind] = n

print(counts)
if counts.get("cake", 0) == 0:
    sys.exit("No cake test images could be downloaded")
