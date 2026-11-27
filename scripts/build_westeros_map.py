#!/usr/bin/env python3
"""
Builds the Westeros campaign's map files from the map art and maps/westeros/design.json.

  maps/westeros/source-art.webp                      the painted map (1774 x 887 or larger, 2:1)
  -> frontend/public/map-assets/westeros/map.png     4096 x 2048 base map (same size as Middle-earth's)
  -> frontend/public/map-assets/westeros/map.webp    the copy the game shows
  -> backend/src/main/resources/westeros-map.json    places, ports and the Wall, for the server

The art stays exactly as painted; only higher-resolution copies are made. With --hd, the base
map is downscaled from an AI upscale of the art (sharp); without it, the art is enlarged with
Lanczos (soft). The zoom tiles come from the same upscale. With Real-ESRGAN
(realesrgan-ncnn-vulkan, from github.com/xinntao/Real-ESRGAN releases) in a tools folder:

  realesrgan-ncnn-vulkan -i source.png -o x4.png -n realesr-animevideov3 -s 4   (source.png: the art)
  python -c "from PIL import Image; Image.open('x4.png').resize((8192, 4096), Image.LANCZOS).save('level1.png')"
  realesrgan-ncnn-vulkan -i level1.png -o level2.png -n realesr-animevideov3 -s 2
  python scripts/build_westeros_map.py --hd level1.png
  python scripts/make_map_tiles.py --pyramid westeros level1.png level2.png

Then regenerate terrain from the art:  python scripts/generate_terrain.py --profile westeros

Needs: pip install pillow
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageFilter

Image.MAX_IMAGE_PIXELS = None

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / 'maps/westeros/source-art.webp'
DESIGN = ROOT / 'maps/westeros/design.json'
OUT = ROOT / 'frontend/public/map-assets/westeros'
MAP_DATA = ROOT / 'backend/src/main/resources/westeros-map.json'
SIZE = (4096, 2048)


def main():
    parser = argparse.ArgumentParser(description='Builds the Westeros map images and place data.')
    parser.add_argument('--hd', type=Path, help='an AI upscale of the art (2:1, at least 4096 wide) to downscale the base map from')
    args = parser.parse_args()
    art = Image.open(args.hd or ART).convert('RGB')
    if abs(art.width / art.height - 2) > 0.01:
        raise SystemExit(f'the art must be 2:1 like the Middle-earth map, got {art.width} x {art.height}')
    OUT.mkdir(parents=True, exist_ok=True)
    base = art if art.size == SIZE else art.resize(SIZE, Image.LANCZOS)
    if art.width < SIZE[0]:
        base = base.filter(ImageFilter.UnsharpMask(radius=2.2, percent=55, threshold=2))
    base.save(OUT / 'map.png', optimize=True)
    base.save(OUT / 'map.webp', quality=88, method=6)
    print(f'map.png {SIZE[0]} x {SIZE[1]}, map.webp {(OUT / "map.webp").stat().st_size / 1e6:.1f} MB')

    design = json.loads(DESIGN.read_text(encoding='utf-8'))
    places = [{'name': p['name'], 'kind': p['kind'], 'x': p['x'], 'y': p['y'], 'region': p['region'],
               'port': bool(p.get('port'))} for p in design['places']]
    MAP_DATA.write_text(json.dumps({'mapKey': 'westeros', 'width': 1774, 'height': 887,
                                    'places': places, 'wall': design['wall']}, indent=1), encoding='utf-8')
    print(f'{len(places)} places written to {MAP_DATA.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
