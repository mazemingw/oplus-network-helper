# VPN Hotspot integration

The previous source tree corresponds to [v2.19.1](https://github.com/Mygod/VPNHotspot/tree/v2.19.1)
with local changes. This module now integrates the app sources from
[v2.19.2](https://github.com/Mygod/VPNHotspot/tree/v2.19.2), commit
`aa5653ae64fb8994de5eabec33a470fb5da2faf1` (upstream version code 1036).

- This release adds interface state callbacks with a fallback for older Android
  versions, and forwarding for system IPsec VPN upstreams. The host's hotspot
  panel now consumes the same callback as the built-in hotspot screen.
- The libraries share the host app, toolchain, launcher, license screen and
  diagnostic console. Existing Soft AP configuration, P2P and root launcher
  adaptations remain. Firebase is not initialized, and hotspot logs including
  root-side messages enter the host's redacted console. The upstream removal of
  the Tasker plug-in actions is included.
- Compared the Room schemas, database name, boot settings and Soft AP API across
  the 2.x update. The schema is unchanged, and the existing data paths stay in
  place. Kept the host's dependency versions where the upstream source compiles
  against them.

[v3.0.8](https://github.com/Mygod/VPNHotspot/tree/v3.0.8) is the newer upstream
major release. Its Compose screens, client storage schema, root API and routing
implementation are different. Its build also generates supplicant Java stubs
from pinned AOSP AIDL and builds a Rust daemon plus protobuf IPC. A move to 3.x
needs separate work to migrate saved clients/settings, adapt the host's Soft AP
controls and verify Wi-Fi/P2P and VPN forwarding on rooted devices.
