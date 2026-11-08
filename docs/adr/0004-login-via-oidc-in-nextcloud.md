# 4. Login via OIDC in Nextcloud

Date: 2026-11-08

## Status

Accepted

## Context

Login should go through an external identity provider, not through self-built authentication. Only explicitly allowed users should get in, with MFA.

Because of [3. Use TLS passthrough on the VPS](0003-use-tls-passthrough-on-the-vps.md) the VPS can't check tokens, so the login has to happen on the Pi.

## Decision

Nextcloud authenticates users via OpenID Connect with the `user_oidc` app. The identity provider decides who can sign in.

## Consequences

- no own login code
- Nextcloud binds user IDs to the provider, switching providers later means migrating users (rough estimate 1 to 3 hours)
- the local Nextcloud admin stays reachable via `?direct=1` on the login URL
- whatever the provider lets through gets in, so provider-side exceptions (see [6](0006-use-microsoft-entra-id-via-azure-for-students.md)) have to be handled in Nextcloud
