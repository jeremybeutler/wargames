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

Each tile carries BLEED extra pixels on every inner edge so neighbouring tiles overlap with
identical pixels; the frontend positions them with the same numbers (see HD_TILE_* in
Gameplay.vue). Requires Pillow with WebP support.
"""
import sys
from pathlib import Path

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
ROOT = Path(__file__).resolve().parent.parent
COLS, ROWS, BLEED = 8, 4, 2
QUALITY = 82


def main():
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
