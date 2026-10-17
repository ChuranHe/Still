# README demo media

These browser captures use Still's production HTML, CSS, and JavaScript with a fixture Android bridge. The clock, weather, storage, and albums are example data. They demonstrate the interface; hardware sensors and Android imports are covered by the device tests.

## Generated sample photos

The three photos were created for this repository with the built-in `image_gen` tool in October 2026. The people are fictional. Full prompts are in [prompts.json](prompts.json).

| Photo                                  | Subject                                      |
| -------------------------------------- | -------------------------------------------- |
| [coast.jpg](samples/coast.jpg)         | Pacific-style coastal scenery at golden hour |
| [mountains.jpg](samples/mountains.jpg) | An alpine lake and mountain reflections      |
| [people.jpg](samples/people.jpg)       | Two fictional adult friends at a park picnic |

Generated PNG outputs were encoded as JPEG at quality 86 with macOS `sips`; no stock or personal photos were used. These demo assets are provided with this project under its [MIT license](../../LICENSE).

## Recreate captures

Install the development dependencies and a browser as described in [DEVELOPMENT.md](../DEVELOPMENT.md), then run:

```sh
node scripts/capture_readme.mjs
python3 -m pip install -r requirements-dev.txt
python3 scripts/encode_readme_gif.py
```

The capture script serves local files through Playwright routing, blocks external requests, supplies sample state, and drives the existing UI controls. It does not connect to a Portal or read a personal library. It writes three full-resolution screenshots here and animation frames into ignored `build/readme-frames/`. The encoder assembles a 960 × 600 GIF with a shared palette and deliberate pauses for reading.

The animation shows landscape playback and a fade, album collages, photo previews, the cover editor, and Settings. The separate portrait screenshot shows the portrait-only photo set.

[manifest.json](manifest.json) records the reviewed files and SHA-256 hashes. After intentionally replacing demo media, inspect every output and update the manifest. The public-source check permits only the explicitly listed demo filenames and requires their indexed hashes to match; other photos and device captures remain blocked.
