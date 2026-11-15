# shared helpers for setup-vps.sh and setup-pi.sh, sourced, not run

set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

require_root() {
    [[ $EUID -eq 0 ]] || die "run with sudo"
}

# values already in the environment win over .env (deploy.ps1 passes ips and keys that way)
load_env() {
    local env_file="${ENV_FILE:-$REPO_DIR/.env}" name
    local -A keep=()
    [[ -f $env_file ]] || die "$env_file missing, copy .env.example and fill it in"
    for name in $(tr -d '\r' < "$env_file" | grep -oE '^[A-Z_][A-Z0-9_]*='); do
        name=${name%=}
        [[ -n ${!name:-} ]] && keep[$name]=${!name}
    done
    set -a
    # strip CR, the file is usually edited on windows
    # shellcheck disable=SC1090
    . <(tr -d '\r' < "$env_file")
    set +a
    for name in "${!keep[@]}"; do
        printf -v "$name" '%s' "${keep[$name]}"
        export "${name?}"
    done
}

require_vars() {
    local v
    for v in "$@"; do
        [[ -n ${!v:-} ]] || die "$v is not set in .env"
    done
}

# asks for a secret only if it isn't in the environment already
# input is visible on purpose, pasting blind over ssh went wrong too often
ask_secret() {
    local var=$1 prompt=$2 value
    if [[ -z ${!var:-} ]]; then
        read -rp "$prompt: " value < /dev/tty
        # drop CR and surrounding spaces from pasting
        value=${value//$'\r'/}
        value=${value#"${value%%[![:space:]]*}"}
        value=${value%"${value##*[![:space:]]}"}
        [[ -n $value ]] || die "$var must not be empty"
        printf -v "$var" '%s' "$value"
    fi
}

apt_install() {
    apt-get install -y -q "$@" > /dev/null
}

# copy a file only if it differs, returns 0 if something changed
put() {
    local src=$1 dst=$2 mode=${3:-0644}
    if [[ -f $dst ]] && cmp -s "$src" "$dst"; then
        return 1
    fi
    install -D -m "$mode" -o root -g root "$src" "$dst"
    log "updated $dst"
    return 0
}

# copy a repo config with the test values replaced, returns 0 if something changed
render() {
    local src=$1 dst=$2 mode=${3:-0644} tmp
    tmp=$(mktemp)
    sed -e "s/cloud\.homelab\.test/${NC_DOMAIN}/g" \
        -e "s/AllowUsers admin/AllowUsers ${ADMIN_USER}/" \
        "$src" > "$tmp"
    if put "$tmp" "$dst" "$mode"; then rm -f "$tmp"; return 0; fi
    rm -f "$tmp"
    return 1
}

setup_etckeeper() {
    if ! command -v etckeeper > /dev/null; then
        log "installing etckeeper"
        apt_install etckeeper
    fi
}

etckeeper_commit() {
    if etckeeper unclean; then
        etckeeper commit "$1" > /dev/null
        log "etckeeper: $1"
    fi
}

# own key pair for wg-gw, the private key never leaves the host
wg_keys() {
    if [[ ! -f /etc/wireguard/wg-gw.key ]]; then
        log "generating wireguard keys"
        (umask 077 && wg genkey > /etc/wireguard/wg-gw.key)
        wg pubkey < /etc/wireguard/wg-gw.key > /etc/wireguard/wg-gw.pub
    fi
    # shellcheck disable=SC2034  # used by the setup scripts
    WG_OWN_PUBKEY=$(cat /etc/wireguard/wg-gw.pub)
    # /etc/wireguard is root only, deploy.ps1 reads the public key from here
    install -m 0644 /etc/wireguard/wg-gw.pub "$REPO_DIR/wg-gw.pub"
}

# writes wg-gw.conf from stdin (without the PrivateKey line) and (re)starts the tunnel
wg_config() {
    local tmp
    tmp=$(mktemp)
    chmod 600 "$tmp"
    sed "s|^PrivateKey = .*|PrivateKey = $(cat /etc/wireguard/wg-gw.key)|" > "$tmp"
    if put "$tmp" /etc/wireguard/wg-gw.conf 0600; then
        systemctl enable -q wg-quick@wg-gw
        systemctl restart wg-quick@wg-gw
    else
        systemctl enable -q --now wg-quick@wg-gw
    fi
    rm -f "$tmp"
}

setup_nftables() {
    local src=$1
    nft -c -f "$src" || die "nftables syntax check failed for $src"
    if put "$src" /etc/nftables.conf; then
        nft -f /etc/nftables.conf
    fi
    systemctl enable -q --now nftables
}

setup_unattended_upgrades() {
    local host=$1
    apt_install unattended-upgrades
    put "$REPO_DIR/configs/$host/etc/apt/apt.conf.d/20auto-upgrades" /etc/apt/apt.conf.d/20auto-upgrades || true
    put "$REPO_DIR/configs/$host/etc/apt/apt.conf.d/52unattended-upgrades-local" /etc/apt/apt.conf.d/52unattended-upgrades-local || true
}

# key login has to work before password login goes away
setup_ssh() {
    local host=$1 home keys
    home=$(getent passwd "$ADMIN_USER" | cut -d: -f6)
    [[ -n $home ]] || die "user $ADMIN_USER doesn't exist"
    keys="$home/.ssh/authorized_keys"

    if [[ -n ${ADMIN_SSH_PUBKEY:-} ]] && ! grep -qF "$ADMIN_SSH_PUBKEY" "$keys" 2> /dev/null; then
        install -d -m 700 -o "$ADMIN_USER" -g "$ADMIN_USER" "$home/.ssh"
        echo "$ADMIN_SSH_PUBKEY" >> "$keys"
        chown "$ADMIN_USER:$ADMIN_USER" "$keys"
        chmod 600 "$keys"
        log "added ADMIN_SSH_PUBKEY to $keys"
    fi

    if ! grep -qE '^(ssh-|ecdsa-|sk-)' "$keys" 2> /dev/null; then
        warn "no key in $keys, sshd hardening skipped (password login stays on)"
        return
    fi

    if render "$REPO_DIR/configs/$host/etc/ssh/sshd_config.d/10-hardening.conf" /etc/ssh/sshd_config.d/10-hardening.conf; then
        sshd -t || die "sshd config invalid"
        systemctl reload ssh
    fi
}
