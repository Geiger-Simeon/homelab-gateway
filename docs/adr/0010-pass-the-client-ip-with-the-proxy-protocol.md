# 10. Pass the client IP with the PROXY protocol

Date: 2026-11-08

## Status

Accepted

## Context

With TLS passthrough (ADR 0003) the VPS opens its own TCP connection to the Pi. Without extra information, every request on the Pi comes from `10.99.0.1`:

- Nextcloud's brute force protection would throttle all users together, or one attacker would lock everyone out
- logs on the Pi don't show who actually connected

The VPS can't add an HTTP header like `X-Forwarded-For`, it never sees HTTP.

## Decision

nginx on the VPS sends the PROXY protocol (v1) header in front of each forwarded connection (`proxy_protocol on`). The reverse proxy on the Pi reads it and uses the address as client IP, but only for connections from `10.99.0.1`.

## Consequences

- the Pi sees the real client IP, brute force protection and logs work per client
- port 443 on the Pi only works with a PROXY header in front; a normal TLS client can't connect there directly, a direct LAN access to Nextcloud needs its own port
- anyone who can reach that port can claim any client IP, so only the VPS may reach it; the firewall (ADR 0009) allows 443 only from `10.99.0.1` over `wg-gw`
- the header is plain text inside the tunnel, it contains only addresses and ports
