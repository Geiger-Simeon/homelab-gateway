# 9. Use nftables with a dedicated table

Date: 2026-11-08

## Status

Accepted

## Context

ADR 0007 limits the tunnel to the two `/32` addresses, but not to ports. Both machines need a host firewall:

- the VPS is reachable from the internet and must not accept new connections from the Pi
- the Pi must only accept the proxy port from the VPS, nothing else from the tunnel
- the home LAN is shared, so the Pi has to stay reachable via SSH from there

Debian 13 ships nftables. The default `/etc/nftables.conf` starts with `flush ruleset`, which also deletes rules other tools add (Docker, possibly later on the Pi) on every reload.

Alternatives:

- ufw: another layer on top, the effective rules are harder to read and to keep in the repo as a single file
- iptables: legacy on Debian, the `iptables` command already translates to nftables

## Decision

- nftables on both machines, one file per host in `configs/<host>/etc/nftables.conf`, loaded by `nftables.service`
- all rules live in an own table `inet gateway`; the file replaces only this table (`table` + `delete table`), no `flush ruleset`
- policy drop on `input` and `forward`, `output` is not filtered
- VPS: 22/tcp, 443/tcp, WireGuard 51820/udp; everything new arriving on `wg-gw` is dropped
- Pi: from `wg-gw` only 443/tcp from `10.99.0.1`; SSH only from the LAN

## Consequences

- the file in the repo is exactly what runs on the machine, it contains no secrets
- a reload doesn't touch tables of other tools
- an `accept` in this table doesn't override a `drop` in another table on the same hook, and vice versa: a packet has to pass all base chains
- the forward policy drop blocks Docker's published ports, the Pi rules have to be revisited if Nextcloud runs in Docker (phase 5)
- the Pi can't ping the VPS through the tunnel, connectivity is checked from the VPS side or with `wg show`
- the real Pi needs additional rules for the services already running there (existing VPN), the real VPS exposes SSH to the internet (hardening in phase 6)
