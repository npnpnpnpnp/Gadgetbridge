#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "Pillow",
# ]
# ///
"""Unpack or locally pack an LJ736/K75 FitPro watch-face file.

The script understands the catalogued ``AA 55 01 00 00`` face container and
the standalone ``BM`` palette record returned by HiWatchPro's custom-image
conversion service. It never contacts a network service or changes the input.

PNG/GIF conversion needs Pillow (``python3 -m pip install Pillow``). Raw image
blobs, palettes and JSON metadata are always exported using the standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import struct
import sys
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


WIDTH, HEIGHT = 240, 286
MAX_INPUT_BYTES = 12 * 1024 * 1024
MAX_GIF_DURATION_MS = 30_000
MAX_GIF_FRAMES = 80


class FormatError(ValueError):
    """The file is not a complete, supported FitPro face payload."""


@dataclass
class Record:
    group: int
    kind: int
    bits_per_pixel: int
    delay: int
    x: int
    y: int
    width: int
    height: int
    offsets: list[int]


def u16(data: bytes, offset: int) -> int:
    if offset + 2 > len(data):
        raise FormatError("unexpected end of file")
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    if offset + 4 > len(data):
        raise FormatError("unexpected end of file")
    return struct.unpack_from("<I", data, offset)[0]


def parse_container(data: bytes) -> tuple[int, list[Record]]:
    if not data.startswith(b"\xaa\x55\x01\x00\x00"):
        raise FormatError("not an AA55 format-1 face container")
    if len(data) < 6:
        raise FormatError("truncated face header")
    at = 6
    records: list[Record] = []
    for group in range(data[5]):
        if at >= len(data):
            raise FormatError("truncated component group")
        kind = data[at]
        at += 1
        count = 1
        if kind in (0, 1):
            if at >= len(data):
                raise FormatError("truncated component count")
            count = data[at]
            at += 1
        if count == 0:
            raise FormatError(f"group {group} contains no records")
        for _ in range(count):
            if at >= len(data):
                raise FormatError("truncated component record")
            bits = data[at]
            at += 1
            delay = 0
            if kind in (0, 1):
                if at >= len(data):
                    raise FormatError("truncated animation delay")
                delay = data[at]
                at += 1
            x, y, width, height = struct.unpack_from("<4H", data, at)
            at += 8
            if not width or not height:
                raise FormatError("zero-sized image")
            if at >= len(data):
                raise FormatError("truncated frame count")
            frame_count = data[at]
            at += 1
            if not frame_count:
                raise FormatError("record contains no image frames")
            offsets = [u32(data, at + 4 * frame) for frame in range(frame_count)]
            at += 4 * frame_count
            records.append(Record(group, kind, bits, delay, x, y, width, height, offsets))
    return at, records


def image_blob(data: bytes, record: Record, offset: int) -> tuple[bytes, list[tuple[int, int, int]] | None, bytes]:
    """Return raw blob, optional RGB palette, and index/pixel bytes."""
    pixels = record.width * record.height
    if record.bits_per_pixel == 8:
        colours = u16(data, offset)
        if not 1 <= colours <= 256:
            raise FormatError(f"invalid palette size {colours} at 0x{offset:x}")
        palette_end = offset + 2 + 2 * colours
        end = palette_end + pixels
        if end > len(data):
            raise FormatError(f"truncated 8-bit image at 0x{offset:x}")
        rgb565 = struct.unpack_from(f"<{colours}H", data, offset + 2)
        palette = [rgb565_to_rgb(value) for value in rgb565]
        indices = data[palette_end:end]
        if max(indices) >= colours:
            raise FormatError(f"palette index outside palette at 0x{offset:x}")
        return data[offset:end], palette, indices
    if record.bits_per_pixel == 16:
        end = offset + pixels * 2
        if end > len(data):
            raise FormatError(f"truncated 16-bit image at 0x{offset:x}")
        return data[offset:end], None, data[offset:end]
    raise FormatError(f"unsupported {record.bits_per_pixel}-bit image at 0x{offset:x}")


def rgb565_to_rgb(value: int) -> tuple[int, int, int]:
    return ((value >> 11) * 255 // 31, ((value >> 5) & 63) * 255 // 63, (value & 31) * 255 // 31)


def make_image(record: Record, palette: list[tuple[int, int, int]] | None, pixels: bytes):
    try:
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("PNG export needs Pillow; install it with: python3 -m pip install Pillow") from error
    if palette is not None:
        image = Image.frombytes("P", (record.width, record.height), pixels)
        flat_palette = [channel for rgb in palette for channel in rgb]
        image.putpalette(flat_palette + [0] * (768 - len(flat_palette)))
        image = image.convert("RGB")
    else:
        rgb = bytearray()
        for value in struct.unpack(f"<{record.width * record.height}H", pixels):
            rgb.extend(rgb565_to_rgb(value))
        image = Image.frombytes("RGB", (record.width, record.height), bytes(rgb))
    return image


def save_png(path: Path, record: Record, palette: list[tuple[int, int, int]] | None, pixels: bytes) -> None:
    make_image(record, palette, pixels).save(path)


def save_gif(path: Path, record: Record, frames: list[tuple[list[tuple[int, int, int]] | None, bytes]]) -> None:
    if len(frames) < 2:
        return
    images = [make_image(record, palette, pixels) for palette, pixels in frames]
    # The delay byte is inferred to be centiseconds from catalogue samples.
    images[0].save(path, save_all=True, append_images=images[1:], loop=0,
                   duration=max(1, record.delay) * 10, disposal=2)


def unpack_container(data: bytes, output: Path, png: bool) -> dict:
    header_size, records = parse_container(data)
    output.mkdir(parents=True, exist_ok=True)
    extracted: dict[int, str] = {}
    metadata = {
        "format": "fitpro-aa55-v1",
        "file_size": len(data),
        "header_size": header_size,
        "groups": data[5],
        "records": [],
    }
    for number, record in enumerate(records):
        entry = asdict(record)
        entry["frames"] = []
        gif_frames: list[tuple[list[tuple[int, int, int]] | None, bytes]] = []
        for frame, offset in enumerate(record.offsets):
            name = f"record-{number:02d}-frame-{frame:02d}"
            raw, palette, pixels = image_blob(data, record, offset)
            if offset in extracted:
                entry["frames"].append({"offset": offset, "shared_with": extracted[offset]})
                gif_frames.append((palette, pixels))
                continue
            raw_name = name + ".raw"
            (output / raw_name).write_bytes(raw)
            frame_info = {"offset": offset, "raw": raw_name, "bytes": len(raw)}
            if palette is not None:
                palette_name = name + ".palette.json"
                (output / palette_name).write_text(json.dumps(palette) + "\n", encoding="utf-8")
                frame_info["palette"] = palette_name
            if png:
                png_name = name + ".png"
                save_png(output / png_name, record, palette, pixels)
                frame_info["png"] = png_name
            extracted[offset] = name
            gif_frames.append((palette, pixels))
            entry["frames"].append(frame_info)
        if png and record.kind in (0, 1) and len(record.offsets) > 1:
            gif_name = f"record-{number:02d}-animation.gif"
            save_gif(output / gif_name, record, gif_frames)
            entry["gif"] = gif_name
            entry["gif_delay_ms"] = max(1, record.delay) * 10
        metadata["records"].append(entry)
    (output / "watchface.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def unpack_bitmap_record(data: bytes, output: Path, png: bool, start: int = 0) -> dict:
    if data[start:start + 2] != b"BM" or len(data) - start < 10:
        raise FormatError("not a standalone FitPro BM palette record")
    width, height, colours, pixel_offset = struct.unpack_from("<4H", data, start + 2)
    if not width or not height or not 1 <= colours <= 256 or pixel_offset != 10 + 2 * colours:
        raise FormatError("invalid standalone BM record")
    record = Record(0, 0, 8, 0, 0, 0, width, height, [0])
    # Reuse the container decoder by presenting its image blob at offset 0.
    blob = struct.pack("<H", colours) + data[start + 10:start + pixel_offset] + data[start + pixel_offset:]
    raw, palette, pixels = image_blob(blob, record, 0)
    output.mkdir(parents=True, exist_ok=True)
    (output / "image.raw").write_bytes(raw)
    (output / "palette.json").write_text(json.dumps(palette) + "\n", encoding="utf-8")
    metadata = {"format": "fitpro-bm-record", "file_size": len(data), "prefix_size": start, "width": width, "height": height, "colours": colours,
                "raw": "image.raw", "palette": "palette.json"}
    if png:
        save_png(output / "image.png", record, palette, pixels)
        metadata["png"] = "image.png"
    (output / "watchface.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def find_bitmap_record(data: bytes) -> int | None:
    """Find a valid custom-image record, including a possible vendor font prefix."""
    at = 0
    while True:
        at = data.find(b"BM", at)
        if at < 0 or at + 10 > len(data):
            return None
        width, height, colours, pixels = struct.unpack_from("<4H", data, at + 2)
        if (width and height and 1 <= colours <= 256 and pixels == 10 + 2 * colours and
                at + pixels + width * height <= len(data)):
            return at
        at += 1


def rgb565(red: int, green: int, blue: int) -> int:
    return (red >> 3) << 11 | (green >> 2) << 5 | (blue >> 3)


def argb_pixels(image) -> list[int]:
    rgba = image.convert("RGBA")
    return [rgb565(red * alpha // 255, green * alpha // 255, blue * alpha // 255)
            for red, green, blue, alpha in rgba.getdata()]


def indexed_216(pixels: list[int]) -> bytes:
    palette = [rgb565(red * 51, green * 51, blue * 51)
               for red in range(6) for green in range(6) for blue in range(6)]
    indices = bytearray()
    for value in pixels:
        red, green, blue = value >> 11, (value >> 5) & 63, value & 31
        indices.append(((red * 5 + 15) // 31) * 36 + ((green * 5 + 31) // 63) * 6 + (blue * 5 + 15) // 31)
    return struct.pack("<H", len(palette)) + struct.pack(f"<{len(palette)}H", *palette) + indices


def indexed_255(pixels: list[int]) -> bytes:
    frequency: dict[int, int] = {}
    for value in pixels:
        frequency[value] = frequency.get(value, 0) + 1
    palette = heapq.nlargest(255, frequency, key=lambda value: (frequency[value], value))
    palette.sort(key=lambda value: (-frequency[value], value))
    exact = {value: index for index, value in enumerate(palette)}
    closest: dict[int, int] = {}
    indices = bytearray()
    for value in pixels:
        index = exact.get(value)
        if index is None:
            index = closest.get(value)
            if index is None:
                red, green, blue = value >> 11, (value >> 5) & 63, value & 31
                index = min(range(len(palette)), key=lambda candidate: (
                    (red - (palette[candidate] >> 11)) ** 2 +
                    2 * (green - ((palette[candidate] >> 5) & 63)) ** 2 +
                    (blue - (palette[candidate] & 31)) ** 2))
                closest[value] = index
        indices.append(index)
    return struct.pack("<H", len(palette)) + struct.pack(f"<{len(palette)}H", *palette) + indices


def direct_rgb565(pixels: list[int]) -> bytes:
    return struct.pack(f"<{len(pixels)}H", *pixels)


def clock_blob(width: int, height: int, lit: set[tuple[int, int]]) -> bytes:
    palette = struct.pack("<3H", 2, 0, 0xFFFF)
    indices = bytearray(1 if (x, y) in lit else 0 for y in range(height) for x in range(width))
    return palette + indices


def digit_blob(digit: int) -> bytes:
    masks = (0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)
    rectangles = ((4, 2, 20, 6), (18, 4, 22, 20), (18, 20, 22, 36),
                  (4, 34, 20, 38), (2, 20, 6, 36), (2, 4, 6, 20), (4, 18, 20, 22))
    lit: set[tuple[int, int]] = set()
    for segment, rectangle in enumerate(rectangles):
        if masks[digit] & (1 << segment):
            x0, y0, x1, y1 = rectangle
            lit.update((x, y) for y in range(y0, y1) for x in range(x0, x1))
    return clock_blob(24, 40, lit)


def colon_blob() -> bytes:
    lit = {(x, y) for y in range(10, 30) if y < 15 or y >= 25 for x in range(3, 7)}
    return clock_blob(10, 40, lit)


@dataclass
class PackRecord:
    kind: int
    bits: int
    delay: int
    x: int
    y: int
    width: int
    height: int
    images: list[bytes]


def make_container(frames: list[bytes], bits: int, delay: int, clock: bool) -> bytes:
    records = [PackRecord(1 if len(frames) > 1 else 0, bits, delay if len(frames) > 1 else 0,
                          0, 0, WIDTH, HEIGHT, frames)]
    if clock:
        x, y = (WIDTH - 110) // 2, HEIGHT - 46
        records += [PackRecord(1, 8, 0, x + 50, y, 10, 40, [colon_blob()]),
                    PackRecord(5, 8, 0, x, y, 24, 40, [digit_blob(number) for number in range(10)]),
                    PackRecord(6, 8, 0, x + 62, y, 24, 40, [digit_blob(number) for number in range(10)])]
    groups = 0
    previous = None
    header_size = 6
    for record in records:
        if record.kind != previous:
            groups += 1
            header_size += 2 if record.kind in (0, 1) else 1
            previous = record.kind
        header_size += 10 + (1 if record.kind in (0, 1) else 0) + 4 * len(record.images)
    header = bytearray(b"\xaa\x55\x01\x00\x00" + bytes([groups]))
    images = bytearray()
    previous = None
    for record in records:
        if record.kind != previous:
            header.append(record.kind)
            if record.kind in (0, 1):
                header.append(sum(1 for other in records if other.kind == record.kind))
            previous = record.kind
        header.append(record.bits)
        if record.kind in (0, 1):
            header.append(record.delay)
        header.extend(struct.pack("<4H", record.x, record.y, record.width, record.height))
        header.append(len(record.images))
        for image in record.images:
            header.extend(struct.pack("<I", header_size + len(images)))
            images.extend(image)
    if len(header) != header_size:
        raise AssertionError("incorrect face header length")
    result = bytes(header + images)
    if len(result) > 7 * 1024 * 1024:
        raise FormatError("face exceeds the watch uploader's 7 MiB limit")
    return result


def cover_frame(image):
    from PIL import Image
    source = image.convert("RGBA")
    scale = max(WIDTH / source.width, HEIGHT / source.height)
    size = (round(source.width * scale), round(source.height * scale))
    resized = source.resize(size, Image.Resampling.LANCZOS)
    left, top = (resized.width - WIDTH) // 2, (resized.height - HEIGHT) // 2
    result = Image.new("RGBA", (WIDTH, HEIGHT), "black")
    result.alpha_composite(resized.crop((left, top, left + WIDTH, top + HEIGHT)))
    return result


def load_frames(path: Path):
    try:
        from PIL import Image, ImageOps
    except ImportError as error:
        raise RuntimeError("Packing images needs Pillow; install it with: python3 -m pip install Pillow") from error
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise FormatError("choose an image smaller than 12 MiB")
    with Image.open(path) as source:
        is_gif = source.format == "GIF"
        if not is_gif:
            return [cover_frame(ImageOps.exif_transpose(source))], 10
        durations, timeline = [], []
        elapsed = 0
        for index in range(getattr(source, "n_frames", 1)):
            source.seek(index)
            duration = source.info.get("duration", 100)
            duration = 100 if duration < 20 else duration
            durations.append(duration)
            elapsed += duration
            timeline.append(elapsed)
        if elapsed > MAX_GIF_DURATION_MS:
            raise FormatError("use a GIF no longer than 30 seconds")
        interval = max(100, ((elapsed + MAX_GIF_FRAMES * 10 - 1) // (MAX_GIF_FRAMES * 10)) * 10)
        frames = []
        for moment in range(0, elapsed, interval):
            index = next(index for index, end in enumerate(timeline) if moment < end)
            source.seek(index)
            frames.append(cover_frame(source.copy()))
        return frames, interval // 10


def pack_image(input_path: Path, output: Path, palette: str, clock: bool, name: str) -> None:
    if output.exists():
        raise FormatError(f"refusing to overwrite existing file: {output}")
    frames, delay = load_frames(input_path)
    encoded = []
    for frame in frames:
        pixels = argb_pixels(frame)
        encoded.append({"216": indexed_216, "255": indexed_255, "direct": direct_rgb565}[palette](pixels))
    binary = make_container(encoded, 16 if palette == "direct" else 8, delay, clock)
    metadata = {
        "format": "fitpro-watchface-1", "name": name,
        "width": "240", "height": "286", "mainModel": "LJ736", "matchModel": "K75",
        "algorithm": "3", "config": "0", "version": "1", "screenType": "0", "grade": "0",
        "customer": "", "slot": "1", "position": "0", "custom": "0",
        "sha256": hashlib.sha256(binary).hexdigest(),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    def properties_escape(value: str) -> str:
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("=", "\\=").replace(":", "\\:")

    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("watchface.properties", "".join(f"{key}={properties_escape(value)}\n" for key, value in metadata.items()))
        archive.writestr("watchface.bin", binary)


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments and arguments[0] == "pack":
        parser = argparse.ArgumentParser(description="Create an upload-ready LJ736/K75 FitPro watch-face ZIP offline.")
        parser.add_argument("input", type=Path, help="PNG, JPEG, or GIF source image")
        parser.add_argument("output", type=Path, help="new .fitpro.zip output file")
        parser.add_argument("--palette", choices=("216", "255", "direct"), default="255",
                            help="216 RGB565 cube, 255 adaptive RGB565 colours, or direct RGB565 pixels")
        parser.add_argument("--no-clock", action="store_true", help="omit the generated live hour/minute clock")
        parser.add_argument("--name", default="My offline watch face", help="display name stored in the ZIP")
        args = parser.parse_args(arguments[1:])
        try:
            pack_image(args.input, args.output, args.palette, not args.no_clock, args.name)
        except (OSError, FormatError, RuntimeError, struct.error) as error:
            parser.error(str(error))
        print(f"Created {args.output} ({args.palette} RGB565, {'clock' if not args.no_clock else 'no clock'})")
        return 0
    if arguments and arguments[0] == "unpack":
        arguments = arguments[1:]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="FitPro .bin face or custom-image record")
    parser.add_argument("output", type=Path, help="new directory for extracted files")
    parser.add_argument("--no-png", action="store_true", help="extract raw blobs and JSON only; does not need Pillow")
    args = parser.parse_args(arguments)
    try:
        data = args.input.read_bytes()
        if data.startswith(b"\xaa\x55\x01\x00\x00"):
            metadata = unpack_container(data, args.output, not args.no_png)
        elif (bitmap_offset := find_bitmap_record(data)) is not None:
            metadata = unpack_bitmap_record(data, args.output, not args.no_png, bitmap_offset)
        else:
            raise FormatError("input is neither an AA55 face container nor a BM custom-image record")
    except (OSError, FormatError, RuntimeError, struct.error) as error:
        parser.error(str(error))
    print(f"Extracted {len(metadata.get('records', [metadata]))} record(s) to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
