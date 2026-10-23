"""Download a small REAL OSM extract, retaining original nodes (no synthetic geometry).
Run from repo root. Output data is ODbL-1.0, separately from application code.
"""
import datetime
import json
import pathlib
import urllib.request
import xml.etree.ElementTree as ET

BBOX = (116.38, 39.903, 116.41, 39.91)
URL = "https://api.openstreetmap.org/api/0.6/map?bbox=" + ",".join(map(str, BBOX))
CACHE = pathlib.Path("app/build/reports/osm-beijing-source.osm")
if not CACHE.exists():
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(URL, headers={"User-Agent": "NetworkHelper-map-matching-MVP/1.0"})
    CACHE.write_bytes(urllib.request.urlopen(request, timeout=40).read())
root = ET.parse(CACHE).getroot()
nodes = {n.attrib["id"]: [float(n.attrib["lon"]), float(n.attrib["lat"])] for n in root.findall("node")}
features = []
for way in root.findall("way"):
    tags = {t.attrib["k"]: t.attrib["v"] for t in way.findall("tag")}
    road = "\u957f\u5b89\u8857" in tags.get("name", "") and tags.get("highway") == "trunk"
    rail = tags.get("railway") == "subway" and tags.get("name", "") in ["\u5317\u4eac\u5730\u94c11\u53f7\u7ebf", "\u5317\u4eac\u5730\u94c18\u53f7\u7ebf"]
    if not (road or rail):
        continue
    refs = [n.attrib["ref"] for n in way.findall("nd")]
    # Retain contiguous runs inside the declared rectangle, using exact source nodes only.
    runs, run = [], []
    # The map API returns complete ways/nodes. Keep a longer ORIGINAL Line 8 run for outage replay.
    clip = (116.38, 39.895, 116.41, 39.919) if rail and tags.get("name") == "\u5317\u4eac\u5730\u94c18\u53f7\u7ebf" else BBOX
    for ref in refs:
        lng, lat = nodes[ref]
        if clip[0] <= lng <= clip[2] and clip[1] <= lat <= clip[3]:
            run.append(ref)
        else:
            if len(run) >= 2:
                runs.append(run)
            run = []
    if len(run) >= 2:
        runs.append(run)
    for part, refs_in in enumerate(runs):
        features.append({"type": "Feature", "properties": {
            "id": f"osm_way_{way.attrib['id']}_{part}", "osm_way_id": way.attrib["id"],
            "osm_version": way.attrib["version"], "osm_timestamp": way.attrib["timestamp"],
            "source_url": f"https://www.openstreetmap.org/way/{way.attrib['id']}",
            "name": tags.get("name", ""), "route_type": "ROAD" if road else "RAIL",
            "highway": tags.get("highway", ""), "railway": tags.get("railway", ""),
            "oneway": tags.get("oneway", "no"), "start_node": refs_in[0], "end_node": refs_in[-1],
            "corridor": tags.get("name", "") if rail else "",
            "node_ids": refs_in}, "geometry": {"type": "LineString", "coordinates": [nodes[r] for r in refs_in]}})
all_points = [p for f in features for p in f["geometry"]["coordinates"]]
extent = [min(p[0] for p in all_points), min(p[1] for p in all_points), max(p[0] for p in all_points), max(p[1] for p in all_points)]
result = {"type": "FeatureCollection", "name": "\u5317\u4eac\u957f\u5b89\u8857 / \u5730\u94c11\u30018\u53f7\u7ebf\u5c0f\u8303\u56f4\u6d4b\u8bd5\u6458\u5f55",
          "coordinate_system": "WGS84", "bbox": extent, "source": URL,
          "downloaded_utc": datetime.datetime.fromtimestamp(CACHE.stat().st_mtime, datetime.timezone.utc).isoformat(),
          "attribution": "© OpenStreetMap contributors", "license": "ODbL-1.0",
          "license_url": "https://opendatacommons.org/licenses/odbl/1-0/", "features": features}
target = pathlib.Path("app/src/main/assets/map_matching/beijing_sample.geojson")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"Saved {len(features)} real OSM ways/parts; {sum(len(f['geometry']['coordinates']) for f in features)} original nodes; {target.stat().st_size} bytes")
