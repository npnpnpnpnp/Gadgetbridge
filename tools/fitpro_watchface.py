#!/usr/bin/env python3
"""Package a prepared FitPro watch-face payload. This does not convert images or firmware."""
import argparse
import hashlib
from pathlib import Path
import re
import zipfile

MAX_SIZE = 7 * 1024 * 1024
FIELDS = (
    "width",
    "height",
    "mainModel",
    "matchModel",
    "algorithm",
    "config",
    "screenType",
    "grade",
    "customer",
    "version",
)


def read_properties(path):
    """Read the UTF-8 java.util.Properties export produced by the Android activity."""
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.lstrip()
        if not line or line.startswith(("#", "!")):
            continue
        parts = re.split(r"(?<!\\)[=:]", line, maxsplit=1)
        if len(parts) != 2:
            raise ValueError("Invalid display property: " + line)

        def unescape(value):
            value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m[1], 16)), value)
            return re.sub(
                r"\\(.)",
                lambda m: {"n": "\n", "r": "\r", "t": "\t", "f": "\f"}.get(m[1], m[1]),
                value,
            )

        result[unescape(parts[0].rstrip())] = unescape(parts[1].lstrip())
    return result


def escape(value):
    return (
        str(value)
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("=", "\\=")
        .replace(":", "\\:")
        .replace(" ", "\\ ")
    )


def pack(args):
    display = read_properties(args.display)
    missing = set(FIELDS) - display.keys()
    if missing:
        raise ValueError("Missing display fields: " + ", ".join(sorted(missing)))
    for field in set(FIELDS) - {"mainModel", "matchModel", "customer"}:
        if int(display[field]) < 0:
            raise ValueError("Negative " + field)
    slots = int(display.get("slots", "0"))
    if not 1 <= args.slot <= max(1, slots):
        raise ValueError("Slot is outside the watch's reported range")
    if not 0 <= args.position <= 255:
        raise ValueError("Position must be between 0 and 255")
    if args.binary.stat().st_size > MAX_SIZE:
        raise ValueError("Payload exceeds 7 MiB")
    data = args.binary.read_bytes()
    if not data:
        raise ValueError("Empty binary")
    metadata = {key: display[key] for key in FIELDS}
    metadata.update(
        format="fitpro-watchface-1",
        name=args.name,
        slot=args.slot,
        position=args.position,
        custom=int(args.custom),
        sha256=hashlib.sha256(data).hexdigest(),
    )
    manifest = "".join(f"{key}={escape(value)}\n" for key, value in metadata.items())
    # Never overwrite an existing package accidentally.
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("watchface.properties", manifest.encode("utf-8"))
        archive.writestr("watchface.bin", data)
    print(f"Created {args.output} ({len(data)} payload bytes)")
    print(
        "The package is labelled for this display; the binary itself must already be compatible."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--display",
        required=True,
        type=Path,
        help="Display details exported by Gadgetbridge",
    )
    parser.add_argument(
        "--binary",
        required=True,
        type=Path,
        help="Final prepared watch-face payload, including any required thumbnail/font",
    )
    parser.add_argument(
        "--output", required=True, type=Path, help="New .fitpro.zip package"
    )
    parser.add_argument("--name", required=True)
    parser.add_argument(
        "--slot",
        type=int,
        default=1,
        help="One-based slot; use 1 for watches with slots=0",
    )
    parser.add_argument(
        "--position",
        type=int,
        default=0,
        help="Layout position from the source face metadata",
    )
    parser.add_argument(
        "--custom",
        action="store_true",
        help="Set only for a prepared custom-photo face",
    )
    args = parser.parse_args()
    try:
        pack(args)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
