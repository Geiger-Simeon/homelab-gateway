# 18. Validate the Entra ID token in the portal API

Date: 2026-11-18

## Status

Accepted, amends the auth part of [ADR 0017](0017-build-the-portal-with-angular-and-spring-boot.md).

## Context

[ADR 0017](0017-build-the-portal-with-angular-and-spring-boot.md) has oauth2-proxy sign the user in and nginx forward the user and roles to the portal API as `X-Auth-Request-*` headers. The API trusts the headers blindly and only stays safe because it listens on `127.0.0.1` and nginx overwrites the headers on every request. One wrong nginx rule, one direct connection from another process on the Pi, or a future attempt to reach the API from a container, and anyone can claim `portal-admin`. The loopback bind is a mitigation, not a check.

Options:

- shared secret header between nginx and the API: closes the "another process on the Pi" gap but still trusts whatever header value shows up; the secret has to be rotated and stored
- pre-authentication over the existing headers with Spring's `RequestHeaderAuthenticationFilter`: same trust model as today, just wrapped in Spring Security
- Spring as an OAuth2 resource server that validates the Entra ID token forwarded by oauth2-proxy: the API cryptographically checks who the request is for, no shared state with nginx
- Spring as an OAuth2 client doing its own login against Entra: works, but oauth2-proxy already protects the other apps on the Pi (Nextcloud, future ones) and doing a second login in Spring means two sessions for the same user

## Decision

Spring Security on the API as an OAuth2 resource server. oauth2-proxy is configured with `set_authorization_header = true`, nginx reads `Authorization` from the auth_request response and forwards it as the `Authorization: Bearer <token>` header to the API. Spring verifies the signature against the Microsoft JWKS and checks issuer and audience.

The token used is the **ID token**, not an access token. The portal doesn't register its own "Expose an API" scope, so the access token oauth2-proxy gets from Entra is the one for Microsoft Graph: a different issuer (`sts.windows.net`), `aud` is Graph, no `roles` claim. The ID token has `aud = <client-id>` and the app roles in the `roles` claim, which is what the API needs.

Validation:

- issuer must equal `https://login.microsoftonline.com/<tenant-id>/v2.0`
- `aud` must contain the portal's client id
- `exp` / `nbf` via the default Nimbus validators
- signature against the JWKS at `/discovery/v2.0/keys`, fetched lazily and cached

User and roles come from the validated token: `name`, `preferred_username`, `email`, `roles`. `/api/system` is restricted to `ROLE_portal-admin` by the filter chain, every other `/api/**` endpoint needs an authenticated token.

For local development the `dev` profile replaces both the bearer token resolver and the decoder with stubs that always return the dev user. The filter chain and roles converter are the same, so dev and prod go through the same code path.

## Consequences

- the API doesn't trust nginx anymore; another process on the Pi can't call `/api` without a valid token for the portal
- the JVM needs outbound HTTPS to `login.microsoftonline.com` to fetch the JWKS; without it the first request after a restart fails
- Entra ID tokens expire after about one hour, so the oauth2-proxy session has to refresh the token in the background: `cookie_refresh < token lifetime` (verify when setting up the proxy)
- JWTs with the full `roles` claim can get large; `proxy_buffer_size` in nginx may need to be raised, depending on how many roles a user has
- the loopback bind and the nginx overwrite stay in place as a second layer, they cost nothing
- the frontend doesn't change, the browser never sees a token; oauth2-proxy still handles login and session cookie
