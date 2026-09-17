[CmdletBinding()]
param([switch]$AcceptEula)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtimePath = Join-Path $repoRoot '.phase1-runtime'
if (-not $AcceptEula) { throw 'Read https://www.minecraft.net/eula and pass -AcceptEula only if you accept it.' }
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
$serverPath = Join-Path $runtimePath 'paper.jar'
$expectedHash = '274bbcb9807ad79a479617ce695a7c0686e652a8d1cabfd0e288d92a3ea29593'
if (-not (Test-Path -LiteralPath $serverPath)) {
    $downloadPath = Join-Path $runtimePath 'paper.download'
    Invoke-WebRequest -Uri "https://fill-data.papermc.io/v1/objects/$expectedHash/paper-26.2-124.jar" -UserAgent 'Papea2AC-Phase1-NativeHarness/1' -OutFile $downloadPath
    if ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Paper download checksum mismatch; download retained for inspection.' }
    Move-Item -LiteralPath $downloadPath -Destination $serverPath
}
if ((Get-FileHash -LiteralPath $serverPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Existing Paper JAR checksum mismatch.' }
$pluginPath = Join-Path $repoRoot 'anticheat-platform-paper/build/libs/anticheat-platform-paper-1.0-SNAPSHOT-plugin.jar'
if (-not (Test-Path -LiteralPath $pluginPath)) { throw 'Build :anticheat-platform-paper:pluginJar first.' }
$propertiesPath = Join-Path $runtimePath 'server.properties'
if (Test-Path -LiteralPath $propertiesPath) {
    $propertiesText = Get-Content -LiteralPath $propertiesPath -Raw
    if ($propertiesText -notmatch '(?m)^server-ip=127\.0\.0\.1\s*$' -or $propertiesText -notmatch '(?m)^server-port=25576\s*$') {
        throw 'Existing server is not the expected loopback endpoint; review configuration manually.'
    }
} else {
    @'
server-ip=127.0.0.1
server-port=25576
online-mode=true
enable-rcon=false
enable-query=false
level-seed=0
view-distance=4
simulation-distance=4
max-players=100
'@ | Set-Content -LiteralPath $propertiesPath -Encoding utf8NoBOM
}
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'plugins') -Force | Out-Null
Copy-Item -LiteralPath $pluginPath -Destination (Join-Path $runtimePath 'plugins/Papea2AC.jar')
"eula=true`n" | Set-Content -LiteralPath (Join-Path $runtimePath 'eula.txt') -Encoding utf8NoBOM
Write-Output "Prepared $runtimePath. Start with Java 25: java -Xms1G -Xmx4G -jar paper.jar --nogui"
