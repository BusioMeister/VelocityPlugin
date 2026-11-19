param(
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $MavenCommand = 'mvn'
)
$ErrorActionPreference = 'Stop'
$env:Path = "$env:ProgramFiles\Git\cmd;$env:Path"
$overlay = $PSScriptRoot
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $root) { throw "Use a new directory: $root" }
New-Item -ItemType Directory -Path $root | Out-Null

function Invoke-Checked([string] $Program, [string[]] $Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed: $($Arguments -join ' ')" }
}

function Get-PinnedSource([string] $Url, [string] $Commit, [string] $Path) {
    Invoke-Checked 'git' @('init', $Path)
    Invoke-Checked 'git' @('-C', $Path, 'fetch', '--depth', '1', $Url, $Commit)
    Invoke-Checked 'git' @('-C', $Path, 'switch', '--detach', 'FETCH_HEAD')
    if ((& git -C $Path rev-parse HEAD) -ne $Commit) { throw "Unexpected checkout in $Path" }
}

$velocity = Join-Path $root 'Velocity'
$instant = Join-Path $root 'InstantTransfer'
Get-PinnedSource 'https://github.com/PaperMC/Velocity.git' '300f5d0823691c8c8bace0c91714c63ab52e0b77' $velocity
Get-PinnedSource 'https://github.com/CodeAndCup/InstantTransfer.git' '74e1be2b0fb798b24e74ec9496f99baf8e7aa190' $instant
$upstream = Join-Path $instant 'velocity-patch/seamless.patch'
Invoke-Checked 'git' @('-C', $velocity, 'apply', '--exclude=*/StateRegistry.java', $upstream)
Invoke-Checked 'git' @('-C', $velocity, 'apply', (Join-Path $overlay 'instanttransfer-velocity-4.3.1-mc26.3-overlay.patch'))
Invoke-Checked 'git' @('-C', $instant, 'apply', (Join-Path $overlay 'instanttransfer-plugin-mc26.3-overlay.patch'))

Push-Location $velocity
try { Invoke-Checked '.\gradlew.bat' @('--no-daemon', ':velocity-proxy:shadowJar') }
finally { Pop-Location }
Push-Location $instant
try { Invoke-Checked $MavenCommand @('-B', '-ntp', '-Dvelocity.version=4.3.1', '-Dpacketevents.version=2.14.0', 'verify') }
finally { Pop-Location }

Write-Host "Proxy: $velocity\proxy\build\libs\velocity-proxy-4.3.1-all.jar"
Write-Host "Plugin: $instant\proxy-plugin\target\InstantTransfer-0.1.0.jar"
Write-Host "Agent: $instant\backend-agent\spigot\target\original-instanttransfer-agent-0.1.0.jar"
