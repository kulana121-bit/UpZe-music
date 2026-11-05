#!/usr/bin/env python3
"""Generates original abstract artwork (album covers, editorial banners, artist avatars) for MusicSpot."""
import math, os, sys, random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
S = 512

def grad(w, h, c1, c2, angle=90):
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    for y in range(h):
        for x in range(w):
            t = ((x / w - .5) * dx + (y / h - .5) * dy) + .5
            t = min(1, max(0, t))
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
    return img

def glow(img, xy, r, color, blur=60, alpha=200):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = xy
    d.ellipse([x - r, y - r, x + r, y + r], fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    base = img.convert("RGBA")
    base.alpha_composite(layer)
    return base.convert("RGB")

def grain(img, amt=10, seed=1):
    random.seed(seed)
    import numpy as np
    n = np.asarray(Image.effect_noise(img.size, amt), dtype=np.float32) - 128.0
    a = np.asarray(img, dtype=np.float32) + n[..., None] * 0.35
    return Image.fromarray(np.clip(a, 0, 255).astype("uint8"))

def synthwave(c_top, c_bot, sun1, sun2, seed):
    img = grad(S, S, c_top, c_bot, 90)
    img = glow(img, (S // 2, int(S * .48)), 150, sun1, 70, 160)
    d = ImageDraw.Draw(img)
    # striped sun
    cx, cy, r = S // 2, int(S * .46), 120
    for y in range(cy - r, cy + 1):
        t = (y - (cy - r)) / r
        col = tuple(int(sun1[i] + (sun2[i] - sun1[i]) * t) for i in range(3))
        if t > .45 and int((y - cy) / 7) % 2 == 0:
            continue
        hw = int(math.sqrt(max(0, r * r - (y - cy) ** 2)))
        d.line([cx - hw, y, cx + hw, y], fill=col)
    # horizon grid
    hy = int(S * .62)
    d.rectangle([0, hy, S, S], fill=(10, 6, 20))
    for i in range(-12, 13):
        d.line([S // 2 + i * 18, hy, S // 2 + i * 120, S], fill=(255, 83, 88), width=2)
    y, k = hy, 6
    while y < S:
        d.line([0, y, S, y], fill=(255, 83, 88), width=2)
        y += k; k = int(k * 1.35) + 1
    return grain(img, 14, seed)

def bloom(c1, c2, seed):
    random.seed(seed)
    img = grad(S, S, c1, (8, 8, 8), 60)
    for _ in range(7):
        img = glow(img, (random.randint(60, 450), random.randint(60, 450)), random.randint(50, 130),
                   tuple(min(255, max(0, c2[i] + random.randint(-40, 40))) for i in range(3)), 50, 150)
    d = ImageDraw.Draw(img)
    for i in range(14):  # petals
        a = i / 14 * math.tau
        x, y = S / 2 + math.cos(a) * 90, S / 2 + math.sin(a) * 90
        d.ellipse([x - 40, y - 40, x + 40, y + 40], outline=(255, 210, 220), width=2)
    return grain(img.filter(ImageFilter.GaussianBlur(1)), 18, seed)

def waves(c1, c2, seed):
    img = grad(S, S, c1, c2, 90)
    img = glow(img, (380, 140), 90, (255, 220, 200), 60, 170)
    d = ImageDraw.Draw(img)
    for k in range(9):
        pts = []
        base = 250 + k * 30
        for x in range(0, S + 8, 8):
            pts.append((x, base + 14 * math.sin(x / 50 + k * .8) + 6 * math.sin(x / 17 + k)))
        col = (int(30 + k * 10), int(120 + k * 12), int(150 + k * 9))
        d.line(pts, fill=col, width=3)
    return grain(img, 10, seed)

def lights(c1, c2, seed):
    random.seed(seed)
    img = grad(S, S, c1, c2, 100)
    for _ in range(26):
        x, y, r = random.randint(0, S), random.randint(0, S), random.randint(8, 40)
        img = glow(img, (x, y), r, random.choice([(255, 196, 120), (255, 150, 90), (255, 230, 170)]), r // 2 + 4, 200)
    d = ImageDraw.Draw(img)
    for i in range(5):  # lanterns
        x = 80 + i * 90; y = 180 + (i % 2) * 60
        d.rounded_rectangle([x, y, x + 46, y + 66], 18, fill=(255, 120, 70), outline=(255, 210, 150), width=3)
        d.line([x + 23, 0, x + 23, y], fill=(90, 60, 40), width=2)
    return grain(img, 12, seed)

def orbit(c1, c2, seed):
    img = grad(S, S, c1, c2, 45)
    img = glow(img, (S // 2, S // 2), 110, (255, 83, 88), 40, 220)
    d = ImageDraw.Draw(img)
    for r, w in ((150, 2), (190, 1), (235, 2)):
        d.ellipse([S / 2 - r, S / 2 - r * .42, S / 2 + r, S / 2 + r * .42], outline=(255, 180, 190), width=w)
    d.ellipse([S / 2 - 70, S / 2 - 70, S / 2 + 70, S / 2 + 70], fill=(20, 8, 16))
    d.arc([S / 2 - 70, S / 2 - 70, S / 2 + 70, S / 2 + 70], 200, 340, fill=(255, 110, 120), width=4)
    random.seed(seed)
    for _ in range(120):
        x, y = random.randint(0, S), random.randint(0, S)
        d.point((x, y), fill=(255, 255, 255))
    return grain(img, 10, seed)

def avatar(c1, c2, seed):
    random.seed(seed)
    img = grad(S, S, c1, c2, random.randint(0, 180))
    img = glow(img, (S // 2, int(S * .42)), 110, (255, 230, 220), 30, 90)
    d = ImageDraw.Draw(img)
    d.ellipse([S / 2 - 85, 120, S / 2 + 85, 290], fill=(20, 14, 18))  # head silhouette
    d.rounded_rectangle([S / 2 - 170, 300, S / 2 + 170, 620], 150, fill=(20, 14, 18))
    return grain(img, 12, seed)

def save(img, name, size=S):
    img.resize((size, size), Image.LANCZOS).save(os.path.join(OUT, name + ".webp"), "WEBP", quality=78, method=6)

save(synthwave((40, 8, 60), (255, 83, 120), (255, 200, 90), (255, 60, 120), 1), "art_city_of_echoes")
save(bloom((70, 10, 50), (255, 120, 170), 2), "art_night_bloom")
save(waves((20, 40, 70), (10, 90, 110), 3), "art_coastal_hours")
save(lights((30, 14, 30), (90, 30, 20), 4), "art_small_lights")
save(orbit((10, 10, 30), (60, 10, 30), 5), "art_low_orbit")
# playlist / editorial covers
save(synthwave((10, 10, 40), (120, 20, 90), (120, 240, 255), (255, 83, 140), 6), "art_late_night_drive")
save(bloom((10, 40, 50), (108, 214, 219), 7), "art_deep_focus")
save(lights((15, 10, 20), (60, 20, 40), 8), "art_acoustic_nocturne")
save(orbit((40, 0, 10), (140, 20, 40), 9), "art_gym_synth")
save(waves((60, 20, 80), (200, 60, 120), 10), "art_chillwave")
for i, (a, b) in enumerate([((90, 20, 40), (255, 83, 88)), ((30, 60, 90), (108, 214, 219)), ((70, 40, 10), (255, 170, 90)),
                             ((50, 10, 70), (190, 90, 255)), ((10, 50, 40), (90, 200, 150))], 1):
    save(avatar(a, b, 20 + i), f"artist_{i}", 256)
print(sorted(os.listdir(OUT)))
