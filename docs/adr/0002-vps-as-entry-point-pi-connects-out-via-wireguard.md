# 2. VPS as entry point, Pi connects out via WireGuard

Date: 2026-11-08

## Status

Accepted

## Context

Nextcloud runs on a Raspberry Pi at home. The internet connection uses CGNAT, so the Pi can't be reached from outside and port forwarding on the router doesn't help.

Access has to work from any browser, including devices that aren't mine, so a VPN client on the device is not an option.

## Decision

A small VPS (netcup VPS pico) is the public entry point.

The Pi opens an outgoing WireGuard tunnel to the VPS and keeps it alive with `PersistentKeepalive = 25`. The VPS only listens, it never needs to reach the Pi on its own.

Rejected:

- Cloudflare Tunnel: terminates TLS at Cloudflare, 100 MB limit per request
- port forwarding / DynDNS: doesn't work behind CGNAT

## Consequences

- no open port on the home router
- after a VPS reboot the tunnel comes back without touching the Pi, because the Pi keeps sending keepalives
- the VPS costs money (12 months minimum term) and is another internet-facing machine that needs updates and hardening
- if the VPS is down, there is no access from outside
