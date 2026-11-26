# homelab-gateway

Browser access to a self-hosted Nextcloud and a small portal on a Raspberry Pi at home, from any device and without a VPN. A small VPS is the public entry point. It passes the encrypted traffic through a WireGuard tunnel to the Pi and can't read any of it. Login goes through Microsoft Entra ID.

## How it works

```
Browser ---- HTTPS 443 ----> VPS ===== WireGuard =====> Raspberry Pi
   |                         nginx stream               nginx (TLS ends here)
   |                         SNI filter                 Nextcloud, portal
   |
   +---- login, MFA ----> Microsoft Entra ID
```

- The Pi sits behind CGNAT and opens the tunnel itself, nothing is forwarded on the home router.
- The VPS only reads the hostname from the TLS handshake (SNI) and forwards known names. TLS ends on the Pi.
- `cloud.<domain>` is Nextcloud, `portal.<domain>` the start page with tiles per app, `<domain>` redirects to the portal.
- Nextcloud logs in through OIDC (`user_oidc`), the portal sits behind oauth2-proxy. Both need an Entra app role, an Entra account alone isn't enough.

## Security

- TLS passthrough: the VPS provider only sees encrypted traffic
- unknown hostnames and plain IP scans are closed on the VPS, rate limits for new connections
- tunnel limited to the two peers (`/32`), no IP forwarding, nftables on both hosts; the Pi accepts only 443 from the VPS
- login only with an Entra app role, the local Nextcloud admin needs TOTP
- Let's Encrypt through DNS-01: the VPS keeps only 443 open, the token on the Pi can only write the challenge record, CAA allows only Let's Encrypt with DNS-01
- SSH with keys only, automatic updates with a nightly reboot

## Stack

- VPS and Pi: Debian 13, nginx, WireGuard, nftables
- Pi: Nextcloud (MariaDB, Redis, PHP-FPM), oauth2-proxy, lego
- portal: Angular with Angular Material, Spring Boot (Java 21)
- identity provider: Microsoft Entra ID

## Repository

```
configs/   config files per host (vps, pi), installed by the setup scripts
scripts/   deploy.ps1 (runs everything from a Windows PC), setup-vps.sh, setup-pi.sh
portal/    portal frontend and backend
docs/      manual steps and checks, architecture (arc42), decisions (ADRs)
```

## Setup

You need:

- VPS and Pi (or two VMs) with Debian 13, SSH server and a sudo user; the Pi with a second, empty disk for the data
- an Entra ID tenant with app registrations for Nextcloud and the portal, app roles assigned to the users
- a domain with A records for `<domain>`, `cloud` and `portal` pointing to the VPS; for a real certificate also a deSEC account
- a Windows PC with an SSH key, Node.js and Maven (the portal is built there)

The Entra, DNS and deSEC steps can't be scripted, they are in [`docs/Manual_Setup.md`](docs/Manual_Setup.md).

Then:

1. copy `.env.example` to `.env` and fill in domains, admin user and Entra IDs; no secrets go in there
2. run `.\scripts\deploy.ps1 -VpsIp <VPS_IP> -PiIp <PI_IP>` from the repo root
3. answer the prompts: sudo passwords, database and Nextcloud admin password, the Entra client secrets, the deSEC token
4. set up TOTP for the local Nextcloud admin at `https://cloud.<domain>/login?direct=1`

Running `deploy.ps1` again only applies what changed. With `ACME_DOMAIN` left empty, the Pi gets self-signed certificates, which is enough for a test with `.test` names in the hosts file.

## Documentation

- [`docs/Manual_Setup.md`](docs/Manual_Setup.md): manual steps and checks for every part
- [`docs/arc42/architecture.md`](docs/arc42/architecture.md): architecture
- [`docs/adr/`](docs/adr/): decisions with their alternatives
- [`portal/README.md`](portal/README.md): local development of the portal
## Status

Tested end to end in VMs and with the real domain; the real Pi is next. Still missing for regular operation: CI (linting, security scans) and monitoring (certificate expiry, availability, vulnerable packages).