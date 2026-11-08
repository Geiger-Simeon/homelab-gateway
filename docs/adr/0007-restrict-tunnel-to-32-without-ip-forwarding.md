# 7. Restrict tunnel to /32 without IP forwarding

Date: 2026-11-08

## Status

Accepted

## Context

The tunnel connects an internet-facing VPS with a machine in the home LAN, which is shared with flatmates. A compromised VPS must not be able to reach anything in the LAN.

## Decision

- `AllowedIPs` on both sides only contains the `/32` of the peer, no `0.0.0.0/0`
- `net.ipv4.ip_forward = 0` on both machines

## Consequences

- through the tunnel, the VPS can only reach `10.99.0.2`, never the LAN behind the Pi
- this limits addresses, not ports, so a firewall on the Pi is still needed to allow only the proxy port (phase 3)
- if something on the Pi turns on forwarding (Docker does), this has to be checked again
