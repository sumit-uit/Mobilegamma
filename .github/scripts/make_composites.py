"""Builds two controlled test photos from the downloaded ones:

  zz_cake_with_person.jpg  a cake with real people beside it -> must be skipped (faces > 0)
  zz_cake_face_topper.jpg  a cake with a face printed on top  -> must NOT be skipped (faces == 0)
"""
import glob
import os
import sys

import cv2
from PIL import Image

folder = sys.argv[1] if len(sys.argv) > 1 else "test-images"
cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")


def best_cake():
    cakes = sorted(glob.glob(os.path.join(folder, "cake_*.jpg")))
    if not cakes:
        sys.exit("no cake images")
    # the largest file tends to be the most detailed, clearest cake shot
    return Image.open(max(cakes, key=os.path.getsize)).convert("RGB")


def best_face():
    """(people image, largest face box) from the people photos, found with OpenCV."""
    best = None
    for path in sorted(glob.glob(os.path.join(folder, "people_*.jpg"))):
        gray = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2GRAY)
        for (x, y, w, h) in cascade.detectMultiScale(gray, 1.1, 5, minSize=(40, 40)):
            if best is None or w * h > best[1][2] * best[1][3]:
                best = (path, (x, y, w, h))
    if best is None:
        sys.exit("no face found in people images")
    return Image.open(best[0]).convert("RGB"), best[1]


cake = best_cake()
people, (fx, fy, fw, fh) = best_face()

# 1) cake on the left, a person (face + shoulders) on the right, same height
pad = int(fw * 0.8)
person = people.crop((max(0, fx - pad), max(0, fy - pad), min(people.width, fx + fw + pad),
                      min(people.height, fy + fh + pad * 3)))
person = person.resize((int(person.width * cake.height / person.height), cake.height))
combo = Image.new("RGB", (cake.width + person.width, cake.height), "white")
combo.paste(cake, (0, 0))
combo.paste(person, (cake.width, 0))
combo.save(os.path.join(folder, "zz_cake_with_person.jpg"), quality=92)

# 2) the same face as a small printed photo in the middle of the cake
face = people.crop((fx - fw // 4, fy - fh // 4, fx + fw + fw // 4, fy + fh + fh // 4))
size = int(min(cake.width, cake.height) * 0.28)
face = face.resize((size, size))
topper = cake.copy()
topper.paste(face, ((cake.width - size) // 2, (cake.height - size) // 2))
topper.save(os.path.join(folder, "zz_cake_face_topper.jpg"), quality=92)
print("built zz_cake_with_person.jpg and zz_cake_face_topper.jpg")
