"""Builds two controlled test photos from fixed Wikimedia Commons images:

  zz_cake_with_person.jpg  a cake with a person beside it -> must be skipped (faces > 0)
  zz_cake_face_topper.jpg  a cake with a face printed on top -> must NOT be skipped (faces == 0)

If a download fails the photos are simply not created and the checks report SKIP.
"""
import io
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image

UA = "CakeSyncCI/0.1 (https://github.com/sumit-uit/mobilegamma)"
folder = sys.argv[1] if len(sys.argv) > 1 else "test-images"
CAKE = "-Cake_-birthday_celebration_-Friends_-Fun_-memoryful_moment_-Delicious.jpg"  # white birthday cake
FACE = "Albert_Einstein_Head.jpg"  # public-domain frontal portrait


def fetch(title, width):
    url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(title) + f"?width={width}"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as res:
        return Image.open(io.BytesIO(res.read())).convert("RGB")


try:
    cake = fetch(CAKE, 960)
    face = fetch(FACE, 480)
except Exception as e:  # network or file problem: skip the controlled checks
    print(f"warning: could not build composite test photos: {e}")
    sys.exit(0)

# 1) the cake on the left, the person on the right at the same height
person = face.resize((int(face.width * cake.height / face.height), cake.height))
combo = Image.new("RGB", (cake.width + person.width, cake.height), "white")
combo.paste(cake, (0, 0))
combo.paste(person, (cake.width, 0))
combo.save(os.path.join(folder, "zz_cake_with_person.jpg"), quality=92)

# 2) the face as a small printed photo in the middle of the cake
size = int(min(cake.width, cake.height) * 0.3)
small = face.resize((size, int(size * face.height / face.width)))
topper = cake.copy()
topper.paste(small, ((cake.width - small.width) // 2, (cake.height - small.height) // 2))
topper.save(os.path.join(folder, "zz_cake_face_topper.jpg"), quality=92)
print("built zz_cake_with_person.jpg and zz_cake_face_topper.jpg")
