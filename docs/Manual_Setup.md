# Manual setup

Steps that can't be scripted (or not reasonably), plus how to verify each phase.
Shell commands live in `NOTES.md` and in `scripts/`.

## Test environment (VMware)

- VMware Workstation Pro, network mode bridged
- Virtual Network Editor: VMnet0 bound to the physical adapter explicitly, not "Automatic"
- both VMs: Debian 13 netinst, only "SSH server" and "standard system utilities"
- Pi-VM: two virtual disks, 20 GB system + 30 GB data
- TODO: fixed DHCP lease in the router for both VMs

| VM    | LAN IP          | Tunnel IP |
|-------|-----------------|-----------|
| vps   | 192.168.178.38  | 10.99.0.1 |
| Pi-VM | 192.168.178.169 | 10.99.0.2 |

### Snapshots

| Name           | State                                                         |
|----------------|---------------------------------------------------------------|
| `clean`        | fresh install, updated, history + etckeeper                   |
| `vor-wireguard`| before phase 2                                                |
| `wireguard-ok` | tunnel working and tested                                     |
| `firewall-ok`  | nftables active and tested (Pi-VM only, missed on the vps)    |
| `passthrough-ok` | nginx stream on the vps tested (vps only)                   |
| `hardening-ok` | SSH, rate limits, automatic updates, Nextcloud 2FA and share settings tested (both VMs) |

## Phase 1: Entra ID

Done in the Entra admin center (`entra.microsoft.com`).

1. tenant: sign up for Azure for Students with the private Microsoft account (not the university account), check that the active directory is the new one and not "Microsoft Services"
2. admin account: 2-step verification on the private Microsoft account
3. security defaults: Entra ID > Overview > Properties > manage security defaults > enabled
4. users (Entra ID > Users > New user > Create new user):
    - `<USER>@<TENANT>.onmicrosoft.com` for the Nextcloud login, no roles
    - `test-unassigned@<TENANT>.onmicrosoft.com` for negative tests, keep it
    - first sign in for both at `myaccount.microsoft.com` in a private window: new password, MFA
5. app registration (Entra ID > App registrations > New registration):
    - name `nextcloud`, single tenant, no redirect URI yet
    - Certificates & secrets: new client secret, copy the value to the password manager, expires `<DATE>`
    - API permissions: Microsoft Graph delegated `openid`, `profile`, `email` (plus default `User.Read`), grant admin consent
6. enterprise application (Entra ID > Enterprise applications > nextcloud):
    - Properties: enabled for sign in yes, assignment required yes, visible to users no
    - Users and groups: assign only `<USER>@`

Values (not secret, but placeholders here):

- tenant ID: `<TENANT_ID>`
- client ID: `<CLIENT_ID>`
- discovery URI: `https://login.microsoftonline.com/<TENANT_ID>/v2.0/.well-known/openid-configuration`

### Verify

Temporarily add redirect URI `http://localhost` (Authentication > Add a platform > Web), then open in a private window:

```
https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/authorize?client_id=<CLIENT_ID>&response_type=code&redirect_uri=http%3A%2F%2Flocalhost&scope=openid%20profile%20email&response_mode=query
```

- the page always fails (nothing runs on localhost), the result is in the address bar
- `?code=...` = login ok, `?error=...` or a Microsoft error page = denied
- expected: `<USER>@` ok, `test-unassigned@` denied (AADSTS50105), Global Admin ok (bypass, see `docs/arc42/architecture.md` section 11)
- remove the `http://localhost` redirect URI afterwards

## Phase 2: WireGuard

Exchange public keys between the VMs by hand (they're not secret).

### Verify

```bash
sudo wg show                      # latest handshake + transfer on both sides
ping -c 3 10.99.0.1               # from Pi-VM, blocked by the vps firewall since phase 3
ping -c 3 10.99.0.2               # from vps
cat /proc/sys/net/ipv4/ip_forward # must be 0 on both
```

- reboot vps: handshake comes back within ~25 s without touching the Pi
- reboot Pi-VM: tunnel comes up on its own
- logs: `sudo journalctl -u wg-quick@wg-gw`

Notes:

- `active (exited)` for `wg-quick@wg-gw` is normal
- `sysctl` is in `/usr/sbin`, not in the user PATH on Debian
- etckeeper may have picked up the WireGuard files in a "daily autocommit", check with `sudo git -C /etc ls-files wireguard`

## Phase 3: Firewall

Rules: `configs/vps/etc/nftables.conf`, `configs/pi/etc/nftables.conf`, commands in `NOTES.md`.

- before loading on the vps: `WG_PORT` in the file has to match `sudo wg show wg-gw listen-port`
- locking yourself out is harmless in the VMs (the VMware console works without network); on the real VPS keep the provider's web console ready

### Verify

Listener on the Pi-VM in a separate terminal: `sudo nc -lk 443` (package `netcat-openbsd`, needed on both VMs).

| From  | Command                                        | Expected                   |
|-------|------------------------------------------------|----------------------------|
| PC    | new SSH connection to both VMs                 | works                      |
| Pi-VM | `sudo wg show`                                 | recent handshake           |
| vps   | `ping -c 3 10.99.0.2`                          | works                      |
| Pi-VM | `ping -c 3 -W 2 10.99.0.1`                     | 100 % packet loss          |
| vps   | `nc -zv -w 3 10.99.0.2 443`                    | succeeded                  |
| vps   | `nc -zv -w 3 10.99.0.2 22`                     | timeout                    |
| Pi-VM | `nc -zv -w 3 10.99.0.1 22`                     | timeout                    |
| PC    | `Test-NetConnection 192.168.178.169 -Port 443` | `TcpTestSucceeded : False` |

- the two timeouts matter most: apart from vps to Pi on 443, the tunnel can't be used to open connections in either direction
- SSH sessions opened before the rules were loaded keep working (`established`), only a new connection tells you something
- reboot both VMs: `sudo nft list ruleset` shows table `inet gateway`, tunnel comes back up

## Phase 4: TLS passthrough

Config: `configs/vps/etc/nginx/stream.d/gateway.conf`, commands in `NOTES.md`. Nothing manual on the Pi-VM.

### Verify

Listener on the Pi-VM, restart it before each test: `sudo nc -l 443 | head -n 1`

| From | Command | Expected |
|------|---------|----------|
| PC   | `curl.exe -vk --max-time 5 --resolve cloud.homelab.test:443:192.168.178.38 https://cloud.homelab.test/` | Pi-VM shows `PROXY TCP4 <PC IP> 192.168.178.38 <port> 443`, curl times out |
| PC   | `curl.exe -vk --max-time 5 https://192.168.178.38/` | curl fails at once, nothing on the Pi-VM |
| PC   | `curl.exe -vk --max-time 5 --resolve other.test:443:192.168.178.38 https://other.test/` | curl fails at once, nothing on the Pi-VM |
| vps  | `sudo tail -5 /var/log/nginx/error.log` | `connect() failed (111)` to `127.0.0.1:1` for the two negative tests |

- the PC IP in the PROXY line shows the Pi gets the real client, not `10.99.0.1`
- reboot vps: nginx comes back on its own, positive test works again

## Phase 5: Nextcloud login via Entra

Commands (`user_oidc` install, provider, group settings) in `NOTES.md`. Order matters: provider first, then the Entra steps below, then the group restriction.

1. redirect URI: copy it from Nextcloud (Administration settings > OpenID Connect), then in Entra: App registrations > nextcloud > Authentication > Add a platform > Web, paste it; leave access tokens and ID tokens (implicit/hybrid) unchecked, no front-channel logout URL
2. app role: App registrations > nextcloud > App roles > Create app role
    - display name `Nextcloud User`, allowed member types Users/Groups, value `nc-user`, enabled
    - app roles instead of groups: assigning groups to an app needs Entra ID P1
3. role assignment: Enterprise applications > nextcloud > Users and groups > edit `<USER>@`, select `Nextcloud User` instead of "Default Access"; `test-unassigned@` stays unassigned

### Verify

Each login in a new private window, so no other Microsoft session gets picked up.

| Who | Expected |
|-----|----------|
| `<USER>@` | logged in; `occ user:list` shows the object ID, `occ group:list` shows it in `nc-user` |
| `test-unassigned@` | Entra error page (AADSTS50105), no new user |
| Global Admin | passes Entra, Nextcloud denies ("Zugriff verboten"), no new user |
| anyone, `/login` | redirect to Entra without a Nextcloud login form |
| local admin, `/login?direct=1` | local login form works |

Desktop client login and WebDAV are tested without a client via `curl.exe`, see `NOTES.md`. The real desktop and mobile clients follow after go-live.

## Phase 6: Hardening

Configs and commands in `NOTES.md`. Manual parts:

1. SSH key on the PC (PowerShell), before password login is turned off:
    ```powershell
    ssh-keygen -t ed25519 -C "<ADMIN_USER>@<PC>"
    Get-Service ssh-agent | Set-Service -StartupType Automatic
    Start-Service ssh-agent
    ssh-add $env:USERPROFILE\.ssh\id_ed25519
    type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <USER>@<VPS_IP> "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
    type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <USER>@<PI_IP> "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
    ```
    - passphrase in the password manager, entry named after the key (`SSH-Key <PC> (id_ed25519)`) with fingerprint (`ssh-keygen -lf $env:USERPROFILE\.ssh\id_ed25519.pub`) and the hosts it's on
    - the agent keeps the key, the passphrase is only needed for `ssh-add`
2. TOTP for `ncadmin`: private window, `/login?direct=1`, Personal settings > Security > enable TOTP, scan the QR code; generate backup codes on the same page and store them with the `ncadmin` entry in the password manager. Only then enforce 2FA (`NOTES.md`)
3. sharing: Administration settings > Sharing shows password and expiry date enforced, public upload off
4. client secret: App registrations > nextcloud > Certificates & secrets, expiry date into the password manager, calendar reminder 4 weeks before
5. revoking access for a person: remove the assignment in Entra (Enterprise applications > nextcloud > Users and groups), then disable the user or delete the app passwords in Nextcloud (`NOTES.md`)

### Verify

| From | Check | Expected |
|------|-------|----------|
| PC | `ssh -o PubkeyAuthentication=no <ADMIN_USER>@<IP>` (both VMs) | `Permission denied (publickey)` |
| PC | `ssh <ADMIN_USER>@<IP> "echo ok"` (both VMs) | `ok`, no password prompt |
| PC | 20 fast TCP connects to 22 on the vps (`NOTES.md`) | 1 to 10 succeed, then dropped for about a minute |
| Pi-VM | 200 parallel HTTPS connections to the vps (`NOTES.md`) | 30 succeed, nftables counter and `limiting connections` in the nginx log go up |
| both | `sudo unattended-upgrade --dry-run --debug 2>&1 \| grep -i "allowed origins"` | Debian and Debian-Security origins |
| both | `systemctl list-timers 'apt-daily*'` | both timers active |
| browser | `ncadmin` via `/login?direct=1` | TOTP code asked after the password |
| browser | `<USER>@` via Entra | no TOTP in Nextcloud |
| browser | create a share link as `<USER>@` | password required, expiry date 7 days, no upload option |
| Pi-VM | `occ security:bruteforce:attempts <PC IP>` after 3 wrong passwords | attempts > 0, delay > 0; `10.99.0.1` stays at 0 |
| PC | WebDAV with a revoked app password | 401 |
| browser | Nextcloud with `<USER>@` | works normally, no delay |

## Phase 7: Setup scripts

`scripts/deploy.ps1` runs the whole setup from the PC: it installs the SSH key, copies the files, runs `setup-vps.sh`, `setup-pi.sh` and `setup-vps.sh` again, and passes the WireGuard public keys between the hosts.

Before the first run:

- Entra is set up (phase 1), the app role is assigned to the users (phase 5); the scripts don't touch Entra
- both hosts: Debian 13 with "SSH server", user `ADMIN_USER` with sudo (root password left empty in the installer)
- Pi: a second, empty disk for the data; `setup-pi.sh` finds it, asks once and formats it (only a disk without partitions and filesystem)
- `.env` in the repo root, copied from `.env.example`, without passwords; only the upper part needs values
- SSH key on the PC (phase 6)

Run (PowerShell, repo root):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy.ps1 -VpsIp <VPS_IP> -PiIp <PI_IP>
```

Prompts, in this order:

1. password of `ADMIN_USER` once per host, only while no key is installed
2. sudo password for the first vps run
3. sudo password for the Pi, `y` to format the data disk, new database password, new local admin password, Entra client secret (input is visible, pasting with a right click works in the PowerShell window)
4. sudo password for the second vps run

Afterwards by hand: the PC's hosts entry for the vps IP, TOTP for the local admin (`/login?direct=1`).

The scripts also run on their own (`sudo bash ~/homelab-gateway/scripts/setup-*.sh`), then the keys and `VPS_ENDPOINT` come from `.env`.

### Verify

| Where | Check | Expected |
|-------|-------|----------|
| vps, end of the second run | output | `active` three times, a handshake timestamp other than `0`, `permitrootlogin no`, `passwordauthentication no`, `allowusers <ADMIN_USER>`, `maxauthtries 3` |
| Pi, end of the run | output | `active` six times, `installed: true`, `maintenance: false`, JSON from `status.php` with `"installed":true` |
| PC | run `deploy.ps1` again | no password for the hosts, no `updated ...` lines, no secret prompts, only sudo passwords |
| PC | `curl.exe -sk https://<NC_DOMAIN>/status.php` (hosts entry on the vps IP) | JSON with `"installed":true` |
| browser | login tests from phase 5 and 6 | same results as on the manually built VMs |

## Phase 8: Portal and oauth2-proxy

The portal (`portal.<domain>`) sits behind oauth2-proxy with its own Entra app registration; Nextcloud keeps its `user_oidc` login and is not behind the proxy ([ADR 0019](adr/0019-put-oauth2-proxy-in-front-of-the-portal.md)). Configs in `configs/pi/etc/oauth2-proxy/`, `configs/pi/etc/nginx/`, `configs/pi/etc/homelab-portal/`, `configs/pi/etc/systemd/system/`; installed by `setup-pi.sh`, the build comes from `deploy.ps1`.

Entra (entra.microsoft.com):

1. app registration: App registrations > New registration
    - name `portal`, single tenant
    - redirect URI: platform Web, `https://portal.<domain>/oauth2/callback`; access tokens and ID tokens (implicit/hybrid) unchecked
    - Authentication > Web > Add URI: `https://portal.<domain>/` (with the trailing slash), Entra only returns there after logout if it's registered
    - Certificates & secrets: new client secret, value into the password manager, expiry date `<DATE>` with a calendar reminder 4 weeks before (like the Nextcloud one)
    - API permissions: Microsoft Graph delegated `openid`, `profile`, `email`, `offline_access` (plus default `User.Read`), grant admin consent
2. app roles: App registrations > portal > App roles > Create app role, allowed member types Users/Groups, enabled:

    | Display name     | Value           |
    |------------------|-----------------|
    | Portal Admin     | `portal-admin`  |
    | Nextcloud        | `app-nextcloud` |
    | Jellyfin         | `app-jellyfin`  |
    | Rezepte          | `app-rezepte`   |
    | Kalorien         | `app-kalorien`  |

3. enterprise application: Enterprise applications > portal
    - Properties: enabled for sign in yes, assignment required yes, visible to users no
    - Users and groups: assign `<USER>@` once per role (Entra Free takes one role per assignment), e.g. `portal-admin` and `app-nextcloud`; `test-unassigned@` stays unassigned
4. values for `.env`: `PORTAL_CLIENT_ID="<CLIENT_ID>"` (application ID of `portal`), `PORTAL_DOMAIN`; the tenant ID is the same as for Nextcloud

Then `deploy.ps1` as in phase 7. New on the PC: Node and Maven for the portal build (`-SkipPortalBuild` takes the existing build output). New prompt on the Pi: the client secret of the `portal` app, after the Nextcloud one, only while `/etc/oauth2-proxy/client-secret` doesn't exist. The PC's hosts file needs `portal.<domain>` on the vps IP as well.

Before the first run, in the Pi-VM: `apt-cache policy openjdk-21-jre-headless` shows a candidate (same check later on Raspberry Pi OS).

### Verify

Pi-VM:

| Check | Expected |
|-------|----------|
| end of `setup-pi.sh` | `active` eight times, `oauth2-proxy ping: 200` |
| `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/me` | `401`, the API wants a token even from the Pi itself |
| `sudo ss -ltnp \| grep -E ':(4180\|8081)'` | both on `127.0.0.1` only |
| `systemctl status homelab-portal` | `Memory:` well below `MemoryMax` (512M) after some clicks |
| `journalctl -u oauth2-proxy -b` | no Redis or config errors |
| `journalctl -u homelab-portal \| grep "health check"` | no `WARN` for nextcloud (JVM trusts the test certificate) |

PC (hosts entry on the vps IP):

| Command | Expected |
|---------|----------|
| `curl.exe -sk -o NUL -w "%{http_code} %{redirect_url}" https://portal.<domain>/` | `302 .../oauth2/start?rd=/` |
| `curl.exe -sk -o NUL -w "%{http_code} %{redirect_url}" https://portal.<domain>/oauth2/start` | `302 https://login.microsoftonline.com/...` with `code_challenge` |
| `curl.exe -sk -o NUL -w "%{http_code}" https://portal.<domain>/api/me` | `401`, no redirect |
| `curl.exe -sk -o NUL -w "%{http_code}" -H "Authorization: Bearer x" https://portal.<domain>/api/me` | `401`, a client header doesn't get through |
| `curl.exe -sk -o NUL -w "%{http_code}" https://cloud.<domain>/status.php` | `200`, Nextcloud unaffected |

Browser, each login in a new private window:

| Who | Expected |
|-----|----------|
| `<USER>@` with `app-nextcloud` | portal with the Nextcloud tile, status online |
| `<USER>@` with `portal-admin` | system panel visible |
| `<USER>@` with an `app-*` role but not `portal-admin` | no system panel, `/api/system` gives `403` |
| `test-unassigned@` | Entra error page (AADSTS50105) |
| Global Admin | passes Entra, oauth2-proxy answers `403` |
| normal browser already signed in to Microsoft with another account | account picker, choosing `<USER>@` opens the portal |
| anyone, Abmelden button (`/logout`) | Microsoft sign-out page (maybe an account picker), then back to the portal and the Microsoft login asks for the password; no silent login |
| anyone, after logout: `https://cloud.<domain>/` in the same browser, without an open Nextcloud session | Microsoft login asks for the password |
| anyone, DevTools console | no CSP errors |
| anyone, DevTools cookies | only `__Host-hl_session`, `Secure`, `HttpOnly`, `SameSite=Lax`, a short ticket value |

Redis, after a login:

```bash
sudo redis-cli -s /run/redis/redis-server.sock -n 1 --scan    # one key per session
sudo redis-cli -s /run/redis/redis-server.sock -n 0 dbsize    # nextcloud keys, unchanged by the logins
```

Token refresh: keep the portal open longer than an hour (ID token lifetime), then reload. Expected: tiles load without a redirect to Entra, no `401` from `/api/*`, `journalctl -u oauth2-proxy` shows a refreshed session.

## Phase 9: Let's Encrypt certificate

Real domain only; with `.test` names `setup-pi.sh` keeps the self-signed certificates. One wildcard certificate `*.<domain>` through DNS-01. netcup stays the DNS provider, only `acme.<domain>` is delegated to deSEC, and the token on the Pi may only write the challenge record there ([ADR 0020](adr/0020-get-the-certificate-with-dns-01-through-desec.md)).

DNS at netcup (CCP > Domains > the domain > DNS, deploying the zone takes a few minutes):

| Host | Type | Destination |
|------|------|-------------|
| `cloud` | A | VPS IPv4 |
| `portal` | A | VPS IPv4 |
| `acme` | NS | `ns1.desec.io.` |
| `acme` | NS | `ns2.desec.org.` |
| `_acme-challenge` | CNAME | `_acme-challenge.acme.<domain>.` |
| `@` | CAA | `0 issue "letsencrypt.org; validationmethods=dns-01"` |

The CAA record keeps every other CA and every other challenge type out; a compromised VPS sees the traffic for the domain but can't answer DNS-01.

deSEC (desec.io):

1. account, then a new domain `acme.<domain>`; skip the "delegate" and "DNSSEC" steps shown afterwards, the NS records at netcup are the delegation
2. token for the Pi, on any Linux shell with `curl` and `jq` (password via `printf`, a builtin, so it isn't in the process list; doesn't work with `"` or `\` in the password):

```bash
read -r -p 'deSEC email: ' DESEC_EMAIL
read -rs -p 'deSEC password: ' DESEC_PASSWORD; echo
DESEC_ZONE=acme.<domain>
API=https://desec.io/api/v1
ADMIN_TOKEN=$(printf '{"email":"%s","password":"%s"}' "$DESEC_EMAIL" "$DESEC_PASSWORD" \
  | curl -sS -X POST "$API/auth/login/" -H 'Content-Type: application/json' --data @- | jq -r .token)
unset DESEC_PASSWORD
TOKEN_JSON=$(curl -sS -X POST "$API/auth/tokens/" -H "Authorization: Token $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' --data '{"name":"pi acme"}')
TOKEN_ID=$(jq -r .id <<< "$TOKEN_JSON")
DESEC_TOKEN=$(jq -r .token <<< "$TOKEN_JSON")
unset TOKEN_JSON
# default policy first: no write anywhere
curl -sS -X POST "$API/auth/tokens/$TOKEN_ID/policies/rrsets/" -H "Authorization: Token $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' --data '{"domain":null,"subname":null,"type":null}'; echo
curl -sS -X POST "$API/auth/tokens/$TOKEN_ID/policies/rrsets/" -H "Authorization: Token $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"domain\":\"$DESEC_ZONE\",\"subname\":\"_acme-challenge\",\"type\":\"TXT\",\"perm_write\":true}"; echo
curl -sS -X POST "$API/auth/logout/" -H "Authorization: Token $ADMIN_TOKEN" -o /dev/null -w 'logout: %{http_code}\n'
unset ADMIN_TOKEN
echo "$DESEC_TOKEN"    # once, into the password manager
```

3. check that the token can't write anywhere else, expected `403`:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$API/domains/$DESEC_ZONE/rrsets/" \
  -H "Authorization: Token $DESEC_TOKEN" -H 'Content-Type: application/json' \
  --data '{"subname":"test","type":"TXT","ttl":3600,"records":["\"x\""]}'
unset DESEC_TOKEN
```

`.env`: `ACME_DOMAIN="<domain>"`, `ACME_EMAIL` (contact of the Let's Encrypt account, not in the certificate). `NC_DOMAIN` and `PORTAL_DOMAIN` have to be exactly one label below `ACME_DOMAIN`.

Then `deploy.ps1` as in phase 7. New prompt on the Pi: the deSEC token, only while `/etc/lego/desec-token` doesn't exist. The first certificate takes two to four minutes, deSEC needs about two until all its nameservers have the record. If it fails, the test certificate stays and the run goes on; `sudo /usr/local/sbin/homelab-cert` shows why.

### Verify

| Where | Check | Expected |
|-------|-------|----------|
| Pi, end of `setup-pi.sh` | output | `active` for `homelab-cert.timer`, issuer `Let's Encrypt` without `STAGING` |
| Pi | `systemctl list-timers homelab-cert.timer` | next run at night, before the reboot at 04:30 |
| Pi | `sudo systemctl start homelab-cert.service; journalctl -u homelab-cert -n 20` | no renewal needed, no errors |
| Pi | `sudo ls -l /etc/lego` | everything `root`, `desec-token` `-rw-------` |
| Pi | `ls /usr/local/share/ca-certificates/` | no `homelab-test-*` file anymore |
| Pi | serial before and after `sudo /usr/local/sbin/homelab-cert 90` (forced renewal, costs one certificate of the weekly limit) | `certificate deployed for ...`, new serial in `/etc/ssl/nginx/` |
| PC | `curl.exe -s -o NUL -w "%{http_code} verify=%{ssl_verify_result}" https://<NC_DOMAIN>/status.php` | `200 verify=0`, without `-k` |
| PC | same for `https://<PORTAL_DOMAIN>/` | `302 verify=0` |
| browser | both names | padlock, no warning; turn warnings back on where they were skipped for the test certificate |
| portal | Nextcloud tile | online, the JVM trusts Let's Encrypt without the test CA |
| phone, mobile data | both names, Nextcloud app | no certificate question |
| PC | `deploy.ps1` again | no token prompt, no lego run |

## Open

- [ ] fixed DHCP leases for both VMs
- [x] reminder before the client secret expires (calendar, 4 weeks ahead)