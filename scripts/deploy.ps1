# runs the whole setup from the PC: builds the portal, copies the repo files, runs setup-vps.sh
# and setup-pi.sh and passes the wireguard public keys between both hosts
# usage (from the repo): .\scripts\deploy.ps1 -VpsIp 192.168.178.38 -PiIp 192.168.178.169
# needs: Debian with SSH server and a sudo user on both hosts, .env filled in, key in ~/.ssh,
#        node and maven on the PC for the portal build
param(
    [Parameter(Mandatory = $true)][string]$VpsIp,
    [Parameter(Mandatory = $true)][string]$PiIp,
    # default: ADMIN_USER from .env
    [string]$User = '',
    [string]$PubKeyFile = "$env:USERPROFILE\.ssh\id_ed25519.pub",
    # take the existing build output instead of building the portal again
    [switch]$SkipPortalBuild
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path '.env')) { throw '.env missing, copy .env.example and fill it in' }
if (-not $User) {
    $m = Select-String -Path '.env' -Pattern '^ADMIN_USER="(.+)"' | Select-Object -First 1
    if (-not $m) { throw 'ADMIN_USER missing in .env' }
    $User = $m.Matches[0].Groups[1].Value
}
if (-not (Test-Path $PubKeyFile)) { throw "$PubKeyFile missing, create a key with ssh-keygen first" }

# accept-new: only for hosts we haven't seen yet, a changed key still fails
$sshOpts = @('-o', 'StrictHostKeyChecking=accept-new')

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

function Remote {
    param([string]$Ip, [string]$Cmd, [switch]$Tty)
    $a = @() + $sshOpts
    if ($Tty) { $a += '-t' }
    $a += "$User@$Ip", $Cmd
    & ssh.exe @a
    if ($LASTEXITCODE -ne 0) { throw "ssh $Ip failed: $Cmd" }
}

function KeyLoginWorks($ip) {
    # windows powershell turns stderr of native commands into errors, so not with 'Stop'
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & ssh.exe @sshOpts -o BatchMode=yes -o ConnectTimeout=5 "$User@$ip" true 2> $null
    $ok = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $old
    return $ok
}

function Prepare($ip, [string[]]$Extra = @()) {
    # key login first, asks for the password only once per host
    if (-not (KeyLoginWorks $ip)) {
        Step "installing ssh key on $ip (password of $User)"
        Get-Content $PubKeyFile | & ssh.exe @sshOpts "$User@$ip" 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
        if ($LASTEXITCODE -ne 0) { throw "could not install the key on $ip" }
    }
    Step "copying files to $ip"
    Remote $ip 'rm -rf ~/homelab-gateway && mkdir -p ~/homelab-gateway'
    & scp.exe @sshOpts -q -r configs scripts .env @Extra "${User}@${ip}:homelab-gateway/"
    if ($LASTEXITCODE -ne 0) { throw "scp to $ip failed" }
}

function PubKey($ip) { (& ssh.exe @sshOpts "$User@$ip" 'cat ~/homelab-gateway/wg-gw.pub').Trim() }

if (-not $SkipPortalBuild) {
    Step 'building the portal'
    Push-Location portal\frontend
    try {
        npm ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        npm run build; if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    } finally { Pop-Location }
    Push-Location portal\backend
    try {
        # clean, an old version's jar in target would make two
        mvn -q clean package; if ($LASTEXITCODE -ne 0) { throw 'backend build failed' }
    } finally { Pop-Location }
}

# git-ignored, setup-pi.sh takes it from ~/homelab-gateway/portal-dist
Step 'collecting the portal build in portal-dist'
$jars = @(Get-ChildItem 'portal\backend\target' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'homelab-portal-*.jar' })
if ($jars.Count -ne 1) { throw "expected one homelab-portal-*.jar in portal\backend\target, found $($jars.Count)" }
$browser = 'portal\frontend\dist\portal\browser'
if (-not (Test-Path "$browser\index.html")) { throw "$browser\index.html missing, build the frontend first" }
if (Test-Path 'portal-dist') { Remove-Item -Recurse -Force 'portal-dist' }
New-Item -ItemType Directory 'portal-dist' | Out-Null
Copy-Item $jars[0].FullName 'portal-dist\homelab-portal.jar'
Copy-Item -Recurse $browser 'portal-dist\browser'

Prepare $VpsIp
Prepare $PiIp 'portal-dist'

Step 'vps, first run (sudo password)'
Remote $VpsIp 'sudo bash ~/homelab-gateway/scripts/setup-vps.sh' -Tty
$vpsKey = PubKey $VpsIp

Step 'pi (sudo password, then the secrets)'
Remote $PiIp "sudo env VPS_ENDPOINT=$VpsIp VPS_WG_PUBLIC_KEY=$vpsKey bash ~/homelab-gateway/scripts/setup-pi.sh" -Tty
$piKey = PubKey $PiIp

Step 'vps, second run with the pi key (sudo password)'
Remote $VpsIp "sudo env PI_WG_PUBLIC_KEY=$piKey bash ~/homelab-gateway/scripts/setup-vps.sh" -Tty

Step 'cleanup'
Remote $VpsIp 'rm -f ~/homelab-gateway/.env'
Remote $PiIp 'rm -f ~/homelab-gateway/.env'

Write-Host "vps public key: $vpsKey"
Write-Host "pi public key:  $piKey"
Write-Host "`nDone. NC_DOMAIN and PORTAL_DOMAIN have to point to $VpsIp (DNS A records, or the hosts file for .test names), then set up TOTP for the local admin (/login?direct=1)."
