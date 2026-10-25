# fastlane/metadata — how this folder is kept

The store listing of Read Files Tech, in the *Fastlane Supply* layout: per locale, `title.txt`,
`short_description.txt`, `full_description.txt`, and one changelog per version in
`changelogs/<versionCode>.txt`. One locale per language the app ships (`en-US`, `fr-FR`, `de-DE`,
`it-IT`, `es-ES`).

## Why it exists while F-Droid is not a goal

Read Files Tech is distributed through GitHub Releases. These files are still the source of each
release's notes, they make the changelog get written **before** the tag rather than after, and they
keep the app in line with the rest of the Files Tech apps.

## Rules, checked by `tools/check-translations.py`

- **Caps in characters, not bytes** — title 50, short description 80, full description 4000,
  changelog 500: the limits of fdroidserver, which cuts a longer text without a warning. An "é" counts
  as one. Do not measure with `wc -m` under Git Bash: with `LANG` unset it counts bytes.
- **Every shipped language has its locale**, and no locale is kept for a language the app does not ship.
- **Every changelog exists in every locale.** A changelog in one language only is a defect, not a
  translation pending.

## Which versionCode names the changelog

`version.properties` holds a BASE; each APK gets `base × 10 + ABI rank` (universal 0, armeabi-v7a 1,
arm64-v8a 2, x86_64 3 — docs D-011). The changelog is named after the **universal** APK's code,
`base × 10`: base 3000 writes `30000.txt`. A changelog prepared is not a version published: if the
version changes before the tag, rename the file in every locale.

Before the 3.0 (the Flutter app), the changelogs were named after the Flutter version code (`21503.txt`
for 2.15.3); they stay in the history of the `main` branch.
