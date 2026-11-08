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

## Open

- [ ] fixed DHCP leases for both VMs
- [ ] reminder before the client secret expires