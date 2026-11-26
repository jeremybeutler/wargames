"""
Shrinks character portraits for the web.

Reads the full-size originals from assets/portraits/<name>.png (any size, square works best)
and writes frontend/public/portraits/<name>.webp at 256 x 256, which is sharp for the
largest place a portrait appears (96 px in the character panel) on high-DPI phones.

  python scripts/make_portraits.py

Requires Pillow with WebP support.
"""
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'assets/portraits'
OUT = ROOT / 'frontend/public/portraits'
SIZE = 256


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    originals = sorted(p for p in SOURCE.glob('*') if p.suffix.lower() in {'.png', '.jpg', '.jpeg', '.webp'})
    if not originals:
        raise SystemExit(f'No images found in {SOURCE}')
    total = 0
    for source in originals:
        image = ImageOps.fit(Image.open(source).convert('RGB'), (SIZE, SIZE), Image.LANCZOS)
        target = OUT / f'{source.stem.lower()}.webp'
        image.save(target, quality=86, method=6)
        total += target.stat().st_size
    print(f'{len(originals)} portraits, {total / 1024:.0f} KB in all')


if __name__ == '__main__':
    main()
