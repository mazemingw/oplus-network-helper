# AMap Falcon Android SDK

`amap-track-1.5.0.jar` contains the unmodified Falcon classes and their Java dependency closure from AMap's official combined distribution. The project already uses `com.amap.api:3dmap:10.0.600`, which supplies a location engine. Adding the full combined jar would duplicate public location classes and replace that engine unintentionally.

Source: [official Android Falcon download](https://lbs.amap.com/api/android-track/download), archive `https://a.amap.com/lbs/static/zip/AMap_Android_Track_SDK_All.zip`, downloaded 2026-10-23. Nested bundle: `AMapTrack_AMapLocation.zip`; source jar: `AMapTrack_1.5.0_AMapLocation_11.2.000_20260529.jar`.

| Artifact | SHA-256 |
| --- | --- |
| Official archive | `dc6c9dfed28c95d34afedef4a3ff880cdb10820ca9d29a8401100cdf387d1d5d` |
| Original combined jar | `305ce94ef599bacfbc250800a88ab993c6ea59ccacc058eefca67ccc31aee490` |
| Reproducible track jar | `4b680012d69382d6dbf2facd662ae2ab930b403c3e8710025d062e7d0dab5385` |

Rebuild with Python 3, passing the existing map jar from Gradle's dependency cache:

```text
python tools/prepare_amap_track.py --map-jar PATH_TO_3dmap-10.0.600.jar
```

The script pins the upstream checksums, follows class constants, descriptors and literal reflective names, excludes classes already supplied by the map SDK, and writes deterministic zip entries. Result: 283 unchanged classes (86 `com.amap.api.track`, 197 `com.amap.api.col.trl`). No new native libraries. Revalidate the closure and runtime collection when upgrading either SDK; this is an integration artifact, not a separate SDK release. AMap's SDK terms continue to apply.

Android authentication uses the existing manifest key and signing certificate. `AMAP_FALCON_SID` is injected from ignored `local.properties` or a Gradle property. Service-management Web keys are never included in this artifact or the app.
