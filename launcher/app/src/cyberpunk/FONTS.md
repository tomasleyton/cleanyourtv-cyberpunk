# Fonts (cyberpunk flavor)

| File | Family | Weight | Used for |
|------|--------|--------|----------|
| `res/font/rajdhani_bold.ttf` | Rajdhani | 700 (Bold) | clock, titles |
| `res/font/jetbrains_mono.ttf` | JetBrains Mono | 400 (Regular) | everything else |

`res/font/outfit.xml` is an alias family that maps `@font/outfit` (used by the
upstream layouts) to `@font/jetbrains_mono`, so upstream layouts render in mono
without being copied into this source set.

## Origin

Both fonts come from the Google Fonts latin subsets (woff2) embedded in the
design mock `TV Home Cyberpunk.html`. They were decoded and converted to TTF
with fontTools (Android only accepts TTF/OTF in `res/font`), then slimmed:

- JetBrains Mono shipped as a variable font (wght 400-800); it was instanced
  to `wght=400` with `fontTools.varLib.instancer`.
- Hinting removed (`--no-hinting`), CFF/TT subroutines flattened; layout
  features reduced to `kern`, `mark`, `mkmk` (coding ligatures dropped).
- Glyph coverage is the Google Fonts latin subset: Basic Latin, Latin-1
  Supplement (incl. ñ á é í ó ú ü ¿ ¡ · °), general punctuation and a few
  symbols. No further glyph removal.

Sizes: `rajdhani_bold.ttf` ~24 KB, `jetbrains_mono.ttf` ~33 KB (APK budget: 400 KB).

## License

SIL Open Font License 1.1 for both fonts. Copyright notices (from the fonts' name tables):

- Rajdhani: Copyright (c) 2014 Indian Type Foundry (info@indiantypefoundry.com)
- JetBrains Mono: Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)

The OFL 1.1 text is in [`launcher/FONT-LICENSE-OFL.txt`](../../../FONT-LICENSE-OFL.txt).
That file currently carries the Outfit copyright line (upstream's font); the
license terms are identical, but the two notices above should be added there
when the cyberpunk flavor is released.
