# LJ736/K75 face encoding (algorithm 3, version 1)

The offline encoder targets the owner's exported 240 × 286 capabilities, config
0, screen type/grade 0, empty customer. This is an independently implemented
format, not a claim of compatibility with other FitPro chipsets or displays.

## Evidence, 22 September 2026

The supplied HiWatchPro APK's `WatchThemeHelper.handleNetSrcBin` creates a 24-bit
BMP, then `HttpHelper.bmp16Convert8BitByNetwork` posts it to
`https://tomato.gulaike.com/api/v1/convert/8bit`, with `file` and `font` form fields.
`font.bin` prepends the standard custom-face font; `empty.bin` does not. Network
research used synthetic solid-colour images, not user photos. The shared app
client authorization was used only in local research and is not part of the app,
encoder, tests or this document.

Observed server outputs at 240 × 286:

- Black with `empty.bin`: 68,652 bytes, starts `42 4d f0 00 1e 01 01 00 0c 00 00 00`.
- Red with `empty.bin`: same size, palette entry `00 f8` (RGB565 red).
- Black with `font.bin`: 71,909 bytes, the same background with a 3,257-byte font
  prefix. The prefix matches the catalogue's standalone font sample.

For catalogue photo face 3250, all 255 RGB565 palette entries match the source
BMP's RGB palette reduced to RGB565, and every index byte matches the decoded
BMP in top-down order. The final downloaded payload contains its font prefix
followed by the background record.

Compatible catalogue samples were retrieved through the APK's v2 list endpoint
for mainModel=LJ736, mchModel=K75, width=240, height=286, version=1, arithmetic=3.
An independent parser accounts for every header and image byte, without gaps or
unexplained trailers, in these animated catalogue samples:

| Face ID | File size | Header length | Animated image records |
|---|---:|---:|---|
| 3514 | 140,867 | 249 | 240 × 197, 2 frames, interval 5 |
| 6426 | 148,909 | 539 | 115 × 97, 3 frames, interval 8 |
| 6363 | 132,613 | 563 | 73 × 88 and 61 × 70, 3 frames each, interval 5 |

Their corresponding preview GIF timing is not a reliable measure of firmware
playback timing. The role of the interval byte is inferred from animated versus
static records. Its interpretation as 10 ms ticks remains unverified. Generated
faces must be tested on hardware before describing playback as confirmed.

Research downloads remain outside the source tree. No vendor artwork, fonts or
credentials are included in the app. Clock glyphs are original seven-segment
rectangles generated in code.

## Custom bitmap record (server output)

All integers below are little-endian:

| Field | Bytes |
|---|---:|
| ASCII `BM` | 2 |
| Width, height | 2 each |
| Palette count N | 2 |
| Offset of first pixel (10 + 2N) | 2 |
| RGB565 palette | 2N |
| Top-down, row-major palette indices, no row padding | width × height |

This is **not** a standard Windows BMP header. The app uses the full catalogue
container described below instead, to generate static or animated faces with
live digits without distributing the vendor font prefix.

## Full face container

Header: `AA 55 01 00 00`, then one byte giving the number of component groups.
Each group begins with a type byte. For types 0 and 1, a record-count byte follows.
Other observed types have exactly one record.

Types used by the encoder:

- 0: static background, usually one image record.
- 1: overlay/animation records; supports multiple frames.
- 5: hour digits, 10 images.
- 6: minute digits, 10 images.

Each record:

| Field | Bytes |
|---|---:|
| Bits per pixel (8 or 16) | 1 |
| Animation interval, only for type 0/1; static records use 0 | 1 |
| X, Y, width, height | 2 each |
| Number of images | 1 |
| Absolute file offsets for the images | 4 per image |

Image blobs immediately follow the header. Shared offsets exist in vendor files;
the first implementation emits independent blobs to keep generation simple.

- 8-bit image: N (2 bytes), RGB565 palette (2N bytes), then width × height indices.
  There is no `BM` prefix here. Samples use up to 255 colours. Our encoder uses
  a fixed 216-colour RGB cube, preserving black, white and the primary colours.
- 16-bit image: width × height raw RGB565 little-endian pixels.

Full catalogue containers are uploaded with custom=0. The watch supplies the
live hour/minute value; the converter supplies the digit images and positions.
The selected background occupies the full display. If enabled, an opaque black
strip reserves space for white clock digits and a colon.

## Limits and testing

The exporter emits the existing two-entry `fitpro-watchface-1` ZIP, including
SHA-256 and matching device metadata. It validates the generated ZIP with the
same parser and compatibility checks used by the installer before offering Save.
It never uploads automatically and makes no network requests.

Unit tests independently parse generated offsets, group counts, all frame bounds,
all palette indices and every payload byte; check live digit groups; distinguish
successive animation frames; verify RGB565 and scan order; exercise the 80-frame
limit and ZIP round-trip; and reject truncated, oversized or overlong GIFs.
Android's native GIF decoder handles compositing/disposal. Native decoding and
watch rendering still need device testing; pure JVM tests do not cover them.

## Unpacking existing faces

`tools/fitpro-format-research/unpack_watchface.py` extracts an existing `.bin` without
contacting the vendor API. It supports both full `AA 55` format-1 containers and
the `BM` custom-image records, including a prefixed `font.bin` block.

```sh
python3 tools/fitpro-format-research/unpack_watchface.py face.bin unpacked-face
```

The output contains `watchface.json`, a raw blob and palette JSON for each
unique image offset, and PNG renderings when Pillow is installed. Multi-frame
type-0/type-1 records are also reconstructed as GIFs. The GIF delay is the
face record's interval byte interpreted as centiseconds, which remains an
inference rather than hardware-confirmed timing. Use `--no-png` to extract
without Pillow. Offsets can be intentionally shared by several records;
`watchface.json` notes those references rather than duplicating them.

The same script can produce the Android app's upload-ready ZIP entirely offline:

```sh
python3 tools/fitpro-format-research/unpack_watchface.py pack photo.jpg my-face.fitpro.zip \
  --palette 255 --name "My face"
```

`--palette 216` is the compatibility cube, `--palette 255` chooses the most-used
RGB565 colours per source frame, and `--palette direct` writes raw RGB565 pixels
without an image palette. `--no-clock` omits the generated live hour/minute
clock. PNG/JPEG inputs use EXIF orientation and a centre crop; GIFs use the same
30-second, 80-frame, up-to-10-fps limits as the Android converter. Packing needs
Pillow and creates a ZIP; it does not upload to the watch.
