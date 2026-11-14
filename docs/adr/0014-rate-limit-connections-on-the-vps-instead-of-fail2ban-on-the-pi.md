# 14. Rate limit connections on the VPS instead of fail2ban on the Pi

Date: 2026-11-14

## Status

Accepted

## Context

Unwanted traffic should stop at the VPS, the home network is shared. The usual tool for repeated failed logins is fail2ban, which reads logs and bans IPs in the firewall.

- on the Pi, every connection comes from `10.99.0.1` on the network level (ADR 0010), the real client IP is only inside the PROXY header; a firewall ban on the Pi would block the VPS and with it every user
- the VPS can't see failed logins, it never decrypts anything (ADR 0003); fail2ban there would need the Pi's logs shipped back through the tunnel, which the VPS firewall blocks on purpose
- Nextcloud already throttles failed logins per client IP (brute force protection) and works with the IP from the PROXY header

## Decision

- VPS: nftables limits new connections per source IP with dynamic sets (SSH 5/min, burst 10; HTTPS 20/s, burst 50), nginx `stream` limits open connections per IP (`limit_conn 30`) and waits at most 5 s for the ClientHello
- Pi: no fail2ban; failed logins are handled by Nextcloud's brute force protection
- SSH: key only, so there is nothing to brute force

## Consequences

- scanners and connection floods get slowed down before they reach the tunnel; dropped SYNs are retransmitted, regular clients only see a short delay
- the limits don't stop password guessing over one keep-alive connection, that stays Nextcloud's job
- many users behind one IP (CGNAT, office) share the limits; 30 open connections and 20 new per second are far above what a browser (HTTP/2, one connection) or the desktop client needs
- no automatic bans of persistent attackers; possible later with nftables sets fed by the Pi, only if monitoring shows a need
- drop counters on the rules give numbers for monitoring
