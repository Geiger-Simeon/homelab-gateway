# Portal

Start page on the Pi: shows the services a user has an Entra app role for, plus a short system overview for the admin.
Decision and trade-offs: [ADR 0017](../docs/adr/0017-build-the-portal-with-angular-and-spring-boot.md).

| Part       | Stack                                   | Runs as                                      |
|------------|-----------------------------------------|----------------------------------------------|
| `frontend` | Angular 22, Angular Material (M3)       | static files, served by nginx                |
| `backend`  | Spring Boot 4.1, Java 21                | systemd service on `127.0.0.1:8081`          |

## Local development

Backend with a fake user (profile `dev`, the app refuses to start with `dev-user` in any other profile):

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

## API

| Endpoint           | Who             | Returns                                                       |
|--------------------|-----------------|---------------------------------------------------------------|
| `GET /api/me`      | signed-in users | name, email, `admin` flag                                     |
| `GET /api/services`| signed-in users | services the user has the role for, with status               |
| `GET /api/system`  | `portal-admin`  | parsed system status file plus `stale` flag, `403` for everyone else, `404` if the file is missing or invalid |

Without the auth headers every endpoint answers `401`. The check sits in `AuthInterceptor` for all of `/api/**`, so new endpoints are covered automatically.

The frontend reloads the page once on a `401` (expired oauth2-proxy session). A second `401` within 30 s shows the error page instead, since that means the headers are missing, not the session.

## Auth headers

The backend has no login of its own. It trusts these headers (names configurable under `portal.auth`):

| Header                              | Content                     |
|-------------------------------------|-----------------------------|
| `X-Auth-Request-Preferred-Username` | login name, used for the greeting |
| `X-Auth-Request-Email`              | email                       |
| `X-Auth-Request-Groups`             | app roles, comma separated  |

Entra puts app roles into the `roles` claim, groups into `groups` (as object IDs). oauth2-proxy fills the groups header from `groups` by default, so it has to be pointed at `roles` (`oidc_groups_claim = "roles"`). Not verified yet for the `entra-id` provider, check when setting up the proxy.

That is only safe if:

- the backend listens on `127.0.0.1` only (`server.address` in `application.yml`)
- nginx sets these headers on every request from the oauth2-proxy response, so headers sent by a client are overwritten

Sketch for nginx, checked against the oauth2-proxy docs when the proxy is set up:

```nginx
location /api/ {
    auth_request /oauth2/auth;
    auth_request_set $hl_user   $upstream_http_x_auth_request_preferred_username;
    auth_request_set $hl_email  $upstream_http_x_auth_request_email;
    auth_request_set $hl_groups $upstream_http_x_auth_request_groups;
    proxy_set_header X-Auth-Request-Preferred-Username $hl_user;
    proxy_set_header X-Auth-Request-Email              $hl_email;
    proxy_set_header X-Auth-Request-Groups             $hl_groups;
    proxy_pass http://127.0.0.1:8081;
}
```

## Services

Configured in `backend/src/main/resources/application.yml` under `portal.services`, keyed by service id (the order is the tile order). A profile only lists what it changes, e.g. `application-dev.yml` just sets `status-override` for nextcloud.

- `role`: Entra app role needed to see the tile, no role means hidden for everyone
- `planned: true`: shown as "In Planung", no link
- `health-url`: checked by the backend on demand, any 2xx counts as online. The first check per URL blocks, after that the cached value is served and refreshed in the background once it's older than `health-cache-ttl` (30 s)
- `status-override`: fixed status, wins over `health-url`
- neither: shown as "Status unbekannt"

The health URL must not go through oauth2-proxy (a redirect or `401` counts as offline), and the JVM has to trust its certificate. Status changes and their reason are logged as `WARN`, e.g. `journalctl -u homelab-portal | grep "health check"`.

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
