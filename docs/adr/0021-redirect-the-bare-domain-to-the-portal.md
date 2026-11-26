# 21. Redirect the bare domain to the portal

Date: 2026-11-26

## Status

Accepted

## Context

Only `cloud.<domain>` and `portal.<domain>` were set up. Typing just `<domain>` ended at netcup's default A record, not at the VPS. The bare domain should lead to the portal; later it may get a small public start page with a login button that leads to the portal.

- the VPS only forwards known names (ADR 0011) and decrypts nothing (ADR 0003), so a redirect can't happen there
- the wildcard `*.<domain>` doesn't cover `<domain>` itself (ADR 0020)
- serving the portal directly under the bare domain would change the Entra redirect URIs, the oauth2-proxy config and the portal config, and would block a start page there later

## Decision

- DNS: A record `@` to the VPS
- the VPS forwards `<domain>` like the other names, only when `ACME_DOMAIN` is set; VMs with `.test` names don't get it
- one certificate for `*.<domain>` and `<domain>`; the deploy hook stays as it is, the apex site uses the portal certificate file
- nginx on the Pi answers `<domain>` with `302` to `https://portal.<domain>/`; 302 because browsers keep a 301 and would skip a later start page

## Consequences

- the bare domain works without touching Entra, oauth2-proxy or the portal
- one more name reaches the Pi; the site only redirects, so it adds no content and no login of its own
- a start page later replaces the `return` with a `root`, the login button links to the portal
- a certificate from before this change lacks the bare domain; `homelab-cert` and `setup-pi.sh` notice that and get a new one with `run`, since it's not clear that `renew` in lego 4.9 takes a changed domain list
- `homelab-cert` validates two names, but the wildcard authorization is often still valid, so usually only the bare domain gets a DNS-01 challenge
