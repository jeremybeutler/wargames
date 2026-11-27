"""
Builds the web copies of the Middle-earth map:

  frontend/public/map-assets/middle-earth/map.webp     the 4096 x 2048 base map (loaded first)
  frontend/public/map-assets/middle-earth/hd/R-C.webp  8 x 4 tiles of a 2x upscale, loaded only
                                                       for the part of the map in view when zoomed

The upscale is made outside this script, with Real-ESRGAN (realesrgan-ncnn-vulkan,
model realesr-animevideov3, scale 2):

  realesrgan-ncnn-vulkan -i frontend/public/map-assets/middle-earth/map.png \
      -o map-8k.png -n realesr-animevideov3 -s 2
  python scripts/make_map_tiles.py map-8k.png

Other campaigns' maps use the same layout; name the campaign's map folder second:

  python scripts/make_map_tiles.py westeros-8k.png westeros

A tile pyramid (several zoom levels, each loaded only when the zoom needs it) is built with
--pyramid, naming the map folder and one image per level, smallest first. Each level is cut
into 1024-px tiles under tiles/<level>/, and tiles/tiles.json lists the levels for the game
screen (ThronesGameplay.vue):

  python scripts/make_map_tiles.py --pyramid westeros level1-8192.png level2-16384.png

Each tile carries BLEED extra pixels on every inner edge so neighbouring tiles overlap with
identical pixels; the frontend positions them with the same numbers (see HD_TILE_* in
Gameplay.vue). Requires Pillow with WebP support.
"""
import json
import sys
from pathlib import Path

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
ROOT = Path(__file__).resolve().parent.parent
COLS, ROWS, BLEED = 8, 4, 2
QUALITY = 82
PYRAMID_TILE = 1024


def cut(image, folder, cols, rows):
    """Cuts an image into cols x rows tiles, each with BLEED pixels of overlap; returns bytes written."""
    folder.mkdir(parents=True, exist_ok=True)
    tile_w, tile_h = image.width // cols, image.height // rows
    total = 0
    for row in range(rows):
        for col in range(cols):
            box = (max(0, col * tile_w - BLEED), max(0, row * tile_h - BLEED),
                   min(image.width, (col + 1) * tile_w + BLEED), min(image.height, (row + 1) * tile_h + BLEED))
            path = folder / f'{row}-{col}.webp'
            image.crop(box).save(path, quality=QUALITY, method=6)
            total += path.stat().st_size
    return total


def pyramid(folder_name, sources):
    out = ROOT / 'frontend/public/map-assets' / folder_name / 'tiles'
    levels = []
    for number, source in enumerate(sources, 1):
        image = Image.open(source).convert('RGB')
        if image.width % PYRAMID_TILE or image.height % PYRAMID_TILE or image.width != 2 * image.height:
            sys.exit(f'{source}: expected a 2:1 image in multiples of {PYRAMID_TILE} px, got {image.width} x {image.height}')
        cols, rows = image.width // PYRAMID_TILE, image.height // PYRAMID_TILE
        size = cut(image, out / str(number), cols, rows)
        levels.append({'level': number, 'width': image.width, 'height': image.height,
                       'cols': cols, 'rows': rows, 'bleed': BLEED})
        print(f'level {number}: {image.width} x {image.height}, {cols * rows} tiles, {size / 1e6:.1f} MB')
    (out / 'tiles.json').write_text(json.dumps({'levels': levels}), encoding='utf-8')


def main():
    if len(sys.argv) >= 4 and sys.argv[1] == '--pyramid':
        pyramid(sys.argv[2], sys.argv[3:])
        return
    if len(sys.argv) not in (2, 3):
        sys.exit('usage: python scripts/make_map_tiles.py <upscaled map, 8192 x 4096> [map folder, default middle-earth]')
    OUT = ROOT / 'frontend/public/map-assets' / (sys.argv[2] if len(sys.argv) == 3 else 'middle-earth')
    hd = Image.open(sys.argv[1]).convert('RGB')
    if hd.size != (8192, 4096):
        sys.exit(f'expected an 8192 x 4096 image, got {hd.size[0]} x {hd.size[1]}')

    base = Image.open(OUT / 'map.png').convert('RGB')
    base.save(OUT / 'map.webp', quality=88, method=6)
    print(f'map.webp {(OUT / "map.webp").stat().st_size / 1e6:.1f} MB')

    tiles = OUT / 'hd'
    tiles.mkdir(exist_ok=True)
    tile_w, tile_h = hd.width // COLS, hd.height // ROWS
    total = 0
    for row in range(ROWS):
        for col in range(COLS):
            box = (max(0, col * tile_w - BLEED), max(0, row * tile_h - BLEED),
                   min(hd.width, (col + 1) * tile_w + BLEED), min(hd.height, (row + 1) * tile_h + BLEED))
            path = tiles / f'{row}-{col}.webp'
            hd.crop(box).save(path, quality=QUALITY, method=6)
            total += path.stat().st_size
    print(f'{ROWS * COLS} tiles, {total / 1e6:.1f} MB')


if __name__ == '__main__':
    main()
