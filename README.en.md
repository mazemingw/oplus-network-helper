# Oplus Network Helper

[简体中文](README.md)

An Android network and signal analysis tool for Qualcomm and MediaTek devices.
The application ID is `com.nvmex.networkhelper`. The UI is built with Jetpack
Compose and includes a separate VPN/hotspot library module.

## Source version

- Current source version: `v1.6.1` (versionCode `61`).
- Architecture: `arm64-v8a`

## Features

- **Dual-SIM network panel**: compare operator, data/NR mode, bands, channels, cells and links. The cell timeline shows channels and RSRP. Collapsible RX measurements are embedded in the panel, with the expansion preference shared across SIMs.
- **Signal quality**: display RSRP, RSRQ, SINR, RSSI, signal level and score, with historical signal charts.
- **Drive-test map**: save both SIMs at each sampling tick and switch recorded datasets with SIM tabs. Hide station triangles and choose an overview or starting-point replay camera; overview is the default. Nearby station queries support a 1–10 km radius with independent 4G/5G and optional band filters.
- **QoS diagnostics**: inspect Qualcomm DATA/IMS QCI, AMBR and NAS information with per-SIM caches. The experimental MediaTek AT reader has not been verified on a MediaTek device.
- **Engineer-mode tools**: access network engineering functions and clear engineer-mode files.
- **Band and cell control**: on rooted Qualcomm devices, use QMI to read hardware-supported bands, set RATs/bands and manage cell locks, with operator filtering, serving-band highlights and registration feedback. Legacy locking remains optional.
- **Local data management**: manage imported cells and locally recorded cell information. Dedicated home-log and drive-record pages support Excel-compatible CSV import/export, including original and GCJ02 coordinates for new drive points. See the [data-management guide (Chinese)](docs/local-data-management.md).
- **Network utilities**: iPerf speed tests, Wi-Fi information, Ping and VPN/hotspot management; Wi-Fi channel plots compress idle ranges and display separate 80+80 MHz segments.
- **Weak Wi‑Fi cutoff** — Open settings at the top of the Wi‑Fi panel to enable background monitoring. Two readings at or below the threshold, 7 seconds apart, disconnect the current access point. The default is −80 dBm. Wi‑Fi stays enabled and Android can reconnect automatically.
- **Updates and plugin sharing**: show download, validation and installer phases, validate APK package/version/signatures, and import standalone plugins or sharing archives that contain framework files.
- **Xposed/vendor APIs**: some advanced features require root, Xposed/LSPosed or vendor-specific system APIs.

## Band/cell control and RX measurements

The QMI integration was reviewed through qcom-band-menu `e924f38`. Band locking writes a changed RAT before restoring known band masks because some modems reset the masks on RAT changes. An explicit rejection of a 5G mode change marks the mode read-only and reports the outcome of the other operations. [qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu/commit/e924f388b8c69dfbe7f91e76a7721698d8099e5a)

- Band selections start empty and appear in NR SA, NR NSA, LTE, WCDMA, GSM order. RAT selection controls card visibility. Buttons include nominal frequencies or SUL labels, with separate selected and serving indicators. Mainland China mode defaults on and filters bands by the selected SIM's registered operator.
- Cell candidates require valid PCI and complete parameters, ordered as neighbors, recent serving history, LTE CA, then current serving cells. The open menu stays stable and shows RSRP bars and current-cell badges. NRCA carrier frequencies are excluded from cell-lock candidates.
- Home and control screens share per-SIM serving data. Cell lock feedback checks modem readback and fresh registration samples; insufficient evidence remains unconfirmed. Unlock clears actual active lock types. Optional RAT coupling saves and restores the previous settings.
- The Home lock button opens controls for the visible SIM. Both screens mark the default data SIM with the same icon. Multi-antenna RX measurements are available on Qualcomm devices.
- Tap a Home cell-timeline record to confirm a cell/band shortcut. The actual SIM slot and subscription are carried to the control page, which checks hardware/operator rules before filling fields or selecting bands. Tap Lock manually to execute. FDD/TDD SCS defaults are 15/30 kHz and remain editable. Controls use rectangular buttons, with solid checked selections and a compact lock-readback panel.
- Floating Lock/Unlock buttons have auto-dismiss status alerts. Reset channel uses bounded session recovery; a confirmed QMI 5 binding conflict can stop the verified upstream application's background helper and release its clients without clearing band/cell locks.
- Available bands still opens the operator-band dialog, preferring real hardware capabilities and falling back to built-in lists only when unavailable. N3/N8 are labeled Uncommon. Legacy band locking defaults off; MediaTek users may try enabling it in Settings.
- LTE CA comes only from QMI. NRCA prefers the existing engineering feed, then QMI SCELL frequencies, then no data. RX measurements use compact rows in the network panel, with colored signal bars and a smooth 24dp filled PHASE pie.
- NRCA chips show converted bands; raw channels remain in details. The timeline prefers neighbors with valid PCI and falls back to serving history otherwise. Visible neighbor batches stay stable for at least 3 seconds. RX keeps fixed chain positions and retains observed RAT sections during each SIM's expanded view, clearing missing readings instead of resizing as 2/4 chains or LTE/NR groups change. RX, CA and neighbors share one read-only session with at most two reconnect attempts for transient binding failures.

See the [QMI control guide (Chinese)](docs/qmi-controls.md) for data sources, recovery limits, building and device checks, plus [upstream synchronization](app/src/main/cpp/qmi/UPSTREAM.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Drive testing and nearby stations

Open **Map drawing and discovery** from the drive-map FAB (previously sector drawing settings). Configure sector geometry, sampling interval, nearby discovery, a 1–10 km radius, RAT and optional band. Automatic discovery is off by default, with a 3 km default radius; you can also request an immediate query. Results come from the backend cell-parameter database.

Select all RATs, 4G or 5G independently of the band. Leave the band empty for all bands. A number such as `3` matches that band number within the selected RATs; `B3` restricts LTE and `n78` restricts NR. Conflicting RAT/prefix selections are rejected. Filtering uses channel-number ranges, so overlapping NR bands can match the same frequency.

Opening the map keeps the current location. Restoring a recording defaults to fitting the selected SIM's band-filtered track. More → Initial replay view saves an overview/start preference, with immediate Fit entire track / Go to track start actions. Station triangles can be hidden during recording and replay. SIM tabs select each recorded dataset, including older single-SIM recordings. See the [drive-map guide (Chinese)](docs/drive-test-map.md).

Map updates add dense markers with continuous full-resolution tracks, handover/serving-sector switches, a red serving-station distance link, scrolling operator names and dedicated map/2D/3D buttons. Recordings show their start city and estimated length, save on exit and can be explicitly selected for continued recording. GPS, accelerometer and gyroscope fusion starts when the map opens and calibrates while driving. It estimates turns and speed changes during GPS outages for up to 10 minutes by default (configurable from 1–30 minutes), retaining drift indicators; vehicle validation is still required.

AMap positioning and sampling take priority without waiting for IMU readiness. The speed panel shows only speed and IMU Ready/Not ready. Nearby stations show markers; antenna sectors belong only to the current serving station, including the selected point during replay. Connected station and antenna-sector snapshots are saved with each Session for offline per-SIM replay; marker details append frequencies in MHz. The dual-SIM comparison page scrolls vertically and includes the cell type. NR channel 520830 is labeled as a metro private network, while existing high-speed railway labels are retained.

The Apache 2.0 backend has its own repository: [NetworkHelperBack](https://github.com/mazemingw/NetworkHelperBack). Deploy the updated backend and restart it to enable the [nearby station API](https://github.com/mazemingw/NetworkHelperBack/blob/main/nearby-api.md).

Optional Falcon correction is off by default. The official AMap Android Falcon SDK uploads and queries driving tracks with road matching; no route import is required. Gathering starts and stops with road-test recording; browsing the map does not upload a track. Raw fixes, inertial estimates and dual-SIM measurements remain separate. Railway and subway correction is not supported. The map has translucent overlays, upper-right map/view/speed controls, lower-right actions aligned with the RSRP legend, and bottom-center zoom controls. The serving station is green; selected markers are highlighted, and tapping an information bubble copies its details. Point details and CSV include RSRQ, SINR and NR SS measurements. See [integration and device acceptance notes (Chinese)](docs/map-matching-mvp.md).

Under the More FAB, Band filter and coverage accepts separate comma-separated LTE/NR bands, such as `3,41` and `41,28`, for live points and recording replay. The overlay lists the selected bands and their distance coverage; outlined bright-purple lines retain the unselected road-test path, and all raw samples remain saved. NRCA bandwidth decoding on newer devices uses system capability checks while retaining legacy decoding. See the [analysis (Chinese)](docs/nrca-pyb110-analysis.md).

## Screenshots

Screenshots are grouped by feature under [`docs/images`](docs/images). Click an
image to open the full-size version.

### HOME — Home

<p>
  <a href="docs/images/home/home.png"><img src="docs/images/home/home.png" alt="Home" width="260"></a>
  <a href="docs/images/home/control-panel.png"><img src="docs/images/home/control-panel.png" alt="Network control panel" width="260"></a>
  <a href="docs/images/home/qos-data.png"><img src="docs/images/home/qos-data.png" alt="QOS data" width="260"></a>
</p>

### LOCKBAND — Band locking

These screenshots show the legacy interface. See above for the current QMI interface and features.

<p>
  <a href="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-43-38_0b59cd5314832a..png"><img src="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-43-38_0b59cd5314832a..png" alt="Band locking settings" width="260"></a>
  <a href="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-54-70_0b59cd5314832a..png"><img src="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-54-70_0b59cd5314832a..png" alt="Band locking details" width="260"></a>
</p>

### RADAR — Signal radar

<p>
  <a href="docs/images/radar/Screenshot_2026-09-12-04-37-58-73_0b59cd5314832a..png"><img src="docs/images/radar/Screenshot_2026-09-12-04-37-58-73_0b59cd5314832a..png" alt="Signal radar" width="260"></a>
</p>

### WIFI — Wi-Fi

<p>
  <a href="docs/images/WIFI/Screenshot_2026-09-12-04-38-19-73_0b59cd5314832a..png"><img src="docs/images/WIFI/Screenshot_2026-09-12-04-38-19-73_0b59cd5314832a..png" alt="Wi-Fi overview" width="260"></a>
  <a href="docs/images/WIFI/Screenshot_2026-09-12-04-38-32-71_0b59cd5314832a..png"><img src="docs/images/WIFI/Screenshot_2026-09-12-04-38-32-71_0b59cd5314832a..png" alt="Wi-Fi details" width="260"></a>
</p>

### HOTSPOT — VPN hotspot

<p>
  <a href="docs/images/hotspot/Screenshot_2026-09-12-04-38-57-83_0b59cd5314832a..png"><img src="docs/images/hotspot/Screenshot_2026-09-12-04-38-57-83_0b59cd5314832a..png" alt="VPN hotspot" width="260"></a>
</p>

### MENU — Menu

<p>
  <a href="docs/images/menu/Screenshot_2026-09-12-04-39-08-14_0b59cd5314832a..png"><img src="docs/images/menu/Screenshot_2026-09-12-04-39-08-14_0b59cd5314832a..png" alt="Menu" width="260"></a>
  <a href="docs/images/menu/Screenshot_2026-09-12-04-39-11-61_0b59cd5314832a..png"><img src="docs/images/menu/Screenshot_2026-09-12-04-39-11-61_0b59cd5314832a..png" alt="Menu tools" width="260"></a>
</p>

### SETTINGS — Settings

<p>
  <a href="docs/images/settings/Screenshot_2026-09-12-04-39-27-05_0b59cd5314832a..png"><img src="docs/images/settings/Screenshot_2026-09-12-04-39-27-05_0b59cd5314832a..png" alt="Settings" width="260"></a>
  <a href="docs/images/settings/Screenshot_2026-09-12-04-39-31-15_0b59cd5314832a..png"><img src="docs/images/settings/Screenshot_2026-09-12-04-39-31-15_0b59cd5314832a..png" alt="Settings details" width="260"></a>
</p>

## Build requirements

- Android Studio (stable recommended)
- JDK 17 (Gradle 8.13 does not support JDK 25)
- Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`

Open the project root in Android Studio and wait for Gradle sync:

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Run local unit tests and build without connecting a device:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Verified on 2026-10-26: v1.6.0 Debug APK build succeeded; 380 unit tests, 377 passed, 3 skipped for missing local real-world samples, 0 failures. Native QMI sessions and the recovery script have separate mocked tests; commands are in the [QMI guide (Chinese)](docs/qmi-controls.md). The test APK was kept locally without installation. Shortcut navigation, dual-SIM writes, locking and actual registration still require device acceptance testing.

See the [existing compatibility verification record](docs/2026-09-18-compatibility-verification.md) and the [drive-map checks and manual test steps](docs/drive-test-map.md).

## Configuration

The local `local.properties` file, signing files, private keys and Firebase
configuration are excluded from version control. Add your AMap key to the
project-root `local.properties`; both debug and release builds will then use it
automatically:

```properties
AMAP_API_KEY=your_amap_key
```

You can temporarily override the local value with `-PAMAP_API_KEY=...` on the
Gradle command line. Note that the key is packaged into the APK; restrict it to
the Android package name/signing SHA-1 in the AMap console and never commit
`local.properties`. Some features require root access, vendor-specific APIs or
a physical target device and may not work on a standard emulator.

## Project structure

```text
app/                  Main application module
bunny-modem/          bunnylock backends and Root/Shizuku bridge (GPL-3.0)
vpnhotspot-mobile/    VPN and hotspot library module
gradle/               Gradle wrapper and dependency versions
docs/                 Feature guides, verification records and screenshots
```

## Modem drivers and acknowledgements

The **Drivers & settings** tab in band/cell control offers the original Qualcomm
QMI driver and bunnylock's automatic detection, Qualcomm QMI, MediaTek AT, Pixel,
and Android radio HAL backends. Network/modem restart, channel cleanup, an AT
console, device diagnostics, and logs use Network Helper's own Compose UI.

Rootless access requires a running, authorized **Shizuku** service. ADB access
supports the Android radio HAL and MediaTek RIL passthrough where the device
actually provides it; direct QMI and PCI locking are not universally available.
Switching drivers releases the current session without clearing locks. Cleanup
releases our helper and, with root, discovers verified qcom-band-menu and
bunnylock backend processes. It never sends unlock commands or restarts the
system radio service. With root, actual QRTR/QMUX descriptor owners are also
inspected and verified as third-party processes, without relying on two package
names. Cleanup automatically reconnects and refreshes the selected driver;
kernel/permission limits are reported. Both drivers use the same capability-driven
band and cell control UI.

Our sincere thanks to the authors and contributors of
[Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu) and
[btsearch/bunnylock](https://github.com/btsearch/bunnylock). Their open protocol
implementations, backends, and compatibility work make broader device support
possible. Their original icons identify driver provenance in the app.

See [backend integration and validation](docs/modem-backends.md).

If cleanup readback still fails, a dialog lists known system NAS users, their live init
state, roles, and the effects of pausing them. Recovery pauses one confirmed component at
a time and checks the selected backend again. **Choose for me** defaults to on (selection
still requires confirmation); **Automatic restore** defaults to off. When enabled, exit
restores only components that this app paused from a running state. Previously stopped
services are preserved. See [system channel recovery](docs/qmi-system-recovery.md).

## License

Original code in this project is licensed under the **Apache License 2.0**;
see [LICENSE](LICENSE). The repository also contains third-party code under
other licenses. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and retain
the applicable copyright and license notices.

The vendored `bunny-modem` source remains GPL-3.0. The combined application
containing this module is distributed under GPL-3.0 terms; existing Apache-2.0
notices on original files remain in place. See [bunny-modem/LICENSE](bunny-modem/LICENSE).
