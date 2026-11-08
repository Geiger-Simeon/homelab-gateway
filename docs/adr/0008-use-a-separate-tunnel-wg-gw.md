# 8. Use a separate tunnel wg-gw

Date: 2026-11-08

## Status

Accepted

## Context

The real Pi already runs a WireGuard VPN for my own devices.

## Decision

The gateway tunnel gets its own interface `wg-gw`, its own keys and its own net `10.99.0.0/24`.

## Consequences

- no address or config collision with the existing VPN
- the VPS is not a peer in the network of my own devices
- firewall rules can be written per interface
- two WireGuard interfaces on the Pi, both have to be maintained
