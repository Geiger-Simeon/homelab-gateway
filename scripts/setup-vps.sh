#!/usr/bin/env bash
# sets up the vps: wireguard hub, nftables, nginx stream passthrough, updates, ssh
# usage: sudo bash scripts/setup-vps.sh   (reads .env from the repo root, or ENV_FILE)
# safe to run again; run it twice on a fresh setup, see docs/Manual_Setup.md

source "$(dirname "$0")/lib.sh"

require_root
load_env
require_vars NC_DOMAIN PORTAL_DOMAIN ADMIN_USER

log "packages"
setup_etckeeper
apt-get update -q > /dev/null
apt_install wireguard nftables nginx libnginx-mod-stream curl

log "wireguard"
wg_keys
if [[ -z ${PI_WG_PUBLIC_KEY:-} ]]; then
    warn "PI_WG_PUBLIC_KEY not set, tunnel config skipped; run setup-pi.sh, put its key in .env, run this again"
else
    wg_config <<CONF
# /etc/wireguard/wg-gw.conf on the vps, written by setup-vps.sh
[Interface]
Address = 10.99.0.1/24
ListenPort = 51820
PrivateKey = 

[Peer]
# pi
PublicKey = ${PI_WG_PUBLIC_KEY}
AllowedIPs = 10.99.0.2/32
CONF
fi

log "nftables"
setup_nftables "$REPO_DIR/configs/vps/etc/nftables.conf"

log "nginx stream"
rm -f /etc/nginx/sites-enabled/default
install -d -m 0755 /etc/nginx/stream.d
if ! grep -q '^include /etc/nginx/stream.d/\*.conf;' /etc/nginx/nginx.conf; then
    # has to be in the main context, after the http block
    echo 'include /etc/nginx/stream.d/*.conf;' >> /etc/nginx/nginx.conf
fi
tmp=$(mktemp)
if [[ -n ${ACME_DOMAIN:-} ]]; then
    sed "s/^\([[:space:]]*\)homelab\.test /\1${ACME_DOMAIN} /" "$REPO_DIR/configs/vps/etc/nginx/stream.d/gateway.conf" > "$tmp"
else
    sed '/^[[:space:]]*homelab\.test /d; /the bare domain (ACME_DOMAIN)/d' "$REPO_DIR/configs/vps/etc/nginx/stream.d/gateway.conf" > "$tmp"
fi
render "$tmp" /etc/nginx/stream.d/gateway.conf || true
rm -f "$tmp"
nginx -t -q || die "nginx config invalid"
systemctl enable -q nginx
systemctl reload-or-restart nginx

log "automatic updates"
setup_unattended_upgrades vps

log "ssh"
setup_ssh vps

etckeeper_commit "setup-vps.sh"

echo
log "done"
echo "vps public key (PI side: VPS_WG_PUBLIC_KEY): $WG_OWN_PUBKEY"
echo
systemctl is-active nftables nginx wg-quick@wg-gw || true
if [[ -n ${PI_WG_PUBLIC_KEY:-} ]]; then
    # the pi retries every few seconds, give it a moment
    for _ in $(seq 1 30); do
        [[ $(wg show wg-gw latest-handshakes | awk '{print $2}') != 0 ]] && break
        sleep 1
    done
    wg show wg-gw latest-handshakes
fi
sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|allowusers|maxauthtries) '
