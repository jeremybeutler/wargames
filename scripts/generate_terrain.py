"""Generate Middle-earth terrain strokes from the map art.

Reads frontend/public/map-assets/middle-earth/map.png, detects forest, mountains
(including snowy peaks), Mordor's volcanic ash plain and rivers by color and
texture, and writes them as map-editor strokes to terrain/middle-earth.json.
Load that file into a running app with scripts/import-terrain.ps1.

Needs: pip install pillow numpy scipy scikit-image

    python scripts/generate_terrain.py [--preview terrain/preview.png]
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
from skimage.morphology import skeletonize

ROOT = Path(__file__).resolve().parent.parent
MAP = ROOT / 'frontend/public/map-assets/middle-earth/map.png'
OUT = ROOT / 'terrain/middle-earth.json'

# The game measures everything on a 1774 x 887 map (MovementPathCalculator).
W, H = 1774, 887
AREA_BRUSH = 1.6   # stroke diameter as % of map height (editor "brush size")
RIVER_BRUSH = 2.0
EDGE_MARGIN = 12   # px; the paper border is never terrain


def hsv_of(img):
    hsv = np.asarray(img.convert('HSV')).astype(np.float32)
    return hsv[..., 0] * 360 / 255, hsv[..., 1] / 255, hsv[..., 2] / 255


def density(mask, size):
    return ndi.uniform_filter(mask.astype(np.float32), size)


def sea_and_lakes(h, s, iterations, grow):
    """Wide water touching the border is sea; other wide water is lakes."""
    water = (h >= 155) & (h <= 225) & (s > 0.14)
    blueish = (h >= 150) & (h <= 230)
    wide = ndi.binary_opening(water | (blueish & (s < 0.2)), iterations=iterations)
    lab, _ = ndi.label(wide)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    sea_core = np.isin(lab, list(border))
    sea = ndi.binary_dilation(sea_core, iterations=grow)
    lakes = ndi.binary_dilation(wide & ~sea_core, iterations=max(3, grow // 2))
    return water, sea, lakes


def classify_areas(img):
    small = img.resize((W, H), Image.LANCZOS)
    rgb = np.asarray(small).astype(np.float32) / 255
    h, s, v = hsv_of(small)
    lum = rgb @ np.array([0.299, 0.587, 0.114])
    tex = np.sqrt(np.maximum(ndi.uniform_filter(lum ** 2, 7) - ndi.uniform_filter(lum, 7) ** 2, 0))
    water, sea, _ = sea_and_lakes(h, s, iterations=4, grow=7)
    land = ~sea
    land[:EDGE_MARGIN] = land[-EDGE_MARGIN:] = False
    land[:, :EDGE_MARGIN] = land[:, -EDGE_MARGIN:] = False

    forest_px = (h >= 65) & (h <= 160) & (v < 0.47) & (s > 0.35) & land
    snow_px = (s < 0.22) & (v > 0.62) & land & ~water
    rock_px = ((((h < 50) | (h > 330)) & (v < 0.62) & (s > 0.18)) | ((s < 0.25) & (v < 0.62)))
    rock_px &= land & ~water & (tex > 0.05)
    lava_px = (h < 25) & (s > 0.6) & (v > 0.6) & land

    forest = density(forest_px, 11) > 0.38
    mountain = density(rock_px | snow_px, 15) > 0.30

    # Mordor's ash plain: the large smooth grey area inside the mountain ring.
    yy, xx = np.mgrid[0:H, 0:W]
    mordor = (xx > W * 0.58) & (yy > H * 0.5) & (yy < H * 0.86)
    smooth_tex = ndi.uniform_filter(tex, 15)
    ash = (smooth_tex < 0.075) & (s < 0.48) & (v < 0.64) & ((h < 45) | (s < 0.15)) & mordor & land
    ash = ndi.binary_opening(ash, iterations=3)
    labels, count = ndi.label(ash)
    sizes = ndi.sum(ash, labels, range(1, count + 1))
    volcanic = labels == (np.argmax(sizes) + 1)
    volcanic = ndi.binary_fill_holes(ndi.binary_closing(volcanic, iterations=8))
    volcanic |= ndi.binary_dilation(lava_px & mordor, iterations=3) & ndi.binary_dilation(volcanic, iterations=10)
    ring = ndi.binary_closing(mountain & mordor, iterations=6)
    inside = ndi.binary_fill_holes(ring) & ~ring
    labels, count = ndi.label(inside)
    if count:
        sizes = ndi.sum(inside, labels, range(1, count + 1))
        volcanic |= np.isin(labels, np.nonzero(sizes > 2000)[0] + 1)
    grey = (s < 0.3) & (v > 0.25) & (v < 0.58) & land
    volcanic |= (density(grey, 17) > 0.55) & ndi.binary_dilation(volcanic, iterations=70) & mordor
    volcanic = ndi.binary_opening(volcanic, iterations=2)
    volcanic = ndi.binary_fill_holes(ndi.binary_closing(volcanic, iterations=5)) & land

    mountain &= ~volcanic & land
    forest &= ~mountain & land   # the slower terrain wins anyway; skip duplicate strokes
    return {'volcanic': volcanic, 'mountain': mountain, 'forest': forest}


def river_skeleton(img):
    h, s, v = hsv_of(img)
    water, sea, lakes = sea_and_lakes(h, s, iterations=9, grow=16)
    river = water & (v > 0.22) & ~sea & ~lakes
    river = ndi.binary_closing(river, iterations=2)
    labels, count = ndi.label(river, structure=np.ones((3, 3)))
    sizes = ndi.sum(river, labels, range(1, count + 1))
    river = np.isin(labels, np.nonzero(sizes >= 150)[0] + 1)
    return skeletonize(river)


def trace(skel):
    """Split a 1-px skeleton into polylines between endpoints and junctions."""
    ys, xs = np.nonzero(skel)
    pixels = set(zip(ys.tolist(), xs.tolist()))
    offsets = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

    def neighbours(p):
        return [(p[0] + dy, p[1] + dx) for dy, dx in offsets if (p[0] + dy, p[1] + dx) in pixels]

    nodes = {p for p in pixels if len(neighbours(p)) != 2}
    used = set()

    def edge(a, b):
        return (a, b) if a < b else (b, a)

    def walk(start, nxt):
        path, prev, cur = [start, nxt], start, nxt
        while cur not in nodes:
            options = [q for q in neighbours(cur) if q != prev and edge(cur, q) not in used]
            if not options:
                break
            used.add(edge(cur, options[0]))
            prev, cur = cur, options[0]
            path.append(cur)
        return path

    paths = []
    for node in nodes:
        for q in neighbours(node):
            if edge(node, q) not in used:
                used.add(edge(node, q))
                paths.append(walk(node, q))
    for p in pixels:  # closed loops have no nodes
        for q in neighbours(p):
            if edge(p, q) not in used:
                used.add(edge(p, q))
                paths.append(walk(p, q))
    return paths


def simplify(points, eps):
    if len(points) < 3:
        return points
    pts = np.array(points)
    a, b = pts[0], pts[-1]
    ab = b - a
    norm = np.hypot(*ab)
    dist = (np.hypot(*(pts - a).T) if norm == 0
            else np.abs(ab[0] * (pts[:, 1] - a[1]) - ab[1] * (pts[:, 0] - a[0])) / norm)
    i = int(np.argmax(dist))
    if dist[i] > eps:
        return simplify(points[:i + 1], eps)[:-1] + simplify(points[i:], eps)
    return [points[0], points[-1]]


def radius(brush):
    return H * brush / 200.0   # same formula as MovementPathCalculator


def area_strokes(mask, brush):
    """Cover a mask with horizontal capsule strokes, one row of strokes per brush width."""
    rad = radius(brush)
    step = rad * 1.7
    strokes, y = [], rad * 0.85
    while y < H:
        band = mask[int(max(0, y - step / 2)):int(min(H, y + step / 2 + 1))]
        row = ndi.binary_closing(band.mean(axis=0) >= 0.5, iterations=2)
        labels, _ = ndi.label(row)
        for found in ndi.find_objects(labels):
            x0, x1 = found[0].start, found[0].stop - 1
            if x1 - x0 + 1 < rad * 0.9:
                continue
            a, b = x0 + rad * 0.7, x1 - rad * 0.7
            if b <= a:
                a = b = (x0 + x1) / 2
            strokes.append([(a, y), (b, y)])
        y += step
    return strokes


def river_strokes(img):
    sx, sy = W / img.size[0], H / img.size[1]
    strokes = []
    for path in trace(river_skeleton(img)):
        pts = [(x * sx, y * sy) for y, x in path]
        length = sum(np.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))
        if length >= 4:   # skip skeleton spurs
            strokes.append(simplify(pts, 1.2))
    return strokes


def rasterize(strokes, brush):
    im = Image.new('L', (W, H), 0)
    draw = ImageDraw.Draw(im)
    rad = radius(brush)
    for stroke in strokes:
        for a, b in zip(stroke, stroke[1:]):
            draw.line([a, b], fill=255, width=int(round(rad * 2)))
        for x, y in stroke:
            draw.ellipse([x - rad, y - rad, x + rad, y + rad], fill=255)
    return np.asarray(im) > 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--map', type=Path, default=MAP)
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--preview', type=Path, help='also save an overlay image of the strokes')
    args = parser.parse_args()

    img = Image.open(args.map).convert('RGB')
    areas = classify_areas(img)
    layers = [(name, AREA_BRUSH, area_strokes(areas[name], AREA_BRUSH))
              for name in ('volcanic', 'mountain', 'forest')]
    layers.append(('river', RIVER_BRUSH, river_strokes(img)))

    regions = []
    for terrain, brush, strokes in layers:
        for index, stroke in enumerate(strokes, 1):
            regions.append({
                'name': f'{terrain.capitalize()} {index}',
                'terrainType': terrain,
                'brushSize': brush,
                'points': [{'x': round(x / W * 100, 4), 'y': round(y / H * 100, 4)} for x, y in stroke],
            })
    args.out.parent.mkdir(parents=True, exist_ok=True)
    data = {'mapKey': 'middle-earth', 'mapLayer': 'middle-earth',
            'source': 'Generated by scripts/generate_terrain.py from the Middle-earth map art.',
            'regions': regions}
    args.out.write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8')

    colors = {'forest': (0, 255, 0), 'mountain': (255, 0, 255), 'volcanic': (255, 60, 0), 'river': (0, 80, 255)}
    preview = np.asarray(img.resize((W, H))).copy()
    for terrain, brush, strokes in layers:
        covered = rasterize(strokes, brush)
        if terrain in areas:
            mask = areas[terrain]
            print(f'{terrain:9s} {len(strokes):4d} strokes, covers {(covered & mask).sum() / mask.sum():.0%} '
                  f'of detected area, {(covered & ~mask).sum() / covered.sum():.0%} spill')
        else:
            print(f'{terrain:9s} {len(strokes):4d} strokes')
        preview[covered] = (preview[covered] * 0.45 + np.array(colors[terrain]) * 0.55).astype(np.uint8)
    if args.preview:
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(preview).save(args.preview)
    print(f'{len(regions)} strokes written to {args.out}')


if __name__ == '__main__':
    main()
