# Contributing

Bug reports and focused pull requests are welcome. Describe the Portal/Android version, expected behavior, reproduction steps, and the result. Use synthetic images when reporting photo problems, and remove private network details or upload codes from logs/screenshots.

## Working on the code

1. Read the [development guide](docs/DEVELOPMENT.md).
2. Keep imports bounded, USB reads read-only, and photo copies scoped to their albums.
3. Keep UI text focused on the user; photo filenames and dimensions do not belong in the gallery.
4. Run `python3 scripts/test.py` and the relevant device suites for behavior changes.
5. Run `python3 scripts/check_public.py` after staging your changes. Run `npm run format:check` (or `npm run format` to apply formatting). Java follows Google Java Format 1.22.0; Python uses four-space indentation.
6. Include what changed and how it was verified. Distinguish physical-device results from fixture-based checks.

Changes to image storage or migration must preserve existing photos, settings, and album covers. Incomplete settings edits must keep the last valid values and allow unrelated settings to save.

Generated files belong under `build/`. Keep SDKs, signing keys, original photos, device backups, account credentials, and personal machine paths out of commits. Do not reformat the vendored QR generator; retain its original notice and checksum.

Contributions are distributed under the repository's [MIT license](LICENSE).
