"""Two animations at the pixel cap (8 000 x 8 000), by Pillow: a GIF and a WebP (libwebp's WebPAnimEncoder).

Android decodes an animation's frames at full size whatever the subsampling asked: played, each held
256 MB of native memory on a Galaxy S9. Over FileCaps.ANIMATION_PIXELS the viewer shows the first frame.

Usage: python -I pillow_giant_animated.py <out folder>
"""
import pathlib
import sys

from PIL import Image, ImageDraw

SIDE = 8000
out = pathlib.Path(sys.argv[1])
frames = []
# One background, a square that moves: each frame after the first is a small rectangle, so the files stay small.
for i in range(3):
    frame = Image.new("RGB", (SIDE, SIDE), (40, 160, 128))
    ImageDraw.Draw(frame).rectangle((i * 2000, i * 2000, i * 2000 + 2000, i * 2000 + 2000), fill="white")
    frames.append(frame)
frames[0].save(out / "pillow-giant-animated.gif", save_all=True, append_images=frames[1:], duration=300, loop=0)
frames[0].save(out / "pillow-giant-animated.webp", save_all=True, append_images=frames[1:], duration=300, loop=0,
               lossless=True, method=4)
for name in ("pillow-giant-animated.gif", "pillow-giant-animated.webp"):
    print(name, (out / name).stat().st_size)
