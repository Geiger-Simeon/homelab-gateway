#!/usr/bin/env bash
# sets up the pi: wireguard client, nftables, mariadb, redis, php, nextcloud with user_oidc, updates, ssh
# usage: sudo bash scripts/setup-pi.sh   (reads .env from the repo root, or ENV_FILE)
# secrets are asked for on the terminal when needed, they can also come from the environment
# safe to run again, see docs/Manual_Setup.md
# not for a pi that already runs nextcloud: data migration is a separate step

source "$(dirname "$0")/lib.sh"

NC_DIR=/var/www/nextcloud
NC_GPG_FINGERPRINT=${NC_GPG_FINGERPRINT:-28806A878AE423A28372792ED75899B9A724937A}

occ() { runuser -u www-data -- php "$NC_DIR/occ" "$@"; }

# nextcloud data must not end up on the system disk / sd card
# formats only an empty disk (no partitions, no filesystem) and asks before doing so
setup_data_disk() {
    local root_disk name type disk part uuid answer
    local -a cand=()
    # shellcheck disable=SC2153  # comes from .env
    mountpoint -q "$DATA_DIR" && return

    if [[ -n ${DATA_DISK:-} ]]; then
        cand=("${DATA_DISK#/dev/}")
    else
        root_disk=$(lsblk -no PKNAME "$(findmnt -no SOURCE /)")
        while read -r name type; do
            [[ $type == disk && $name != "$root_disk" ]] || continue
            [[ $(lsblk -no NAME "/dev/$name" | wc -l) -eq 1 ]] || continue
            blkid -p "/dev/$name" > /dev/null 2>&1 && continue
            cand+=("$name")
        done < <(lsblk -dno NAME,TYPE)
    fi
    [[ ${#cand[@]} -eq 1 ]] || die "$DATA_DIR not mounted and no single empty disk found (${cand[*]:-none}); set DATA_DISK or mount it by hand"

    disk=/dev/${cand[0]}
    if [[ $(lsblk -no NAME "$disk" | wc -l) -ne 1 ]] || blkid -p "$disk" > /dev/null 2>&1; then
        die "$disk has partitions or a filesystem, not touching it; mount it at $DATA_DIR by hand"
    fi
    read -rp "Format $disk ($(lsblk -dno SIZE "$disk")) as data disk for $DATA_DIR? [y/N] " answer < /dev/tty
    [[ $answer == [yY] ]] || die "aborted"

    parted "$disk" --script mklabel gpt mkpart data ext4 0% 100%
    udevadm settle
    part=$(lsblk -lnpo NAME "$disk" | sed -n 2p)
    mkfs.ext4 -q -L data "$part"
    uuid=$(blkid -s UUID -o value "$part")
    install -d "$DATA_DIR"
    echo "UUID=$uuid $DATA_DIR ext4 defaults,nofail 0 2" >> /etc/fstab
    systemctl daemon-reload
    mount "$DATA_DIR"
    log "data disk $part mounted at $DATA_DIR"
}

require_root
load_env
require_vars NC_DOMAIN ADMIN_USER LAN_CIDR DATA_DIR VPS_ENDPOINT ENTRA_TENANT_ID ENTRA_CLIENT_ID
NC_ADMIN_USER=${NC_ADMIN_USER:-ncadmin}
NC_SHARE_EXPIRE_DAYS=${NC_SHARE_EXPIRE_DAYS:-7}
NC_PHONE_REGION=${NC_PHONE_REGION:-DE}

log "packages"
setup_etckeeper
apt-get update -q > /dev/null
apt_install parted wireguard nftables nginx curl bzip2 gpg jq \
    php-fpm php-mysql php-gd php-curl php-mbstring php-xml php-zip php-intl php-gmp php-bcmath \
    php-apcu php-redis php-imagick mariadb-server redis-server
PHP_VER=$(php -r 'echo PHP_MAJOR_VERSION.".".PHP_MINOR_VERSION;')

log "data disk"
setup_data_disk

log "wireguard"
wg_keys
if [[ -z ${VPS_WG_PUBLIC_KEY:-} ]]; then
    warn "VPS_WG_PUBLIC_KEY not set, tunnel config skipped; run setup-vps.sh first and put its key in .env"
else
    wg_config <<CONF
# /etc/wireguard/wg-gw.conf on the pi, written by setup-pi.sh
[Interface]
Address = 10.99.0.2/24
PrivateKey = 

[Peer]
# vps
PublicKey = ${VPS_WG_PUBLIC_KEY}
Endpoint = ${VPS_ENDPOINT}:51820
AllowedIPs = 10.99.0.1/32
PersistentKeepalive = 25
CONF
fi

log "nftables"
tmp=$(mktemp)
sed "s|^define LAN = .*|define LAN = ${LAN_CIDR}|" "$REPO_DIR/configs/pi/etc/nftables.conf" > "$tmp"
setup_nftables "$tmp"
rm -f "$tmp"

log "mariadb"
if put "$REPO_DIR/configs/pi/etc/mysql/mariadb.conf.d/90-nextcloud.cnf" /etc/mysql/mariadb.conf.d/90-nextcloud.cnf; then
    systemctl restart mariadb
fi

log "redis on a unix socket"
sed -i -e 's/^port 6379/port 0/' \
       -e 's|^# unixsocket .*|unixsocket /run/redis/redis-server.sock|' \
       -e 's/^# unixsocketperm .*/unixsocketperm 770/' /etc/redis/redis.conf
id -nG www-data | grep -qw redis || usermod -aG redis www-data
grep -q '^apc.enable_cli=1' "/etc/php/$PHP_VER/mods-available/apcu.ini" \
    || echo 'apc.enable_cli=1' >> "/etc/php/$PHP_VER/mods-available/apcu.ini"
if etckeeper unclean; then
    systemctl restart redis-server "php$PHP_VER-fpm"
fi

log "nextcloud files"
if [[ ! -f $NC_DIR/occ ]]; then
    dl=$(mktemp -d)
    gnupg=$(mktemp -d)
    (
        cd "$dl"
        curl -fsSLO https://download.nextcloud.com/server/releases/latest.tar.bz2
        curl -fsSLO https://download.nextcloud.com/server/releases/latest.tar.bz2.sha256
        curl -fsSLO https://download.nextcloud.com/server/releases/latest.tar.bz2.asc
        curl -fsSLO https://nextcloud.com/nextcloud.asc
        sha256sum -c --ignore-missing --quiet latest.tar.bz2.sha256
        gpg --homedir "$gnupg" --quiet --import nextcloud.asc
        fpr=$(gpg --homedir "$gnupg" --with-colons --fingerprint | awk -F: '/^fpr/ {print $10; exit}')
        [[ $fpr == "$NC_GPG_FINGERPRINT" ]] || die "unexpected signing key $fpr"
        gpg --homedir "$gnupg" --quiet --verify latest.tar.bz2.asc latest.tar.bz2
        tar -xjf latest.tar.bz2 -C /var/www/
    )
    rm -rf "$dl" "$gnupg"
    chown -R www-data:www-data "$NC_DIR"
fi
install -d -m 0750 -o www-data -g www-data "$DATA_DIR/nextcloud-data"

log "certificate"
if [[ ! -f /etc/ssl/nginx/$NC_DOMAIN.crt ]]; then
    # self-signed for testing, the real certificate comes with go-live
    install -d -m 0755 /etc/ssl/nginx
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 \
        -subj "/CN=$NC_DOMAIN" -addext "subjectAltName=DNS:$NC_DOMAIN" \
        -keyout "/etc/ssl/nginx/$NC_DOMAIN.key" -out "/etc/ssl/nginx/$NC_DOMAIN.crt" 2> /dev/null
    chmod 600 "/etc/ssl/nginx/$NC_DOMAIN.key"
fi

log "nginx site"
rm -f /etc/nginx/sites-enabled/default
tmp=$(mktemp)
sed "s|php8\.4-fpm\.sock|php${PHP_VER}-fpm.sock|" "$REPO_DIR/configs/pi/etc/nginx/sites-available/nextcloud.conf" > "$tmp"
render "$tmp" /etc/nginx/sites-available/nextcloud.conf || true
rm -f "$tmp"
ln -sf /etc/nginx/sites-available/nextcloud.conf /etc/nginx/sites-enabled/nextcloud.conf
# self checks go over loopback, see docs/NOTES.md
grep -qE "^127\.0\.0\.1[[:space:]]+${NC_DOMAIN//./\\.}\$" /etc/hosts || echo "127.0.0.1 $NC_DOMAIN" >> /etc/hosts
nginx -t -q || die "nginx config invalid"
systemctl enable -q nginx
systemctl reload-or-restart nginx

log "nextcloud install"
if [[ $(occ status --output=json | jq -r .installed) != "true" ]]; then
    ask_secret NC_DB_PASSWORD "New password for the nextcloud database user"
    ask_secret NC_ADMIN_PASSWORD "New password for the local admin $NC_ADMIN_USER"
    db_pass=${NC_DB_PASSWORD//\'/\'\'}
    mariadb <<SQL
CREATE DATABASE IF NOT EXISTS nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'nextcloud'@'localhost' IDENTIFIED BY '${db_pass}';
ALTER USER 'nextcloud'@'localhost' IDENTIFIED BY '${db_pass}';
GRANT ALL PRIVILEGES ON nextcloud.* TO 'nextcloud'@'localhost';
FLUSH PRIVILEGES;
SQL
    unset db_pass
    # occ has no env option here, the passwords are visible in the process list for a moment
    occ maintenance:install --database mysql --database-name nextcloud --database-user nextcloud \
        --database-pass "$NC_DB_PASSWORD" --admin-user "$NC_ADMIN_USER" --admin-pass "$NC_ADMIN_PASSWORD" \
        --data-dir "$DATA_DIR/nextcloud-data"
    unset NC_DB_PASSWORD NC_ADMIN_PASSWORD
    FRESH_INSTALL=1
fi

log "nextcloud config"
occ config:system:set trusted_domains 0 --value="$NC_DOMAIN" > /dev/null
occ config:system:set overwrite.cli.url --value="https://$NC_DOMAIN" > /dev/null
occ config:system:set memcache.local --value='\OC\Memcache\APCu' > /dev/null
occ config:system:set memcache.locking --value='\OC\Memcache\Redis' > /dev/null
occ config:system:set memcache.distributed --value='\OC\Memcache\Redis' > /dev/null
occ config:system:set redis host --value='/run/redis/redis-server.sock' > /dev/null
occ config:system:set redis port --type=integer --value=0 > /dev/null
occ config:system:set default_phone_region --value="$NC_PHONE_REGION" > /dev/null
occ config:system:set maintenance_window_start --type=integer --value=1 > /dev/null
if [[ ${FRESH_INSTALL:-0} == 1 ]]; then
    occ maintenance:repair --include-expensive > /dev/null
fi

log "user_oidc"
if ! occ app:list --output=json | jq -e '.enabled.user_oidc' > /dev/null; then
    occ app:install user_oidc > /dev/null || occ app:enable user_oidc > /dev/null
fi
# created once with all options; no later update calls, an update without the secret
# may leave it empty (seen with a newer user_oidc than 8.13), and --clientsecret-env
# alone doesn't update anything
if ! occ user_oidc:provider | grep -qw Entra; then
    ask_secret NC_OIDC_SECRET "Entra client secret"
    export NC_OIDC_SECRET
    # login only with an nc- app role (ADR 0013), the role has to be assigned in entra already
    runuser -u www-data -- php "$NC_DIR/occ" user_oidc:provider Entra \
        --clientid="$ENTRA_CLIENT_ID" \
        --clientsecret-env=NC_OIDC_SECRET \
        --discoveryuri="https://login.microsoftonline.com/$ENTRA_TENANT_ID/v2.0/.well-known/openid-configuration" \
        --unique-uid=0 \
        --mapping-uid=oid \
        --group-provisioning=1 \
        --mapping-groups=roles \
        --group-whitelist-regex='/^nc-/' \
        --group-restrict-login-to-whitelist=1
    unset NC_OIDC_SECRET
fi
occ config:app:set --type=string --value=0 user_oidc allow_multiple_user_backends > /dev/null

log "nextcloud hardening"
occ group:list --output=json | jq -e 'has("local-2fa")' > /dev/null || occ group:add local-2fa > /dev/null
occ group:list --output=json | jq -e --arg u "$NC_ADMIN_USER" '.["local-2fa"] | index($u)' > /dev/null \
    || occ group:adduser local-2fa "$NC_ADMIN_USER"
occ twofactorauth:enforce --on --group=local-2fa > /dev/null
occ config:app:set core shareapi_enforce_links_password --value=yes > /dev/null
occ config:app:set core shareapi_default_expire_date --value=yes > /dev/null
occ config:app:set core shareapi_expire_after_n_days --value="$NC_SHARE_EXPIRE_DAYS" > /dev/null
occ config:app:set core shareapi_enforce_expire_date --value=yes > /dev/null
occ config:app:set core shareapi_allow_public_upload --value=no > /dev/null

log "automatic updates"
setup_unattended_upgrades pi

log "ssh"
setup_ssh pi

etckeeper_commit "setup-pi.sh"

echo
log "done"
echo "pi public key (VPS side: PI_WG_PUBLIC_KEY): $WG_OWN_PUBKEY"
echo
systemctl is-active nftables nginx mariadb redis-server "php$PHP_VER-fpm" wg-quick@wg-gw || true
wg show wg-gw latest-handshakes 2> /dev/null || true
occ status
curl -sk "https://$NC_DOMAIN/status.php"; echo
