"""Extract Falcon's Java dependency closure without replacing the map SDK's location engine.

Usage: python tools/prepare_amap_track.py --map-jar PATH_TO_3dmap-10.0.600.jar
The official archive is downloaded to ignored tmp/; no credentials are used.
"""
import argparse
import hashlib
import io
from pathlib import Path
import re
import struct
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = "https://a.amap.com/lbs/static/zip/AMap_Android_Track_SDK_All.zip"
ARCHIVE_SHA256 = "dc6c9dfed28c95d34afedef4a3ff880cdb10820ca9d29a8401100cdf387d1d5d"
SDK_SHA256 = "305ce94ef599bacfbc250800a88ab993c6ea59ccacc058eefca67ccc31aee490"
SDK_NAME = "AMapTrack_1.5.0_AMapLocation_11.2.000_20260529.jar"


def verified(data, expected):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"SDK checksum changed: {actual}; review the official release before updating")
    return data


def classes(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name[:-6]: archive.read(name) for name in archive.namelist() if name.endswith(".class")}


def references(data):
    """Class constants, descriptors, signatures and literal reflective class names."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid JVM class")
    count = struct.unpack_from(">H", data, 8)[0]
    strings, class_indexes = {}, []
    index, offset = 1, 10
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            strings[index] = data[offset:offset + length].decode("utf-8", errors="replace")
            offset += length
        else:
            if tag == 7:
                class_indexes.append(struct.unpack_from(">H", data, offset)[0])
            offset += sizes[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    result = {strings[i] for i in class_indexes if not strings[i].startswith("[")}
    for value in strings.values():
        result.update(re.findall(r"L([\w/$]+)(?:;|<)", value))
        if re.fullmatch(r"(?:com[./](?:amap|loc)|android|java|org)[./][\w.$/]+", value):
            result.add(value.replace(".", "/"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--map-jar", required=True, type=Path)
    parser.add_argument("--archive", type=Path, default=ROOT / "tmp/falcon-sdk/official-sdk.zip")
    parser.add_argument("--output", type=Path, default=ROOT / "app/libs/amap-track-1.5.0.jar")
    args = parser.parse_args()
    if not args.archive.exists():
        args.archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = verified(response.read(), ARCHIVE_SHA256)
        args.archive.write_bytes(data)
    else:
        data = verified(args.archive.read_bytes(), ARCHIVE_SHA256)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        bundle = archive.read("AMapTrack_AMapLocation.zip")
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        names = [name for name in archive.namelist() if name.endswith("/" + SDK_NAME) or name == SDK_NAME]
        if len(names) != 1:
            raise ValueError("Unexpected official SDK archive layout")
        sdk = classes(verified(archive.read(names[0]), SDK_SHA256))
    supplied = set(classes(args.map_jar.read_bytes()))
    selected = {name for name in sdk if name.startswith("com/amap/api/track/")}
    pending = list(selected)
    while pending:
        name = pending.pop()
        for dependency in references(sdk[name]):
            if dependency in sdk and dependency not in supplied and dependency not in selected:
                selected.add(dependency)
                pending.append(dependency)
    if selected & supplied:
        raise ValueError("Duplicate classes with the map SDK")
    if any(not name.startswith(("com/amap/api/track/", "com/amap/api/col/trl/")) for name in selected):
        raise ValueError("Unexpected dependency; review the SDK integration")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(selected):
            entry = zipfile.ZipInfo(name + ".class", date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, sdk[name])
    print(f"{len(selected)} unchanged classes, SHA-256 {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
