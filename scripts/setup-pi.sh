#!/usr/bin/env bash
# sets up the pi: wireguard client, nftables, mariadb, redis, php, nextcloud with user_oidc, oauth2-proxy, portal, let's encrypt certificate, updates, ssh
# usage: sudo bash scripts/setup-pi.sh   (reads .env from the repo root, or ENV_FILE)
# secrets are asked for on the terminal when needed, they can also come from the environment
# safe to run again, see docs/Manual_Setup.md
# not for a pi that already runs nextcloud: data migration is a separate step

source "$(dirname "$0")/lib.sh"

NC_DIR=/var/www/nextcloud
NC_GPG_FINGERPRINT=${NC_GPG_FINGERPRINT:-28806A878AE423A28372792ED75899B9A724937A}
OAUTH2_PROXY_VERSION=${OAUTH2_PROXY_VERSION:-v7.15.6}
# sha256 of the release tarballs, from the *-sha256sum.txt files of the release
declare -A OAUTH2_PROXY_SHA256=(
    [amd64]=91b3e86d9c2de679a516e6696704e3fd1acf67e8b8f7be77b618f086f72fb1f0
    [arm64]=3593e50daa517eaa8f8d0966b95786a61a4cdf491694dc9091b8cd32daa85d56
)
PORTAL_DIST="$REPO_DIR/portal-dist"

occ() { runuser -u www-data -- php "$NC_DIR/occ" "$@"; }

# self-signed for tests, and a placeholder until the let's encrypt certificate is there
test_cert() {
    local domain=$1
    [[ -f /etc/ssl/nginx/$domain.crt ]] && return
    install -d -m 0755 /etc/ssl/nginx
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 \
        -subj "/CN=$domain" -addext "subjectAltName=DNS:$domain" \
        -keyout "/etc/ssl/nginx/$domain.key" -out "/etc/ssl/nginx/$domain.crt" 2> /dev/null
    chmod 600 "/etc/ssl/nginx/$domain.key"
    log "test certificate for $domain"
}

# system user without home and login, for the services
system_user() {
    id "$1" > /dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin "$1"
}

# enables and starts a unit, restarts it if something it uses changed
start_unit() {
    local unit=$1 changed=$2
    systemctl enable -q "$unit"
    if [[ $changed == 1 ]]; then
        systemctl restart "$unit"
    else
        systemctl start "$unit"
    fi
}

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
require_vars NC_DOMAIN ADMIN_USER LAN_CIDR DATA_DIR VPS_ENDPOINT ENTRA_TENANT_ID ENTRA_CLIENT_ID \
    PORTAL_DOMAIN PORTAL_CLIENT_ID
if [[ -n ${ACME_DOMAIN:-} ]]; then
    require_vars ACME_EMAIL
    # the wildcard covers exactly one label
    for name in "$NC_DOMAIN" "$PORTAL_DOMAIN"; do
        [[ $name == *."$ACME_DOMAIN" && ${name%."$ACME_DOMAIN"} != *.* ]] \
            || die "$name is not one label below ACME_DOMAIN=$ACME_DOMAIN"
    done
fi
NC_ADMIN_USER=${NC_ADMIN_USER:-ncadmin}
NC_SHARE_EXPIRE_DAYS=${NC_SHARE_EXPIRE_DAYS:-7}
NC_PHONE_REGION=${NC_PHONE_REGION:-DE}

log "packages"
setup_etckeeper
apt-get update -q > /dev/null
apt_install parted wireguard nftables nginx curl bzip2 gpg jq \
    php-fpm php-mysql php-gd php-curl php-mbstring php-xml php-zip php-intl php-gmp php-bcmath \
    php-apcu php-redis php-imagick mariadb-server redis-server openjdk-21-jre-headless
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

log "certificates"
test_cert "$NC_DOMAIN"
test_cert "$PORTAL_DOMAIN"
if [[ -n ${ACME_DOMAIN:-} ]]; then
    log "let's encrypt certificate"
    apt_install lego
    install -d -m 0700 /etc/lego /var/lib/lego
    if [[ ! -f /etc/lego/desec-token ]]; then
        ask_secret DESEC_TOKEN "deSEC token (TXT on _acme-challenge.acme.$ACME_DOMAIN only)"
        (umask 077 && printf '%s' "$DESEC_TOKEN" > /etc/lego/desec-token)
        unset DESEC_TOKEN
    fi
    tmp=$(mktemp)
    printf 'ACME_DOMAIN="%s"\nACME_EMAIL="%s"\nCERT_NAMES="%s %s"\n' \
        "$ACME_DOMAIN" "$ACME_EMAIL" "$NC_DOMAIN" "$PORTAL_DOMAIN" > "$tmp"
    put "$tmp" /etc/lego/homelab-cert.env 0600 || true
    rm -f "$tmp"
    put "$REPO_DIR/configs/pi/usr/local/sbin/homelab-cert" /usr/local/sbin/homelab-cert 0755 || true
    put "$REPO_DIR/configs/pi/usr/local/sbin/homelab-cert-deploy" /usr/local/sbin/homelab-cert-deploy 0755 || true
    cert_units=0
    put "$REPO_DIR/configs/pi/etc/systemd/system/homelab-cert.service" /etc/systemd/system/homelab-cert.service && cert_units=1
    put "$REPO_DIR/configs/pi/etc/systemd/system/homelab-cert.timer" /etc/systemd/system/homelab-cert.timer && cert_units=1
    if [[ $cert_units -eq 1 ]]; then
        systemctl daemon-reload
    fi
    systemctl enable -q --now homelab-cert.timer
    acme_crt="/var/lib/lego/certificates/_.$ACME_DOMAIN.crt"
    # the first certificate takes a few minutes (desec propagation), renewals are the timer's job
    if [[ ! -f $acme_crt ]]; then
        /usr/local/sbin/homelab-cert \
            || warn "no let's encrypt certificate, the test one stays (rerun /usr/local/sbin/homelab-cert to see why)"
    fi
    # certificate there but nginx still has another one, e.g. the hook failed
    if [[ -f $acme_crt ]] && ! cmp -s "$acme_crt" "/etc/ssl/nginx/$NC_DOMAIN.crt"; then
        /usr/local/sbin/homelab-cert-deploy
    fi
fi
# test only: the portal health check (java) has to trust the self-signed nextcloud cert;
# runs after the java install, the ca-certificates-java hook updates the java truststore
nc_ca="/usr/local/share/ca-certificates/homelab-test-$NC_DOMAIN.crt"
nc_crt="/etc/ssl/nginx/$NC_DOMAIN.crt"
nc_subject=$(openssl x509 -noout -subject -in "$nc_crt")
nc_issuer=$(openssl x509 -noout -issuer -in "$nc_crt")
if [[ ${nc_subject#subject=} == "${nc_issuer#issuer=}" ]]; then
    if put "$nc_crt" "$nc_ca"; then
        update-ca-certificates > /dev/null
    fi
elif [[ -f $nc_ca ]]; then
    # real certificate in place, the test one goes
    rm -f "$nc_ca"
    update-ca-certificates --fresh > /dev/null
    log "removed $nc_ca"
fi

log "nginx site"
rm -f /etc/nginx/sites-enabled/default
tmp=$(mktemp)
sed "s|php8\.4-fpm\.sock|php${PHP_VER}-fpm.sock|" "$REPO_DIR/configs/pi/etc/nginx/sites-available/nextcloud.conf" > "$tmp"
render "$tmp" /etc/nginx/sites-available/nextcloud.conf || true
rm -f "$tmp"
ln -sf /etc/nginx/sites-available/nextcloud.conf /etc/nginx/sites-enabled/nextcloud.conf
put "$REPO_DIR/configs/pi/etc/nginx/snippets/portal-headers.conf" /etc/nginx/snippets/portal-headers.conf || true
tmp=$(mktemp)
sed "s/<TENANT_ID>/${ENTRA_TENANT_ID}/" "$REPO_DIR/configs/pi/etc/nginx/sites-available/portal.conf" > "$tmp"
render "$tmp" /etc/nginx/sites-available/portal.conf || true
rm -f "$tmp"
ln -sf /etc/nginx/sites-available/portal.conf /etc/nginx/sites-enabled/portal.conf
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

log "oauth2-proxy"
system_user oauth2-proxy
proxy_changed=0
proxy_installed=$(/usr/local/bin/oauth2-proxy --version 2> /dev/null || true)
if [[ $proxy_installed != *"$OAUTH2_PROXY_VERSION"* ]]; then
    arch=$(dpkg --print-architecture)
    sha=${OAUTH2_PROXY_SHA256[$arch]:-}
    [[ -n $sha ]] || die "no oauth2-proxy checksum for $arch"
    name="oauth2-proxy-$OAUTH2_PROXY_VERSION.linux-$arch"
    dl=$(mktemp -d)
    curl -fsSL -o "$dl/$name.tar.gz" \
        "https://github.com/oauth2-proxy/oauth2-proxy/releases/download/$OAUTH2_PROXY_VERSION/$name.tar.gz"
    echo "$sha  $dl/$name.tar.gz" | sha256sum -c --quiet || die "checksum mismatch for $name.tar.gz"
    tar -xzf "$dl/$name.tar.gz" -C "$dl"
    install -m 0755 "$dl/$name/oauth2-proxy" /usr/local/bin/oauth2-proxy
    rm -rf "$dl"
    log "installed oauth2-proxy $OAUTH2_PROXY_VERSION"
    proxy_changed=1
fi
install -d -m 0750 -o root -g oauth2-proxy /etc/oauth2-proxy
if [[ ! -f /etc/oauth2-proxy/cookie-secret ]]; then
    # raw 32 bytes, what cookie_secret_file expects
    (umask 077 && head -c 32 /dev/urandom > /etc/oauth2-proxy/cookie-secret)
    proxy_changed=1
fi
if [[ ! -f /etc/oauth2-proxy/client-secret ]]; then
    ask_secret PORTAL_OIDC_SECRET "Entra client secret of the portal app"
    # no trailing newline, oauth2-proxy would read it as part of the secret
    (umask 077 && printf '%s' "$PORTAL_OIDC_SECRET" > /etc/oauth2-proxy/client-secret)
    unset PORTAL_OIDC_SECRET
    proxy_changed=1
fi
chgrp oauth2-proxy /etc/oauth2-proxy/cookie-secret /etc/oauth2-proxy/client-secret
chmod 0640 /etc/oauth2-proxy/cookie-secret /etc/oauth2-proxy/client-secret
tmp=$(mktemp)
sed -e "s/<TENANT_ID>/${ENTRA_TENANT_ID}/" -e "s/<CLIENT_ID>/${PORTAL_CLIENT_ID}/" \
    "$REPO_DIR/configs/pi/etc/oauth2-proxy/oauth2-proxy.cfg" > "$tmp"
render "$tmp" /etc/oauth2-proxy/oauth2-proxy.cfg 0640 && proxy_changed=1
rm -f "$tmp"
chgrp oauth2-proxy /etc/oauth2-proxy/oauth2-proxy.cfg
if put "$REPO_DIR/configs/pi/etc/systemd/system/oauth2-proxy.service" /etc/systemd/system/oauth2-proxy.service; then
    systemctl daemon-reload
    proxy_changed=1
fi
start_unit oauth2-proxy "$proxy_changed"

log "portal"
system_user homelab-portal
portal_changed=0
install -d -m 0750 -o root -g homelab-portal /etc/homelab-portal
tmp=$(mktemp)
printf 'ENTRA_TENANT_ID="%s"\nENTRA_CLIENT_ID="%s"\n' "$ENTRA_TENANT_ID" "$PORTAL_CLIENT_ID" > "$tmp"
put "$tmp" /etc/homelab-portal/portal.env 0640 && portal_changed=1
rm -f "$tmp"
chgrp homelab-portal /etc/homelab-portal/portal.env
render "$REPO_DIR/configs/pi/etc/homelab-portal/application.yml" /etc/homelab-portal/application.yml && portal_changed=1
if [[ -f $PORTAL_DIST/homelab-portal.jar ]]; then
    put "$PORTAL_DIST/homelab-portal.jar" /opt/homelab-portal/homelab-portal.jar && portal_changed=1
    if ! diff -rq "$PORTAL_DIST/browser" /var/www/portal > /dev/null 2>&1; then
        # swap the whole directory, angular file names change with every build
        rm -rf /var/www/portal.new
        cp -r "$PORTAL_DIST/browser" /var/www/portal.new
        chown -R root:root /var/www/portal.new
        chmod -R u=rwX,go=rX /var/www/portal.new
        rm -rf /var/www/portal
        mv /var/www/portal.new /var/www/portal
        log "updated /var/www/portal"
    fi
else
    warn "portal-dist missing, portal not deployed (deploy.ps1 builds it)"
fi
if put "$REPO_DIR/configs/pi/etc/systemd/system/homelab-portal.service" /etc/systemd/system/homelab-portal.service; then
    systemctl daemon-reload
    portal_changed=1
fi
# without a jar the unit would only restart in a loop
if [[ -f /opt/homelab-portal/homelab-portal.jar ]]; then
    start_unit homelab-portal "$portal_changed"
fi

log "automatic updates"
setup_unattended_upgrades pi

log "ssh"
setup_ssh pi

etckeeper_commit "setup-pi.sh"

echo
log "done"
echo "pi public key (VPS side: PI_WG_PUBLIC_KEY): $WG_OWN_PUBKEY"
echo
systemctl is-active nftables nginx mariadb redis-server "php$PHP_VER-fpm" wg-quick@wg-gw oauth2-proxy homelab-portal || true
if [[ -n ${ACME_DOMAIN:-} ]]; then
    systemctl is-active homelab-cert.timer || true
fi
openssl x509 -in "/etc/ssl/nginx/$NC_DOMAIN.crt" -noout -issuer -enddate
wg show wg-gw latest-handshakes 2> /dev/null || true
occ status
curl -sk "https://$NC_DOMAIN/status.php"; echo
curl -s -o /dev/null -w 'oauth2-proxy ping: %{http_code}\n' http://127.0.0.1:4180/ping || true
