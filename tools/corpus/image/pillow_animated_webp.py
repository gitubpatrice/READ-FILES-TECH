"""An animated WebP for the corpus, by Pillow (its libwebp WebPAnimEncoder): no Windows tool writes one."""
import sys
from PIL import Image, ImageDraw

frames = []
for i in range(8):
    frame = Image.new("RGB", (120, 80), "white")
    draw = ImageDraw.Draw(frame)
    x = 12 + i * 13
    draw.ellipse((x - 10, 30, x + 10, 50), fill="orangered")
    frames.append(frame)
frames[0].save(sys.argv[1], save_all=True, append_images=frames[1:], duration=120, loop=0, lossless=False, quality=80)
print(sys.argv[1])
