# 19. Put oauth2-proxy in front of the portal

Date: 2026-11-18

## Status

Accepted

## Context

The portal (ADR 0017) has no login of its own, its API validates an Entra ID token (ADR 0018). Something on the Pi has to sign the user in, keep the session and hand the token to nginx. Later own apps (recipes, calories) need the same.

Nextcloud is not part of this: it logs in through `user_oidc` (ADR 0004) and its desktop and mobile clients talk WebDAV with app passwords, a proxy login in front would break them. ADR 0018 lists Nextcloud among the apps behind oauth2-proxy, that was wrong.

Options for the login:

- oauth2-proxy in auth_request mode behind nginx
- Spring Security as OAuth2 client in the portal: login code per app, the next app needs it again
- a full identity proxy (Authelia, Pomerium, Keycloak): more features than needed, one more service to run and update

## Decision

- oauth2-proxy (`entra-id` provider) on `127.0.0.1:4180`, nginx checks every request with `auth_request`
- own app registration `portal` with app roles `portal-admin` and `app-*`, separate secret and redirect URI from Nextcloud
- only users with one of these roles get in (`allowed_groups` on the `roles` claim); this also stops global admins, who skip the Entra assignment
- scopes `openid profile email offline_access`, the refresh token keeps the ID token fresh (`cookie_refresh` 30m)
- email taken from `preferred_username`, cloud-only users may have no `email` claim
- `prompt = select_account`: Entra shows the account picker on every login, otherwise it signs in silently with whatever account the browser already uses, and an admin account without a portal role ends in `403`
- sessions in the existing Redis (database 1), the browser only gets a ticket cookie `__Host-hl_session`
- official release binary, version and sha256 pinned in `setup-pi.sh`, own system user and a hardened systemd unit
- portal on its own hostname `portal.<domain>`, the VPS forwards it like the Nextcloud name
- logout at `/logout`: nginx sends the browser to `/oauth2/sign_out` with `rd` set to the Entra logout endpoint, which returns to the portal (`post_logout_redirect_uri`, registered as a redirect URI). `sign_out` alone only drops the proxy session, the next request would sign in again silently through the Entra session, on a shared device that's worse than no button

## Consequences

- one login for the portal and the later apps, every app only checks the token
- oauth2-proxy isn't covered by unattended-upgrades, new releases have to be watched and the pinned version bumped by hand
- sessions depend on Redis; if they get lost (e.g. Redis data wiped), users log in again, silently while the Entra session lasts
- a new app on another hostname needs a cookie on the parent domain instead of `__Host-` and its own redirect URI
- the Entra client secret of `portal` expires like the Nextcloud one and needs the same reminder
- logout ends the Entra session in that browser, so other Entra logins there (the Nextcloud login) need the password again; an already open Nextcloud session keeps running, it has its own
- `whitelist_domains` includes `login.microsoftonline.com`, so `rd` after login may also point there; only Microsoft's own pages, no open redirect to arbitrary hosts
