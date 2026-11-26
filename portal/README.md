# Portal

Start page on the Pi: shows the services a user has an Entra app role for, plus a short system overview for the admin.
Decision and trade-offs: [ADR 0017](../docs/adr/0017-build-the-portal-with-angular-and-spring-boot.md).

| Part       | Stack                                   | Runs as                                      |
|------------|-----------------------------------------|----------------------------------------------|
| `frontend` | Angular 22, Angular Material (M3)       | static files, served by nginx                |
| `backend`  | Spring Boot 4.1, Java 21                | systemd service on `127.0.0.1:8081`          |

## Local development

Backend with a fake user (profile `dev`, outside this profile the app requires a real Entra ID token):

```bash
cd portal/backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Frontend, proxies `/api` to the backend:

```bash
cd portal/frontend
npm ci
npm start
```

Then open http://localhost:4200.

## Tests and build

```bash
cd portal/backend && mvn test
cd portal/frontend && npm test -- --watch=false
```

```bash
cd portal/frontend && npm run build    # -> dist/portal/browser
cd portal/backend && mvn package       # -> target/homelab-portal-<version>.jar
```

The production build doesn't inline critical CSS (`inlineCritical: false` in `angular.json`). With inlining Angular needs an inline script to switch the full stylesheet on, and the CSP on the Pi ([`portal-headers.conf`](../configs/pi/etc/nginx/snippets/portal-headers.conf)) blocks inline scripts, so the page would only get the critical part of the styles.

## API

| Endpoint           | Who             | Returns                                                       |
|--------------------|-----------------|---------------------------------------------------------------|
| `GET /api/me`      | signed-in users | name, email, `admin` flag                                     |
| `GET /api/services`| signed-in users | services the user has the role for, with status               |
| `GET /api/system`  | `portal-admin`  | parsed system status file plus `stale` flag, `403` for everyone else, `404` if the file is missing or invalid |

Without a valid token every endpoint answers `401`. Spring Security guards all of `/api/**`, so new endpoints are covered automatically.

The frontend reloads the page once on a `401` (expired oauth2-proxy session). A second `401` within 30 s shows the error page instead, since that means the API didn't get a valid token (nginx/proxy config or expired token from oauth2-proxy), not just an expired session.

## Auth

The backend has no login of its own. It validates the Entra ID token that oauth2-proxy forwards. Decision and trade-offs: [ADR 0018](../docs/adr/0018-validate-the-entra-id-token-in-the-portal-api.md), oauth2-proxy itself: [ADR 0019](../docs/adr/0019-put-oauth2-proxy-in-front-of-the-portal.md).

Flow:

1. the browser hits nginx, oauth2-proxy sends it to Entra and sets a session cookie
2. `set_authorization_header = true` in oauth2-proxy makes the auth_request response carry the Entra ID token as `Authorization`
3. nginx reads it with `auth_request_set` and forwards it as the `Authorization: Bearer <token>` header to the API
4. Spring verifies the signature against the Microsoft JWKS, checks issuer, `aud` (the client id), expiry; the `roles` claim becomes Spring `ROLE_*` authorities

Configuration:

| Setting                   | Comes from                                       |
|---------------------------|--------------------------------------------------|
| `ENTRA_TENANT_ID`         | tenant id (GUID) of the Entra directory           |
| `ENTRA_CLIENT_ID`         | application (client) id of the Entra app "portal"; on the Pi from `PORTAL_CLIENT_ID` in `.env` |

Both are required outside the `dev` profile, the app refuses to start without them.

oauth2-proxy, full config in [`configs/pi/etc/oauth2-proxy/oauth2-proxy.cfg`](../configs/pi/etc/oauth2-proxy/oauth2-proxy.cfg):

```toml
set_authorization_header = true
# refresh token for cookie_refresh
scope = "openid profile email offline_access"
# token lifetime is about 1 h, refresh in the background so the API never sees an expired one
cookie_refresh = "30m"
# app roles ride in the `roles` claim, not `groups`
oidc_groups_claim = "roles"
```

nginx: [`configs/pi/etc/nginx/sites-available/portal.conf`](../configs/pi/etc/nginx/sites-available/portal.conf). `/api/` gets the token from `auth_request_set` and answers `401` without a session (the frontend reloads), every other path redirects to `/oauth2/start`. The Abmelden button goes to `/logout`, nginx chains the oauth2-proxy sign out and the Entra logout ([ADR 0019](../docs/adr/0019-put-oauth2-proxy-in-front-of-the-portal.md)); `/oauth2/sign_out` alone would log straight back in through the Entra session.

Loopback bind (`server.address = 127.0.0.1`) stays as a second layer, but the real check is the signature.

## Deployment

- `scripts/deploy.ps1` builds frontend and backend on the PC (`-SkipPortalBuild` takes the existing output), puts both into `portal-dist/` (git-ignored) and copies that to the Pi only
- `scripts/setup-pi.sh` installs the jar to `/opt/homelab-portal/homelab-portal.jar` and the static files to `/var/www/portal`, systemd unit `homelab-portal` as user `homelab-portal`
- overrides for the packaged `application.yml` in `/etc/homelab-portal/application.yml`, tenant and client id in `/etc/homelab-portal/portal.env`; the system status file lives in `/var/lib/homelab-portal/`
- logs: `journalctl -u homelab-portal`
- setup and checks: phase 8 in [`docs/Manual_Setup.md`](../docs/Manual_Setup.md)

## Services

Configured in `backend/src/main/resources/application.yml` under `portal.services`, keyed by service id (the order is the tile order). A profile only lists what it changes, e.g. `application-dev.yml` just sets `status-override` for nextcloud.

- `role`: Entra app role needed to see the tile, no role means hidden for everyone
- `planned: true`: shown as "In Planung", no link
- `health-url`: checked by the backend on demand, any 2xx counts as online. The first check per URL blocks, after that the cached value is served and refreshed in the background once it's older than `health-cache-ttl` (30 s)
- `status-override`: fixed status, wins over `health-url`
- neither: shown as "Status unbekannt"

The health URL must not go through oauth2-proxy (a redirect or `401` counts as offline), and the JVM has to trust its certificate (Let's Encrypt works out of the box, `setup-pi.sh` adds the self-signed test certificate to the truststore). Status changes and their reason are logged as `WARN`, e.g. `journalctl -u homelab-portal | grep "health check"`.

`icon` is one of the icons in `frontend/src/app/core/icons.ts`, `color` one of `blue`, `purple`, `orange`, `teal`.

## System status file

Written by a timer on the Pi (not built yet), read by the backend from `portal.system-status-file`:

```json
{
  "generatedAt": "2026-11-15T13:55:00Z",
  "checks": [
    { "id": "tunnel", "label": "WireGuard-Tunnel", "value": "Verbunden", "state": "OK" }
  ]
}
```

- all fields are required, `state` is `OK`, `WARN`, `FAIL` or `INFO`; anything else and the backend ignores the file (logged as `WARN`)
- older than `portal.system-status-max-age` (15 min) and the panel marks the values as outdated
- the timer should write a temp file and `mv` it into place, so the backend never reads a half-written file
- `id` picks the icon (`server`, `tunnel`, `backup`, `updates`), unknown ids get a default icon
- example: `backend/dev/system-status.json`
