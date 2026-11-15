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
- separate cloud-only user `<USER>@<TENANT>.onmicrosoft.com` for the Nextcloud login, MFA via Authenticator
- test user `test-unassigned@<TENANT>.onmicrosoft.com` for negative tests, keep it

### App registration "nextcloud"

- supported account types: single tenant
- redirect URI: added in phase 5, see "user_oidc" there
- API permissions (Microsoft Graph, delegated): `User.Read`, `openid`, `profile`, `email`, admin consent granted
- client secret: password manager, expires `<DATE>`

### Enterprise application "nextcloud"

- enabled for users to sign in: yes
- assignment required: yes
- visible to users: no
- assigned users: only `<USER>@`, with the app role `nc-user` since phase 5 (not "Default Access")

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
- results: `<USER>@` ok, `test-unassigned@` denied, Global Admin ok
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

## Phase 5: Nextcloud (Pi-VM)

- nginx site: `configs/pi/etc/nginx/sites-available/nextcloud.conf`

### Install

Done by `scripts/setup-pi.sh` now, kept here as the manual version.

Packages (PHP 8.4 on Debian 13):

```bash
sudo apt update
sudo apt install nginx php-fpm php-mysql php-gd php-curl php-mbstring php-xml php-zip php-intl php-gmp php-bcmath php-apcu php-redis php-imagick mariadb-server redis-server bzip2 gpg curl jq
sudo rm /etc/nginx/sites-enabled/default
sudo systemctl reload nginx
```

Database, password typed into a variable so it stays out of the history (a value pasted into `${...}` is read as a variable name and ends up empty):

```bash
read -rsp 'DB password: ' DBPASS; echo
sudo mariadb <<SQL
CREATE DATABASE nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'nextcloud'@'localhost' IDENTIFIED BY '${DBPASS}';
GRANT ALL PRIVILEGES ON nextcloud.* TO 'nextcloud'@'localhost';
FLUSH PRIVILEGES;
SQL
unset DBPASS
sudo ss -tlnp | grep 3306   # only 127.0.0.1
```

Nextcloud from the tarball, checked against the checksum and the signature:

```bash
cd /tmp
wget https://download.nextcloud.com/server/releases/latest.tar.bz2
wget https://download.nextcloud.com/server/releases/latest.tar.bz2.sha256
wget https://download.nextcloud.com/server/releases/latest.tar.bz2.asc
wget https://nextcloud.com/nextcloud.asc
sha256sum -c --ignore-missing latest.tar.bz2.sha256
gpg --import nextcloud.asc
gpg --verify latest.tar.bz2.asc latest.tar.bz2   # "Good signature"
sudo tar -xjf latest.tar.bz2 -C /var/www/
sudo chown -R www-data:www-data /var/www/nextcloud
sudo install -d -m 0750 -o www-data -g www-data /mnt/data/nextcloud-data
```

Install, `occ` asks for the database and admin password:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ maintenance:install --database mysql --database-name nextcloud --database-user nextcloud --admin-user ncadmin --data-dir /mnt/data/nextcloud-data
sudo -u www-data php occ config:system:set trusted_domains 0 --value=cloud.homelab.test
sudo -u www-data php occ config:system:set overwrite.cli.url --value=https://cloud.homelab.test
sudo -u www-data php occ status
```

- `sudo mariadb` works through unix socket auth as root, `mariadb -u nextcloud -p nextcloud -e 'SELECT 1;'` checks the password

Self-signed test certificate (replaced at go-live):

```bash
sudo install -d -m 0755 /etc/ssl/nginx
sudo openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 -subj "/CN=cloud.homelab.test" -addext "subjectAltName=DNS:cloud.homelab.test" -keyout /etc/ssl/nginx/cloud.homelab.test.key -out /etc/ssl/nginx/cloud.homelab.test.crt
sudo chmod 600 /etc/ssl/nginx/cloud.homelab.test.key
```

nginx site:

```powershell
scp configs/pi/etc/nginx/sites-available/nextcloud.conf <USER>@<PI_IP>:/tmp/nextcloud.conf
```

```bash
sudo install -m 0644 -o root -g root /tmp/nextcloud.conf /etc/nginx/sites-available/nextcloud.conf
sudo ln -s /etc/nginx/sites-available/nextcloud.conf /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
systemctl is-active nginx php8.4-fpm mariadb redis-server
sudo etckeeper commit "add nextcloud with nginx site"
```

- the cert key lands in `/etc/.git` too, like the WireGuard key; `/etc/.git` is never pushed

### Setup checks

```bash
cd /var/www/nextcloud
sudo -u www-data php occ status
sudo -u www-data php occ setupchecks
```

- the CLI skips some checks (OPcache, PHP memory limit, remote address), those only show under Administration settings → Overview (`/settings/admin/overview`)
- the database findings are only listed there too ("Show the findings")
- "Errors in the log" counts the last 7 days, old entries keep the warning around

### Self checks over loopback

Nextcloud tests its own URLs (`.well-known`, `.mjs`, `.js.map`, data directory, security headers) by calling `overwrite.cli.url`. The tunnel listener only accepts connections with a PROXY header, so these requests failed and most network checks were skipped.

- second listener `listen 127.0.0.1:443 ssl;` without `proxy_protocol` in the Nextcloud server block; nginx picks the listen parameters by the local address of the connection, so both can live next to each other
- loopback is already allowed in nftables (`iifname "lo" accept`)
- on the real Pi the hosts entry gets the real domain

```bash
echo '127.0.0.1 cloud.homelab.test' | sudo tee -a /etc/hosts
sudo apt install curl
curl -kI https://cloud.homelab.test/status.php   # HTTP/2 200
```

### Memory cache and file locking

APCu as local cache, Redis for locking and distributed cache, Redis only on a unix socket (no TCP port).

```bash
sudo apt install php-apcu redis-server php-redis
echo 'apc.enable_cli=1' | sudo tee -a /etc/php/8.4/mods-available/apcu.ini
sudo sed -i 's/^port 6379/port 0/' /etc/redis/redis.conf
sudo sed -i 's|^# unixsocket .*|unixsocket /run/redis/redis-server.sock|' /etc/redis/redis.conf
sudo sed -i 's/^# unixsocketperm .*/unixsocketperm 770/' /etc/redis/redis.conf
grep -E '^(port|unixsocket)' /etc/redis/redis.conf   # port 0, socket path, perm 770
sudo usermod -aG redis www-data
sudo systemctl restart redis-server php8.4-fpm
```

```bash
cd /var/www/nextcloud
sudo -u www-data php occ config:system:set memcache.local --value='\OC\Memcache\APCu'
sudo -u www-data php occ config:system:set memcache.locking --value='\OC\Memcache\Redis'
sudo -u www-data php occ config:system:set memcache.distributed --value='\OC\Memcache\Redis'
sudo -u www-data php occ config:system:set redis host --value='/run/redis/redis-server.sock'
sudo -u www-data php occ config:system:set redis port --type=integer --value=0
sudo -u www-data redis-cli -s /run/redis/redis-server.sock ping   # PONG, also after a reboot
```

- without `apc.enable_cli=1` occ and cron fail once APCu is the local cache
- restarting Redis while Nextcloud runs logs `Redis server went away` once, harmless

### Other settings

```bash
cd /var/www/nextcloud
sudo -u www-data php occ config:system:set default_phone_region --value="DE"
sudo -u www-data php occ config:system:set maintenance_window_start --type=integer --value=1   # hour in UTC
sudo -u www-data php occ maintenance:repair --include-expensive
```

### MariaDB

`configs/pi/etc/mysql/mariadb.conf.d/90-nextcloud.cnf` raises `innodb_log_file_size` to 256M (the setup check warns below 128M). Own drop-in instead of editing `50-server.cnf`, so package updates don't touch it.

```powershell
scp configs/pi/etc/mysql/mariadb.conf.d/90-nextcloud.cnf <USER>@<PI_IP>:/tmp/90-nextcloud.cnf
```

```bash
sudo install -m 0644 -o root -g root /tmp/90-nextcloud.cnf /etc/mysql/mariadb.conf.d/90-nextcloud.cnf
sudo systemctl restart mariadb
sudo mariadb -e "SELECT @@innodb_log_file_size/1024/1024 AS MiB;"   # 256
```

- MariaDB resizes the redo log on restart by itself
- slow query log stays off, only a hint in the setup check and it means extra writes

### HSTS

- `Strict-Transport-Security` with `max-age=15552000`, the minimum the setup check wants
- also needed in the static file location: it has its own `add_header` lines, so nothing is inherited from the server block
- browsers ignore HSTS over an untrusted certificate, so it can stay on with the self-signed test cert

```bash
curl -skI https://cloud.homelab.test/status.php | grep -i strict-transport
curl -skI https://cloud.homelab.test/core/img/favicon.ico | grep -i strict-transport
```

### Accepted hints

- AppAPI deploy daemon, email server, server ID, Imagick without SVG: not needed for now
- 2FA not enforced: users log in through Entra with MFA, the local admin is handled in hardening
- `nginx -t` warns about a duplicate `wasm` type: the Nextcloud sample adds it on top of Debian's `mime.types`

### After the changes

```bash
sudo etckeeper commit "nextcloud tuning: memcache, redis, mariadb, loopback listener, hsts"
sudo reboot
```

After the reboot: `redis-cli ... ping`, `occ setupchecks`, no new errors in the log.

### user_oidc (Entra login)

App version at install: `user_oidc` 8.13.0.

```bash
cd /var/www/nextcloud
sudo -u www-data php occ app:install user_oidc
sudo -u www-data php occ user_oidc:provider --help   # lists all provider options
```

Provider `Entra`. The client secret goes in through an environment variable, so it shows up neither in the bash history (`HISTCONTROL=ignoredups` records commands with a leading space too) nor in the process list:

```bash
cd /var/www/nextcloud
read -rp "Client secret: " NC_OIDC_SECRET
export NC_OIDC_SECRET
sudo --preserve-env=NC_OIDC_SECRET -u www-data php occ user_oidc:provider Entra \
  --clientid="<CLIENT_ID>" \
  --clientsecret-env=NC_OIDC_SECRET \
  --discoveryuri="https://login.microsoftonline.com/<TENANT_ID>/v2.0/.well-known/openid-configuration" \
  --unique-uid=0 \
  --mapping-uid=oid
unset NC_OIDC_SECRET
sudo -u www-data php occ user_oidc:provider          # lists configured providers
```

- `--unique-uid=0 --mapping-uid=oid`: the Nextcloud user ID is the Entra object ID, see ADR 0012; decide before the first login, changing it later creates a new user
- the identifier `Entra` is part of the backchannel logout URL, keep it; deleting the provider invalidates its accounts
- running the command again with the same identifier updates the provider, e.g. after a wrong secret; `--clientsecret-env` alone doesn't update anything, pass at least one more option (e.g. `--clientid`)
- on the fresh test VMs (newer `user_oidc`) the secret was empty after the setup script (AADSTS7000218, no secret sent), probably cleared by a second provider call with only the group options; the script now creates the provider in one call
- a wrong secret only shows at login, when the code is exchanged for tokens

Redirect URI: shown under Administration settings > OpenID Connect, added to the app registration in Entra (`Manual_Setup.md`).

Login only with an app role, see ADR 0013. Run after the role is assigned in Entra, otherwise `<USER>@` is locked out too:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ user_oidc:provider Entra \
  --group-provisioning=1 \
  --mapping-groups=roles \
  --group-whitelist-regex='/^nc-/' \
  --group-restrict-login-to-whitelist=1
sudo -u www-data php occ group:list                  # nc-user with the object ID as member
```

Send the login page straight to Entra, the local admin stays reachable via `/login?direct=1`:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ config:app:set --type=string --value=0 user_oidc allow_multiple_user_backends
```

### user_oidc tests

| Test | Expected |
|------|----------|
| `<USER>@` in a private window | logged in, `occ user:list` shows the object ID |
| `test-unassigned@` | denied by Entra (AADSTS50105), no user created |
| Global Admin | passes Entra, Nextcloud denies the login ("Zugriff verboten"), no user created |
| `/login` | redirects to Entra |
| `/login?direct=1` | local login form, local admin works |

- Entra signs in silently with whatever Microsoft account is active in the browser; with the Entra admin center open in the same browser that's the Global Admin and Nextcloud shows "Zugriff verboten" right away, use a private window or a separate browser profile

Desktop client login (Login Flow v2) and WebDAV with the issued app password, from the PC. `-k` because the test certificate is self-signed (Schannel reports an untrusted root):

```powershell
$r = curl.exe -sk -X POST "https://cloud.homelab.test/index.php/login/v2" | ConvertFrom-Json
$r.login   # open in the browser, log in via Entra, grant access
$c = curl.exe -sk -X POST "$($r.poll.endpoint)" -d "token=$($r.poll.token)" | ConvertFrom-Json
$c.loginName   # object ID
curl.exe -sk -o NUL -w "%{http_code}`n" -u "$($c.loginName):$($c.appPassword)" -X PROPFIND -H "Depth: 1" "https://cloud.homelab.test/remote.php/dav/files/$($c.loginName)/"   # 207
curl.exe -sk -o NUL -w "%{http_code}`n" -u "$($c.loginName):falsch" -X PROPFIND -H "Depth: 1" "https://cloud.homelab.test/remote.php/dav/files/$($c.loginName)/"   # 401
```

Revoke the app password as admin, without logging in as the user:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ user:auth-tokens:list <OBJECT_ID>          # app passwords are "permanent", browser sessions "temporary"
sudo -u www-data php occ user:auth-tokens:delete <OBJECT_ID> <TOKEN_ID>
```

- the first PROPFIND with the same app password returns 401 afterwards
- clean up in PowerShell: `Remove-Variable c, r`
- app passwords don't depend on Entra: removing a user or role in Entra doesn't stop an already configured client, revoke its tokens here
- non-admins find their own sessions under `/settings/user/security`

## Phase 6: Hardening

### Design

- vps: rate limits for new connections per source IP (nftables) and open connections per IP (nginx stream), see ADR 0014
- no fail2ban on the Pi: it only sees `10.99.0.1` on the network level, a ban there would block the vps; Nextcloud throttles by the real IP from the PROXY header
- SSH on both VMs: key only, no root, only the admin user from `ADMIN_USER`
- unattended-upgrades on both VMs with a nightly reboot, see ADR 0016
- Nextcloud: 2FA (TOTP) enforced only for the local admin, see ADR 0015; public links need a password and expire; no public upload
- configs:
    - `configs/<host>/etc/ssh/sshd_config.d/10-hardening.conf`
    - `configs/<host>/etc/apt/apt.conf.d/20auto-upgrades`, `52unattended-upgrades-local`
    - `configs/vps/etc/nftables.conf`, `configs/vps/etc/nginx/stream.d/gateway.conf`

### SSH key (PC)

Steps in `Manual_Setup.md`. Check before touching sshd, otherwise password login is gone without a working key:

```powershell
ssh -o PasswordAuthentication=no -o PreferredAuthentications=publickey <ADMIN_USER>@192.168.178.38 "echo key-ok"
ssh -o PasswordAuthentication=no -o PreferredAuthentications=publickey <ADMIN_USER>@192.168.178.169 "echo key-ok"
```

### SSH (both VMs)

```powershell
scp configs/vps/etc/ssh/sshd_config.d/10-hardening.conf <USER>@<VPS_IP>:/tmp/
scp configs/pi/etc/ssh/sshd_config.d/10-hardening.conf <USER>@<PI_IP>:/tmp/
```

Keep an open SSH session, it survives the reload. VMware console as fallback.

```bash
sudo install -m 0644 -o root -g root /tmp/10-hardening.conf /etc/ssh/sshd_config.d/10-hardening.conf
ls /etc/ssh/sshd_config.d/            # nothing may sort before 10-, sshd takes the first value it reads
sudo sshd -t && sudo systemctl reload ssh
sudo sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication|allowusers|maxauthtries|logingracetime|x11forwarding) '
sudo etckeeper commit "harden sshd"
```

- expected: `permitrootlogin no`, `passwordauthentication no`, `kbdinteractiveauthentication no`, `allowusers <ADMIN_USER>`, `maxauthtries 3`, `logingracetime 30`, `x11forwarding no`
- test with a new connection from the PC: `ssh -o PubkeyAuthentication=no <ADMIN_USER>@<IP>` fails with `Permission denied (publickey)`, `ssh <ADMIN_USER>@<IP> "echo ok"` prints `ok`

### Rate limits (vps)

```powershell
scp configs/vps/etc/nftables.conf configs/vps/etc/nginx/stream.d/gateway.conf <USER>@<VPS_IP>:/tmp/
```

```bash
sudo install -m 0644 -o root -g root /tmp/nftables.conf /etc/nftables.conf
sudo nft -c -f /etc/nftables.conf
sudo nft -f /etc/nftables.conf
sudo nft list table inet gateway
sudo install -m 0644 -o root -g root /tmp/gateway.conf /etc/nginx/stream.d/gateway.conf
sudo nginx -t && sudo systemctl reload nginx
sudo etckeeper commit "rate limits for ssh and https"
```

| Where | Limit |
|-------|-------|
| nftables, 22/tcp | 5 new connections per minute and IP, burst 10 |
| nftables, 443/tcp | 20 new connections per second and IP, burst 50 |
| nginx stream | 30 open connections per IP (`limit_conn`), `preread_timeout 5s` instead of 30 s |

- dynamic sets per address family (`ssh_rate4/6`, `https_rate4/6`), entries expire after a minute without new connections
- only the SYN of a new connection is dropped, the client retransmits it after about a second; normal users see a delay at most, scanners and floods get slowed down
- the drop rules have a `counter`, useful for monitoring later: `sudo nft list chain inet gateway input | grep counter`
- no protection against brute force: any number of requests fit through one keep-alive connection, that's Nextcloud's job

### Rate limit tests

HTTPS, from the Pi-VM through the LAN to the vps. 100 separate curl processes are too slow to hit the limit (about 30 connections/s), one curl with parallel HTTP/1.1 connections does:

```bash
time (for i in $(seq 1 100); do curl -sk -o /dev/null -w "%{http_code}\n" --max-time 3 --resolve cloud.homelab.test:443:192.168.178.38 https://cloud.homelab.test/status.php & done | sort | uniq -c; wait)
time curl -sk --http1.1 --parallel --parallel-immediate --parallel-max 200 --max-time 5 -w "%{http_code}\n" --resolve cloud.homelab.test:443:192.168.178.38 $(for i in $(seq 1 200); do printf -- '-o /dev/null https://cloud.homelab.test/status.php?%d ' $i; done) | sort | uniq -c
```

On the vps afterwards:

```bash
sudo nft list chain inet gateway input | grep 'dport 443'
sudo grep -c 'limiting connections' /var/log/nginx/error.log
```

SSH, from the PC (`Test-NetConnection` is too slow, it pings and resolves first, the burst refills in between):

```powershell
1..20 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; $ok = $c.ConnectAsync("192.168.178.38", 22).Wait(1000); $c.Close(); "$_ $ok" }
```

| Test | Result |
|------|--------|
| 100 single curl processes | 100x `200` in 3.4 s, counter 0 |
| 200 parallel connections | 30x `200`, 170x `000`; counter `https_rate4` 541 packets (incl. retransmits), nginx 103x `limiting connections` |
| 20 TCP connects to 22 | 1 to 10 `True`, from 11 `False` |
| browser | Nextcloud works normally |

- after the SSH test new connections to the vps (also `scp`) are blocked for about a minute, open sessions keep working

### Automatic updates (both VMs)

```powershell
scp configs/vps/etc/apt/apt.conf.d/20auto-upgrades configs/vps/etc/apt/apt.conf.d/52unattended-upgrades-local <USER>@<VPS_IP>:/tmp/
scp configs/pi/etc/apt/apt.conf.d/20auto-upgrades configs/pi/etc/apt/apt.conf.d/52unattended-upgrades-local <USER>@<PI_IP>:/tmp/
```

Commit the sshd change before, apt commits pending `/etc` changes on its own.

```bash
sudo apt install unattended-upgrades
sudo install -m 0644 -o root -g root /tmp/20auto-upgrades /etc/apt/apt.conf.d/20auto-upgrades
sudo install -m 0644 -o root -g root /tmp/52unattended-upgrades-local /etc/apt/apt.conf.d/52unattended-upgrades-local
apt-config dump | grep -E 'APT::Periodic::(Update-Package-Lists|Unattended-Upgrade)|Automatic-Reboot|Remove-Unused-Kernel'
sudo unattended-upgrade --dry-run --debug 2>&1 | grep -i "allowed origins"
systemctl list-timers 'apt-daily*'
sudo etckeeper commit "enable unattended-upgrades"
```

- origins are the Debian default from `50unattended-upgrades`: `trixie` with label `Debian` (point releases) and `Debian-Security`, `trixie-security`; not only security updates
- the local file only adds the reboot: vps 04:00, Pi-VM 04:30, so both aren't down at the same time; reboots only happen if an update needs one (`/var/run/reboot-required`)
- timers `apt-daily.timer` and `apt-daily-upgrade.timer` active
- Nextcloud isn't installed through apt, unattended-upgrades doesn't update it (see below)

### Nextcloud: 2FA for the local admin

TOTP setup in the browser, see `Manual_Setup.md`. Then enforce it only for a dedicated group (ADR 0015):

```bash
cd /var/www/nextcloud
sudo -u www-data php occ group:add local-2fa
sudo -u www-data php occ group:adduser local-2fa ncadmin
sudo -u www-data php occ twofactorauth:enforce --on --group=local-2fa
sudo -u www-data php occ twofactorauth:enforce
sudo -u www-data php occ twofactorauth:state ncadmin
```

- apps `twofactor_totp` and `twofactor_backupcodes` ship enabled
- `ncadmin` via `/login?direct=1`: password, then TOTP code; `<USER>@` via Entra: no TOTP
- lockout: `sudo -u www-data php occ twofactorauth:disable ncadmin totp`

### Nextcloud: public links

```bash
cd /var/www/nextcloud
sudo -u www-data php occ config:app:set core shareapi_enforce_links_password --value=yes
sudo -u www-data php occ config:app:set core shareapi_default_expire_date --value=yes
sudo -u www-data php occ config:app:set core shareapi_expire_after_n_days --value=7
sudo -u www-data php occ config:app:set core shareapi_enforce_expire_date --value=yes
sudo -u www-data php occ config:app:set core shareapi_allow_public_upload --value=no
sudo -u www-data php occ config:list core | grep -i shareapi
```

- check in Administration settings > Sharing; a new link can't be created without a password, the expiry date is preset to 7 days and can't be removed, no upload option

### Nextcloud: brute force protection

`auth.bruteforce.protection.enabled` and `ratelimit.protection.enabled` aren't set in `config.php`, the defaults (on) apply. Check: three wrong passwords for `ncadmin` via `/login?direct=1` from the PC, then:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ security:bruteforce:attempts 192.168.178.197
sudo -u www-data php occ security:bruteforce:attempts 10.99.0.1
sudo -u www-data php occ security:bruteforce:reset 192.168.178.197
```

- PC IP: attempts > 0 with a delay; `10.99.0.1`: 0 attempts, so the throttling works per client and not for the whole tunnel

### Nextcloud: updates

```bash
sudo -u www-data php /var/www/nextcloud/occ update:check
```

- 2026-11-14: `Nextcloud 35.0.3 is available`, running 35.0.2, not installed yet
- update path (not run yet): snapshot / backup first, then the built-in updater (`sudo -u www-data php /var/www/nextcloud/updater/updater.phar`), afterwards `occ upgrade` if the updater doesn't run it, `occ status` and setup checks
- app `updatenotification` is enabled, the admin sees available updates in the web UI; notifications outside Nextcloud come with monitoring

### Revoking access

| Step | Result |
|------|--------|
| app password of `<USER>@` revoked in the UI (Security > Devices & sessions) | WebDAV with it: 207 before, 401 after |
| `test-unassigned@` temporarily assigned (`nc-user`), logged in, app password created | WebDAV 207 |
| assignment removed in Entra | new login: AADSTS50105; WebDAV with the existing app password still 207 |
| `occ user:disable <OBJECT_ID>` | WebDAV 401 |
| cleanup: `occ user:delete <OBJECT_ID>`, `test-unassigned@` stays unassigned in Entra | |

Taking someone's access away completely:

1. remove the assignment / role in Entra (no new logins)
2. disable the user in Nextcloud, or delete their app passwords if the account should stay:

```bash
cd /var/www/nextcloud
sudo -u www-data php occ user:disable <OBJECT_ID>
sudo -u www-data php occ user:auth-tokens:list <OBJECT_ID>
sudo -u www-data php occ user:auth-tokens:delete <OBJECT_ID> <TOKEN_ID>
```

### Snapshots

- `hardening-ok` (both VMs)

## Phase 7: Setup scripts

- `scripts/setup-vps.sh`, `scripts/setup-pi.sh`, shared helpers in `scripts/lib.sh`, `scripts/deploy.ps1` runs both from the PC; how to run them in `Manual_Setup.md`
- `deploy.ps1` passes the WireGuard public keys between the hosts (the scripts copy them to `~/homelab-gateway/wg-gw.pub`, `/etc/wireguard` is root only), values from the environment win over `.env`
- Pi: the data disk is formatted and mounted by the script, only if it is the single empty disk besides the system disk (or `DATA_DISK`), after a prompt
- they do what phases 2 to 6 describe; the commands above stay as the manual version and explanation
- idempotent: files are only replaced when they differ, services only reloaded then; keys, database, Nextcloud install and OIDC provider are only created when missing
- settings without secrets in `.env` (git-ignored, template `.env.example`); passwords and the client secret are asked for on the terminal and only when needed
- test values in the configs (`cloud.homelab.test`, `admin`, the LAN) are replaced from `.env` while copying
- not scripted: Entra, TOTP enrollment, the real certificate, the hosts entry on the PC
- not for a Pi that already runs Nextcloud, data migration is a separate step
- secrets are read with visible input (`read -rp`): pasting blind into `read -s` over `ssh -t` from PowerShell went wrong several times; the value stays in the terminal scrollback, not in the history

### Tests (2026-11-14)

Two fresh VMs `vps-test` and `pi-test` (Debian 13; `pi-test` got a desktop by accident, SSH added and booting to `multi-user.target`), old VMs left as they are, hosts entry on the PC pointed to `vps-test`.

| Test | Result |
|------|--------|
| `deploy.ps1` on both fresh VMs | data disk found and formatted after the prompt, tunnel up, Nextcloud installed, `status.php` over the vps works |
| `ncadmin` via `/login?direct=1` | works, TOTP enforced (`Two-factor challenge failed` in the log for wrong codes) |
| `<USER>@` after the first run | Entra login, then AADSTS7000218 from Nextcloud: the provider had no client secret |
| provider and user deleted, `deploy.ps1` again with the fixed script | only the sudo passwords and the client secret asked, `Client secret loaded from environment variable`, `<USER>@` logs in |

Found while testing:

- `deploy.ps1` had a helper function `Ssh`; PowerShell function names ignore case, so it shadowed `ssh` and called itself; renamed to `Remote`, all calls use `ssh.exe` / `scp.exe`
- the client secret was empty after the first run, see `user_oidc` above; the provider is now created in one call
- `user_oidc:provider` with only `--clientsecret-env` changes nothing, see `user_oidc` above

## Open

- [ ] fixed DHCP leases for both VMs
- [x] phase 5: if Nextcloud runs in Docker, revisit the Pi firewall (not needed, Nextcloud runs natively)
- [ ] go-live: apply the SSH hardening and rate limits on the real VPS before it is reachable (configs ready, phase 6)
- [x] phase 5: reverse proxy on the Pi has to expect the PROXY header (`listen 443 ssl proxy_protocol`) and take the real IP only from `10.99.0.1`
- [x] phase 5: write up the install steps (PHP, MariaDB, Nextcloud, certificate, nginx site)
- [x] phase 6: local admin `ncadmin`: TOTP enforced (group `local-2fa`, ADR 0015)
- [ ] phase 5: certificate on the Pi, TLS-ALPN-01 works through the passthrough without opening port 80
- [x] phase 5: keep Global Admin out of Nextcloud (app role + group restriction in user_oidc, ADR 0013)
- [x] reminder before the client secret expires (calendar, 4 weeks ahead)
- [ ] go-live: test the real desktop and mobile clients (the phone can't resolve `cloud.homelab.test`, the PC already syncs with the production Pi)
- [ ] after buying the domain: add it as custom domain in Entra, move the users' UPNs to it, own cloud-only admin account instead of the private one
- [ ] update Nextcloud to 35.0.3 along the documented update path
- [ ] monitoring: Nextcloud update notification, client secret expiry, rate limit counters
