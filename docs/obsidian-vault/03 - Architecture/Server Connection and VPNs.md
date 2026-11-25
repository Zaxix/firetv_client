---
tags: [feature, architecture]
---
# Server Connection and VPNs

## What it does
Finds the server from whatever was typed, keeps working when the address changes (home network ↔
VPN), and reaches a home server while a full-tunnel VPN app is on.

## How it works
- `ServerAddress.candidates()` turns input ("192.168.1.10", "jf.example.com", a pasted web URL) into
  base URLs; `SessionManager.discover()` probes them **all at once** and keeps the preferred one that
  answers (a slower preferred address gets 400 ms grace), so a VPN that silently drops wrong ports
  doesn't make each guess wait out a timeout.
- Each server remembers up to 5 other addresses that answered as the **same server id** (home and
  VPN/Tailscale). At start, if the saved one fails, the others are raced and the app switches.
- **`LanRoute`** (Android): with a full-tunnel VPN on, connections to an address on the physical
  network's own subnet go over Wi-Fi/Ethernet (when the VPN allows it); everything else still uses
  the VPN.
- Errors are specific: refused (e.g. remote access disabled on the server, HTTP 403) vs not Jellyfin
  vs timeout vs unreachable.

## Relevant files
`data/SessionManager.kt`, `jellyfin/ServerAddress.kt`, `androidMain/.../LanRoute.kt`,
`androidMain/.../HttpEngine.kt`, `core/AppError.kt`; tests `VpnConnectivityTest`.

## Related notes
[[Server Overview]] · [[Backend Architecture]]
