#!/usr/bin/env python3
"""Assemble captured production-UI frames into a compact, looping README GIF."""
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'build/readme-frames'
OUTPUT = ROOT / 'docs/media/still-demo.gif'


def main():
    timeline = json.loads((SOURCE / 'timeline.json').read_text())
    frames = []
    for item in timeline:
        with Image.open(SOURCE / item['name']) as image:
            frames.append(image.convert('RGB').resize((960, 600), Image.Resampling.LANCZOS))
    # One palette across the animation keeps text and scenery colors consistent.
    swatches = Image.new('RGB', (160 * len(frames), 100))
    for index, frame in enumerate(frames):
        swatches.paste(frame.resize((160, 100)), (160 * index, 0))
    palette = swatches.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
    indexed = [frame.quantize(palette=palette, dither=Image.Dither.FLOYDSTEINBERG) for frame in frames]
    indexed[0].save(OUTPUT, save_all=True, append_images=indexed[1:],
                    duration=[item['duration'] for item in timeline], loop=0,
                    optimize=True, disposal=1)
    with Image.open(OUTPUT) as result:
        assert result.is_animated and result.size == (960, 600)
        duration = sum(result.seek(i) or result.info['duration'] for i in range(result.n_frames))
        assert duration == sum(item['duration'] for item in timeline)
        print(f'{OUTPUT.relative_to(ROOT)}: {result.n_frames} frames, {duration / 1000:.1f}s, {OUTPUT.stat().st_size:,} bytes')


if __name__ == '__main__':
    main()
