# 12. Map the Nextcloud user ID to the Entra object ID

Date: 2026-11-14

## Status

Accepted

## Context

`user_oidc` creates a Nextcloud user on the first login. By default the user ID is a hash of the provider ID and the `sub` claim. The user ID is also the name of the user's folder in the data directory, so it matters for the data migration to the real Pi.

In Entra the `sub` claim is a pairwise identifier, unique per application ID. Registering the app again (new client ID) gives the same person a different `sub` and therefore a new Nextcloud user. The `oid` claim is the object ID of the user in the tenant: immutable, never reused, the same for every app in the tenant. It needs the `profile` scope, which `user_oidc` requests by default.

Readable values like `preferred_username` or the email are mutable, Microsoft advises against using them to identify users. A UPN change (planned once a custom domain is added) would create a new user.

Source: Microsoft Learn, "ID token claims reference".

## Decision

The provider uses `--unique-uid=0 --mapping-uid=oid`. The Nextcloud user ID is the Entra object ID, without hashing.

## Consequences

- the user ID is known before the first login (Entra > Users > object ID), files can be prepared for it during the migration
- a new app registration in the same tenant keeps the users
- renaming users or moving them to a custom domain doesn't touch the Nextcloud account
- user IDs are GUIDs, not readable in `occ` output or the data directory
- without hashing, a second provider could in theory deliver the same ID; there is only one provider, and object IDs are GUIDs
- a new tenant or another identity provider still means new user IDs (see [4](0004-login-via-oidc-in-nextcloud.md))
- has to be set before the first login, changing it later creates new users
