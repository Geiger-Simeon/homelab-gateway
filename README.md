# homelab-gateway
Self-hosted VPS gateway providing authenticated access to a home server through a WireGuard tunnel, with OIDC login via Microsoft Entra ID

## Setup

- `scripts/deploy.ps1` sets up both hosts from the PC with `scripts/setup-vps.sh` and `scripts/setup-pi.sh`, see `docs/Manual_Setup.md` (phase 7)
- architecture: `docs/arc42/architecture.md`, decisions: `docs/adr/`
