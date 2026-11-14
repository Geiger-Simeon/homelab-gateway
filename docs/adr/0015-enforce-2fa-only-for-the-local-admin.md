# 15. Enforce 2FA only for the local admin

Date: 2026-11-14

## Status

Accepted

## Context

All regular users log in through Entra, which handles MFA. The local admin `ncadmin` is the break-glass account (ADR 0013) and the only login with a Nextcloud password, reachable via `/login?direct=1` from the internet.

Options:

- enforce 2FA for everyone: Entra users would get TOTP on top of Entra MFA; how `user_oidc` behaves with enforced Nextcloud 2FA wasn't tested
- enforce 2FA for the `admin` group: it's unclear whether OIDC users end up there later, same issue
- hide `/login?direct=1`: the local admin has to stay reachable when Entra is down or misconfigured
- enforce 2FA for a dedicated group that only contains local accounts

## Decision

TOTP (`twofactor_totp`) with backup codes for `ncadmin`. 2FA is enforced only for the group `local-2fa` with `ncadmin` as its member.

## Consequences

- a leaked admin password alone is not enough
- Entra users get no second prompt in Nextcloud
- every new local account has to be added to `local-2fa`
- backup codes are stored in the password manager; without them and without the TOTP device: `occ twofactorauth:disable ncadmin totp` on the Pi
