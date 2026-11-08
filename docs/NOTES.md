# Notes

Commands and decisions that actually worked, in the order they were done.
Secrets (private keys, client secret, passwords) never go here.

## Test environment

- VMware Workstation Pro on Windows 11, network mode bridged
- VMnet0 bound to the physical adapter explicitly (Virtual Network Editor), not "Automatic"
- two VMs, both Debian 13 (netinst), only "SSH server" and "standard system utilities" selected
    - `vps`: stands in for the netcup VPS
    - `Pi-VM`: stands in for the Raspberry Pi
- TODO: fixed DHCP lease in the router for both VMs

| VM    | LAN IP          | Tunnel IP  |
|-------|-----------------|------------|
| vps   | 192.168.178.38  | 10.99.0.1  |
| Pi-VM | 192.168.178.169 | 10.99.0.2  |

## Phase 0: Base setup (both VMs)

### Shell history

Added to `~/.bashrc` (not run as one-off commands, otherwise lost after logout):

```bash
HISTSIZE=<TODO>
HISTFILESIZE=<TODO>
HISTTIMEFORMAT='<TODO>'
HISTCONTROL=ignoredups
PROMPT_COMMAND='history -a'
```

- `HISTCONTROL=ignoredups` instead of the Debian default `ignoreboth`, so commands with a leading space are recorded too
- check: `tail ~/.bash_history`

### etckeeper

```bash
sudo apt install etckeeper
```

- versions `/etc` in a local git repo (`/etc/.git`)
- commits automatically on apt runs and once a day ("daily autocommit")
- manual commit after own edits: `sudo etckeeper commit "message"`
- `/etc/.git` contains private keys, never push it anywhere

### Pi-VM: separate data disk

- disk 1 (20 GB): system, disk 2 (30 GB): data, mirrors SD card + SSD on the real Pi
- data disk mounted via UUID in `/etc/fstab`, mount point `/mnt/data` (`/dev/sdb1`, ext4)
- check: `findmnt /mnt/data`
- TODO: fstab line

### Snapshots

- `clean`: fresh install, updated, history + etckeeper set up (both VMs)

## Phase 1: Entra ID

- own tenant via Azure for Students (no credit card), signed up with the private Microsoft account, not the university account
- private account = Global Administrator, only used to manage the tenant
- security defaults enabled, 2-step verification on the private account
- separate cloud-only user `simeon@<TENANT>.onmicrosoft.com` for the Nextcloud login, MFA via Authenticator
- test user `test-unassigned@<TENANT>.onmicrosoft.com` for negative tests, keep it

### App registration "nextcloud"

- supported account types: single tenant
- redirect URI: added in phase 5 (`https://<HOST>/apps/user_oidc/code`, exact URI shown by user_oidc)
- API permissions (Microsoft Graph, delegated): `User.Read`, `openid`, `profile`, `email`, admin consent granted
- client secret: password manager, expires `<DATE>`

### Enterprise application "nextcloud"

- enabled for users to sign in: yes
- assignment required: yes
- visible to users: no
- assigned users: only `simeon@`

### Values

- tenant ID: `<TENANT_ID>`
- client ID: `<CLIENT_ID>`
- discovery URI: `https://login.microsoftonline.com/<TENANT_ID>/v2.0/.well-known/openid-configuration`

### Test without Nextcloud

Temporary redirect URI `http://localhost` (platform Web), then open:

```
https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/authorize?client_id=<CLIENT_ID>&response_type=code&redirect_uri=http%3A%2F%2Flocalhost&scope=openid%20profile%20email&response_mode=query
```

- result is in the address bar, the page itself always fails (nothing runs on localhost)
- `?code=...` = login ok, `?error=...` or Microsoft error page = denied
- results: `simeon@` ok, `test-unassigned@` denied, Global Admin ok
- Global Admins bypass "assignment required" by design (documented by Microsoft), has to be handled on the Nextcloud side
- remove `http://localhost` redirect URI afterwards

## Phase 2: WireGuard tunnel

### Design

- tunnel net `10.99.0.0/24`, interface `wg-gw` (separate from the existing VPN on the real Pi)
- vps listens on UDP 51820, Pi initiates the connection (works behind CGNAT)
- `AllowedIPs` only the `/32` of the peer, no `0.0.0.0/0`
- no IP forwarding on either side

### Install (both VMs)

```bash
sudo apt update
sudo apt install wireguard
```

### Keys (on each VM, private key never leaves the machine)

```bash
sudo sh -c 'umask 077; cd /etc/wireguard && wg genkey | tee wg-gw.key | wg pubkey > wg-gw.pub && cat wg-gw.pub'
```

- don't paste a block starting with `sudo -i`, the remaining lines ran twice (once as root, once as user)
- check: `sudo ls -l /etc/wireguard` (owner root, `-rw-------`)

### Config

Templates: `configs/vps/etc/wireguard/wg-gw.conf.example`, `configs/pi/etc/wireguard/wg-gw.conf.example`

Written with the private key read from the key file, so it never shows up in the terminal:

```bash
sudo sh -c 'umask 077; cat > /etc/wireguard/wg-gw.conf <<EOF
[Interface]
Address = 10.99.0.1/24
ListenPort = 51820
PrivateKey = $(cat /etc/wireguard/wg-gw.key)

[Peer]
# pi
PublicKey = <PI_PUBLIC_KEY>
AllowedIPs = 10.99.0.2/32
EOF'
```

Pi side analogous, with `Endpoint = <VPS_IP>:51820` and `PersistentKeepalive = 25`, no `ListenPort`.

Check without printing the key:

```bash
sudo grep -v PrivateKey /etc/wireguard/wg-gw.conf
```

### Start (vps first, then Pi)

```bash
sudo systemctl enable --now wg-quick@wg-gw
```

- `active (exited)` is normal, the interface keeps running in the kernel
- `wg/gw` in the unit description is just systemd unescaping `-`

### Tests

```bash
sudo wg show                      # latest handshake, transfer on both sides
ping -c 3 10.99.0.1               # from Pi-VM, blocked by the vps firewall since phase 3
ping -c 3 10.99.0.2               # from vps
cat /proc/sys/net/ipv4/ip_forward # must be 0
```

- `sysctl` is in `/usr/sbin`, not in the user PATH on Debian, use `sudo sysctl` or `/proc`
- reboot vps: handshake comes back without touching the Pi
- reboot Pi-VM: tunnel comes up on its own
- logs: `sudo journalctl -u wg-quick@wg-gw`

### Snapshots

- `wireguard-ok` (both VMs)

## Phase 3: Firewall (nftables)

### Design

- one file per host: `configs/vps/etc/nftables.conf`, `configs/pi/etc/nftables.conf`
- own table `inet gateway`, replaced on load (`table` + `delete table`), no `flush ruleset`, so rules from other tools (Docker later) survive a reload
- input and forward: policy drop on both, output not filtered
- vps: 22/tcp, 443/tcp, 51820/udp, ICMP echo; nothing new from `wg-gw` (the Pi can answer, but never open connections to the vps)
- Pi-VM: from `wg-gw` only 443/tcp from `10.99.0.1`, SSH only from the LAN `192.168.178.0/24`, ICMP echo
- Pi-VM needs no inbound rule for WireGuard, it initiates the connection and the replies match `established`

### Install (both VMs)

Copy from the repo (Windows):

```powershell
scp configs/vps/etc/nftables.conf <USER>@<VPS_IP>:/tmp/nftables.conf
scp configs/pi/etc/nftables.conf <USER>@<PI_IP>:/tmp/nftables.conf
```

On each VM:

```bash
dpkg -l nftables | tail -1          # "ii" = installed, ships with Debian 13, service is off by default
sudo install -m 0644 -o root -g root /tmp/nftables.conf /etc/nftables.conf
sudo nft -c -f /etc/nftables.conf   # syntax check only, loads nothing
sudo systemctl enable --now nftables
sudo nft list ruleset
sudo etckeeper commit "add nftables firewall"
```

- vps: `WG_PORT` in the file has to match `sudo wg show wg-gw listen-port`
- run the etckeeper commit after `systemctl enable`, the symlink in `/etc/systemd/system/sysinit.target.wants/` belongs to the change
- an apt run before the manual commit commits pending `/etc` changes on its own ("saving uncommitted changes in /etc prior to apt run")

### Tests

```bash
sudo apt install netcat-openbsd   # both VMs
sudo nc -lk 443                   # Pi-VM, separate terminal, blocks until Ctrl+C
```

Don't paste further commands after `nc -lk`, they end up as input for netcat.

```bash
ping -c 3 10.99.0.2               # from vps: works
ping -c 3 -W 2 10.99.0.1          # from Pi-VM: 100 % loss
nc -zv -w 3 10.99.0.2 443         # from vps: succeeded
nc -zv -w 3 10.99.0.2 22          # from vps: timeout
nc -zv -w 3 10.99.0.1 22          # from Pi-VM: timeout
```

```powershell
Test-NetConnection 192.168.178.169 -Port 443   # from the PC: TcpTestSucceeded False
```

- SSH sessions opened before loading the rules keep working through `established`, test with a new connection
- reboot both VMs: `sudo nft list ruleset` shows table `inet gateway`, tunnel comes back up

### Snapshots

- `firewall-ok` (Pi-VM only, missed on the vps; `passthrough-ok` includes the firewall)

## Phase 4: TLS passthrough (vps)

### Design

- nginx `stream` (layer 4), no HTTP on the vps at all; DNAT via nftables would need `ip_forward = 1`, see ADR 0007
- `ssl_preread` reads the SNI from the ClientHello without decrypting, only known hostnames go into the tunnel, everything else is closed on the vps
- `proxy_protocol on`: the Pi gets the real client IP in a plain text header before the TLS stream
- test hostname `cloud.homelab.test` (`.test` is reserved), replaced by the real domain later
- config: `configs/vps/etc/nginx/stream.d/gateway.conf`

### Install (vps)

```bash
sudo apt install nginx libnginx-mod-stream
ls /etc/nginx/modules-enabled/        # needs an entry with "stream"
sudo rm /etc/nginx/sites-enabled/default
sudo mkdir -p /etc/nginx/stream.d
echo 'include /etc/nginx/stream.d/*.conf;' | sudo tee -a /etc/nginx/nginx.conf
tail -3 /etc/nginx/nginx.conf         # include must come after the closing } of http
```

Copy `gateway.conf` from the repo (Windows):

```powershell
scp configs/vps/etc/nginx/stream.d/gateway.conf <USER>@<VPS_IP>:/tmp/gateway.conf
```

```bash
sudo install -m 0644 -o root -g root /tmp/gateway.conf /etc/nginx/stream.d/gateway.conf
sudo nginx -t
sudo systemctl reload nginx
sudo ss -tlnp | grep ':443'           # nginx on 0.0.0.0:443 and [::]:443
sudo etckeeper commit "add nginx stream passthrough"
```

- the `stream` block has to be in the main context, `conf.d/*.conf` is included inside `http` and doesn't work for it

### Tests

Pi-VM, prints the first line of one connection and exits:

```bash
sudo nc -l 443 | head -n 1
```

From the PC (`curl.exe`, not the PowerShell alias `curl`):

```powershell
curl.exe -vk --max-time 5 --resolve cloud.homelab.test:443:192.168.178.38 https://cloud.homelab.test/
curl.exe -vk --max-time 5 https://192.168.178.38/
curl.exe -vk --max-time 5 --resolve other.test:443:192.168.178.38 https://other.test/
```

- known name: Pi-VM shows `PROXY TCP4 192.168.178.197 192.168.178.38 <port> 443`, curl times out (no TLS server on the Pi yet)
- IP only / unknown name: curl fails at once, nothing arrives on the Pi-VM, `/var/log/nginx/error.log` on the vps shows `connect() failed (111)` to `127.0.0.1:1`
- reboot vps: `systemctl is-active nginx`, `ss` shows 443 again

### Snapshots

- `passthrough-ok` (vps)

## Open

- [ ] fixed DHCP leases for both VMs
- [ ] phase 5: if Nextcloud runs in Docker, revisit the Pi firewall (Docker needs forwarding, published ports go through the forward chain)
- [ ] phase 6: harden SSH on the real VPS (open to the internet there)
- [ ] phase 5: reverse proxy on the Pi has to expect the PROXY header (`listen 443 ssl proxy_protocol`) and take the real IP only from `10.99.0.1`
- [ ] phase 5: certificate on the Pi, TLS-ALPN-01 works through the passthrough without opening port 80
- [ ] phase 5: keep Global Admin out of Nextcloud (app role + group restriction in user_oidc, or disable auto provisioning)
- [ ] reminder before the client secret expires