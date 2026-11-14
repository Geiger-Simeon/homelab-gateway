# 16. Install updates automatically with a nightly reboot

Date: 2026-11-14

## Status

Accepted

## Context

Both hosts are reachable from the internet (VPS directly, Pi through the tunnel). Security updates have to arrive without me logging in. Kernel and some library updates only take effect after a reboot.

## Decision

- `unattended-upgrades` on both hosts with the Debian default origins (security and stable point releases)
- automatic reboot if an update needs one: VPS at 04:00, Pi at 04:30, so the tunnel isn't rebuilt while both are down
- Nextcloud itself is not installed through apt and is updated by hand with its updater

## Consequences

- updates are installed within a day without manual work
- the service is down for a few minutes at night after kernel updates; the tunnel comes back on its own
- a broken update or a host that doesn't come back is only noticed through monitoring, which isn't there yet
- Nextcloud updates depend on me: `occ update:check` and the update notification in the admin UI, later monitoring
