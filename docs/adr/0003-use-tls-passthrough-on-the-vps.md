# 3. Use TLS passthrough on the VPS

Date: 2026-11-08

## Status

Accepted

## Context

Two goals that can't both be met:

1. nobody except the Pi can read the content, including the VPS provider
2. unauthenticated traffic is stopped on the VPS, before it reaches the home network

To check a login or session on the VPS, the VPS has to decrypt the traffic, which breaks goal 1. Goal 1 has priority.

## Decision

The VPS forwards TLS unchanged into the tunnel (nginx `stream` with `ssl_preread`, routing by SNI). TLS terminates on the Pi, the certificate and private key live only there.

Rejected:

- TLS termination on the VPS with authentication there: VPS sees all content
- IP allowlisting: doesn't work for foreign devices on changing networks
- self-built gatekeeper on the VPS: own security code, and still needs to decrypt
- mTLS or VPN on the client: not possible on devices that aren't mine

## Consequences

- the VPS only sees SNI, IP addresses and traffic volume
- unauthenticated requests reach the reverse proxy and Nextcloud's login page on the Pi, so updates and brute force protection on the Pi matter
- the Pi sees the VPS as client IP unless the PROXY protocol is used; brute force protection needs the real client IP
- the Pi has to get its certificate itself, the ACME challenge type is still open
