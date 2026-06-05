#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageOps

SRC = Path('/Users/xixiaoyong/Downloads/luck.webp')
ROOT = Path('/Users/xixiaoyong/code/XXPlayer')
RES = ROOT / 'app' / 'src' / 'main' / 'res'

SIZES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}


def main():
    if not SRC.exists():
        raise SystemExit(f'source image not found: {SRC}')

    img = Image.open(SRC).convert('RGBA')
    # Center-crop to square then fit to target sizes; keeps subject centered and avoids distortion.
    square = ImageOps.fit(img, (1024, 1024), method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))

    for bucket, size in SIZES.items():
        out_dir = RES / bucket
        out_dir.mkdir(parents=True, exist_ok=True)
        icon = square.resize((size, size), Image.Resampling.LANCZOS)
        icon.save(out_dir / 'ic_launcher.png', format='PNG', optimize=True)
        icon.save(out_dir / 'ic_launcher_round.png', format='PNG', optimize=True)

    # Play Store high-res icon convenience output
    (ROOT / 'artifacts').mkdir(exist_ok=True)
    square.save(ROOT / 'artifacts' / 'ic_launcher_512.png', format='PNG', optimize=True)
    print('launcher icons generated successfully')


if __name__ == '__main__':
    main()
