# Oplus Network Helper

[简体中文](README.md)

An Android network and signal analysis tool for Qualcomm and MediaTek devices.
The application ID is `com.nvmex.networkhelper`. The UI is built with Jetpack
Compose and includes a separate VPN/hotspot library module.

## Features

- **Dual-SIM network panel**: compare the operator, data/NR mode, bands, frequency, cell and link information for SIM1 and SIM2.
- **Signal quality**: display RSRP, RSRQ, SINR, RSSI, signal level and score, with historical signal charts.
- **Drive-test map**: record location tracks and network points, with dual-SIM data and tab switching.
- **Qualcomm QOS**: inspect global events, Paging/Mobility and NAS state diagnostics.
- **Engineer-mode tools**: access network engineering functions and clear engineer-mode files.
- **Bands and cells**: inspect LTE/NR bands, cell parameters and carrier aggregation.
- **Network utilities**: iPerf speed tests, Wi-Fi information, Ping and VPN/hotspot management.
- **Xposed/vendor APIs**: some advanced features require root, Xposed/LSPosed or vendor-specific system APIs.

## Screenshots

Screenshots are grouped by feature under [`docs/images`](docs/images). Click an
image to open the full-size version.

### HOME — Home

<p>
  <a href="docs/images/home/home.png"><img src="docs/images/home/home.png" alt="Home" width="260"></a>
  <a href="docs/images/home/control-panel.png"><img src="docs/images/home/control-panel.png" alt="Network control panel" width="260"></a>
  <a href="docs/images/home/qos-data.png"><img src="docs/images/home/qos-data.png" alt="QOS data" width="260"></a>
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

## Configuration

Local configuration, signing files, private keys and Firebase configuration are
excluded from the repository. Inject the AMap key at build time:

```powershell
.\gradlew.bat assembleDebug -PAMAP_API_KEY=your_amap_key
```

You may also add `AMAP_API_KEY=your_amap_key` to your user-level Gradle
properties. Some features require root access, vendor-specific APIs or a
physical target device and may not work on a standard emulator.

## Project structure

```text
app/                  Main application module
vpnhotspot-mobile/    VPN and hotspot library module
gradle/               Gradle wrapper and dependency versions
```

## License

Original code in this project is licensed under the **Apache License 2.0**;
see [LICENSE](LICENSE). The repository also contains third-party code under
other licenses. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and retain
the applicable copyright and license notices.
