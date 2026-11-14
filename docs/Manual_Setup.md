# Manual setup

Steps that can't be scripted (or not reasonably), plus how to verify each phase.
Shell commands live in `NOTES.md` and later in `setup.sh`.

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
    - `simeon@<TENANT>.onmicrosoft.com` for the Nextcloud login, no roles
    - `test-unassigned@<TENANT>.onmicrosoft.com` for negative tests, keep it
    - first sign in for both at `myaccount.microsoft.com` in a private window: new password, MFA
5. app registration (Entra ID > App registrations > New registration):
    - name `nextcloud`, single tenant, no redirect URI yet
    - Certificates & secrets: new client secret, copy the value to the password manager, expires `<DATE>`
    - API permissions: Microsoft Graph delegated `openid`, `profile`, `email` (plus default `User.Read`), grant admin consent
6. enterprise application (Entra ID > Enterprise applications > nextcloud):
    - Properties: enabled for sign in yes, assignment required yes, visible to users no
    - Users and groups: assign only `simeon@`

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
- expected: `simeon@` ok, `test-unassigned@` denied (AADSTS50105), Global Admin ok (bypass, see `docs/arc42/architecture.md` section 11)
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
3. role assignment: Enterprise applications > nextcloud > Users and groups > edit `simeon@`, select `Nextcloud User` instead of "Default Access"; `test-unassigned@` stays unassigned

### Verify

Each login in a new private window, so no other Microsoft session gets picked up.

| Who | Expected |
|-----|----------|
| `simeon@` | logged in; `occ user:list` shows the object ID, `occ group:list` shows it in `nc-user` |
| `test-unassigned@` | Entra error page (AADSTS50105), no new user |
| Global Admin | passes Entra, Nextcloud denies ("Zugriff verboten"), no new user |
| anyone, `/login` | redirect to Entra without a Nextcloud login form |
| local admin, `/login?direct=1` | local login form works |

Desktop client login and WebDAV are tested without a client via `curl.exe`, see `NOTES.md`. The real desktop and mobile clients follow after go-live.

## Phase 6: Hardening

Configs and commands in `NOTES.md`. Manual parts:

1. SSH key on the PC (PowerShell), before password login is turned off:
    ```powershell
    ssh-keygen -t ed25519 -C "sgeiger@desktop-ae9t35e"
    Get-Service ssh-agent | Set-Service -StartupType Automatic
    Start-Service ssh-agent
    ssh-add $env:USERPROFILE\.ssh\id_ed25519
    type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <USER>@<VPS_IP> "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
    type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <USER>@<PI_IP> "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
    ```
    - passphrase in the password manager, entry named after the key (`SSH-Key desktop-ae9t35e (id_ed25519)`) with fingerprint (`ssh-keygen -lf $env:USERPROFILE\.ssh\id_ed25519.pub`) and the hosts it's on
    - the agent keeps the key, the passphrase is only needed for `ssh-add`
2. TOTP for `ncadmin`: private window, `/login?direct=1`, Personal settings > Security > enable TOTP, scan the QR code; generate backup codes on the same page and store them with the `ncadmin` entry in the password manager. Only then enforce 2FA (`NOTES.md`)
3. sharing: Administration settings > Sharing shows password and expiry date enforced, public upload off
4. client secret: App registrations > nextcloud > Certificates & secrets, expiry date into the password manager, calendar reminder 4 weeks before
5. revoking access for a person: remove the assignment in Entra (Enterprise applications > nextcloud > Users and groups), then disable the user or delete the app passwords in Nextcloud (`NOTES.md`)

### Verify

| From | Check | Expected |
|------|-------|----------|
| PC | `ssh -o PubkeyAuthentication=no sgeiger@<IP>` (both VMs) | `Permission denied (publickey)` |
| PC | `ssh sgeiger@<IP> "echo ok"` (both VMs) | `ok`, no password prompt |
| PC | 20 fast TCP connects to 22 on the vps (`NOTES.md`) | 1 to 10 succeed, then dropped for about a minute |
| Pi-VM | 200 parallel HTTPS connections to the vps (`NOTES.md`) | 30 succeed, nftables counter and `limiting connections` in the nginx log go up |
| both | `sudo unattended-upgrade --dry-run --debug 2>&1 \| grep -i "allowed origins"` | Debian and Debian-Security origins |
| both | `systemctl list-timers 'apt-daily*'` | both timers active |
| browser | `ncadmin` via `/login?direct=1` | TOTP code asked after the password |
| browser | `simeon@` via Entra | no TOTP in Nextcloud |
| browser | create a share link as `simeon@` | password required, expiry date 7 days, no upload option |
| Pi-VM | `occ security:bruteforce:attempts <PC IP>` after 3 wrong passwords | attempts > 0, delay > 0; `10.99.0.1` stays at 0 |
| PC | WebDAV with a revoked app password | 401 |
| browser | Nextcloud with `simeon@` | works normally, no delay |

## Open

- [ ] fixed DHCP leases for both VMs
- [x] reminder before the client secret expires (calendar, 4 weeks ahead)