# 20. Get the certificate with DNS-01 through deSEC

Date: 2026-11-25

## Status

Accepted, certificate names amended by [ADR 0021](0021-redirect-the-bare-domain-to-the-portal.md)

## Context

TLS ends on the Pi (ADR 0003), so the Pi needs its own certificate; the ACME challenge type was left open there. The VPS should keep only 443 open.

- HTTP-01 needs port 80 open on the VPS and forwarded through the tunnel; plain HTTP has no SNI, so the VPS can't filter by name
- TLS-ALPN-01 would need ALPN routing on the VPS and a second path without the PROXY header, all custom
- DNS-01 through the netcup API: the domain sits on netcup CloudDNS, the old CCP API can't see the zone (`5029 zone could not be found` in a test), the lego provider for the new API isn't released yet; a CCP API key can't be limited to one record either
- DNS-01 through deSEC: free DNS hosting with API tokens that can be restricted to a single RRset; `dedyn.io` names can't be registered at the moment, so the zone is a subdomain of the own domain

## Decision

- netcup stays the DNS provider of the domain. `acme.<domain>` is delegated to deSEC (NS records at netcup), `_acme-challenge.<domain>` is a CNAME to `_acme-challenge.acme.<domain>`
- the deSEC token on the Pi may only write TXT on `_acme-challenge.acme.<domain>` (token policy), it can't touch the real zone
- one wildcard certificate `*.<domain>` for `cloud` and `portal`; their names don't show up in the CT logs
- lego from the Debian package; a daily systemd timer renews 30 days before expiry, a hook copies the certificate to the nginx paths and reloads nginx
- `ACME_DOMAIN` empty: self-signed test certificates as before, for the VMs with `.test` names

## Consequences

- the VPS stays at 443 only
- renewals depend on deSEC and Let's Encrypt being reachable; a failed run is retried the next night, there are 30 days of buffer
- whoever owns the Pi can get certificates for `*.<domain>`, but can't change the DNS records
- a CAA record limited to Let's Encrypt and `dns-01` keeps a compromised VPS from getting a certificate through the traffic it forwards
- Let's Encrypt sends no expiry mails anymore, the timer has to be watched (journal, later monitoring)
- the Debian lego is old (4.9.1) and waits only 60 s for DNS by default; deSEC needed about two minutes in the test, the script waits up to ten
- once lego supports the new netcup API, deSEC could go; the CNAME would then point into a netcup zone or go away
