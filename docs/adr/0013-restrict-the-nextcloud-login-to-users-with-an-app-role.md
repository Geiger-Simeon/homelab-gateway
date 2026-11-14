# 13. Restrict the Nextcloud login to users with an app role

Date: 2026-11-14

## Status

Accepted

## Context

"Assignment required" in Entra blocks unassigned users, but Global Administrators bypass it by design (see [6](0006-use-microsoft-entra-id-via-azure-for-students.md)). Without a check in Nextcloud the Global Admin would get a Nextcloud account on the first login.

Options:

- Conditional Access: needs Entra ID P1, not available
- assign an Entra group to the app and check the group: group-based assignment also needs P1
- disable auto provisioning and create users by hand: works, but every new user needs a manual step in Nextcloud with the user ID known in advance
- app role in the app registration, assigned per user (works in Entra ID Free), sent in the `roles` claim; `user_oidc` turns roles into Nextcloud groups and can reject users without a whitelisted group

## Decision

The app registration defines the app role `nc-user`. Users are assigned with this role instead of "Default Access". `user_oidc` maps the `roles` claim to groups and only lets users in whose groups match `/^nc-/`:

`--group-provisioning=1 --mapping-groups=roles --group-whitelist-regex='/^nc-/' --group-restrict-login-to-whitelist=1`

## Consequences

- the Global Admin passes Entra but is rejected by Nextcloud, no account is created
- access is managed in one place: role assignment in Entra
- more roles (e.g. read-only guests) become Nextcloud groups that shares can use later
- groups are only updated at an OIDC login; clients with an app password don't log in again, so removing a role in Entra doesn't remove access until the app passwords are revoked (`occ user:auth-tokens:delete`)
- Entra signs in silently with the Microsoft account active in the browser; if that's the Global Admin, Nextcloud shows "access forbidden" without asking for a password
- the role has to be assigned before the restriction is turned on, otherwise every user is locked out
