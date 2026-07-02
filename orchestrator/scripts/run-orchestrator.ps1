# ============================================================================
# run-orchestrator.ps1 — run the Orchestrator load generator against THIS
#   branch's driver and print the driver's built-in perf-log metrics
#   (TOKEN_ACQUISITION, CONNECTION, PRELOGIN, LOGIN).
#
# Knobs:
#   -K            number of executor (worker) threads
#   -SpCount      number of distinct service principals to spread load across
#   -Workload     burst | steady | sticky
#   -DurationSec  measured window length
#   -Mode         cold | warm
#   -GapMs        (burst) gap between stampede waves
#   -StickyBatch  (sticky) connections per SP before switching
#   -HoldMs       how long each connection is held before close
#   -SpSelect     random | roundrobin
#
# Self-contained: uses ONLY ../target driver jar + orchestrator/lib + classes.
#
# Usage:
#   pwsh scripts/run-orchestrator.ps1                              # defaults
#   pwsh scripts/run-orchestrator.ps1 -K 40 -SpCount 20 -Workload burst -DurationSec 30
#   pwsh scripts/run-orchestrator.ps1 -Workload steady -K 50 -DurationSec 60 -Mode warm
#   pwsh scripts/run-orchestrator.ps1 -Workload sticky -K 30 -SpCount 10 -StickyBatch 10
# ============================================================================

param(
    [int]    $K           = 20,
    [int]    $SpCount     = 10,
    [ValidateSet('burst', 'steady', 'sticky')]
    [string] $Workload    = 'burst',
    [int]    $DurationSec  = 30,
    [ValidateSet('cold', 'warm')]
    [string] $Mode        = 'cold',
    [int]    $GapMs       = 200,
    [int]    $StickyBatch = 10,
    [int]    $HoldMs      = 0,
    [ValidateSet('random', 'roundrobin')]
    [string] $SpSelect    = 'random',
    [string] $Xmx         = '512m'
)

$ErrorActionPreference = 'Stop'

$jdk = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
if (Test-Path $jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH      = "$jdk\bin;$env:PATH"
}

$orchRoot   = Split-Path -Parent $PSScriptRoot
$driverRoot = Split-Path -Parent $orchRoot
$classesDir = Join-Path $orchRoot 'target\classes'
$libDir     = Join-Path $orchRoot 'lib'
$reports    = Join-Path $orchRoot 'reports'
$driverJar  = Join-Path $driverRoot 'target\mssql-jdbc-13.5.0.jre11-preview.jar'
New-Item -ItemType Directory -Force -Path $reports | Out-Null

if (-not (Test-Path (Join-Path $classesDir 'Orchestrator.class'))) {
    throw "Orchestrator not compiled. Run: pwsh scripts/build-orchestrator.ps1"
}
if (-not (Test-Path $driverJar)) {
    throw "driver jar not found: $driverJar  (run: pwsh scripts/build-orchestrator.ps1)"
}

$branch = (& git -C $driverRoot rev-parse --abbrev-ref HEAD 2>$null)
if (-not $branch) { $branch = 'under-test' }

$cpEntries = @($classesDir, $driverJar) + (Get-ChildItem $libDir -Filter *.jar | Select-Object -ExpandProperty FullName)
$runCp     = ($cpEntries -join ';')

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log   = Join-Path $reports "$branch-$Workload-K$K-sp$SpCount-$Mode-$stamp.log"

Write-Host "============================================================"
Write-Host " Orchestrator  driver=$branch  K=$K  SPs=$SpCount  workload=$Workload  duration=${DurationSec}s  mode=$Mode  spSelect=$SpSelect"
Write-Host "============================================================"

$jvmArgs = @(
    "-Xmx$Xmx",
    "-Dorchestrator.driver=$branch",
    '-cp', $runCp,
    'Orchestrator', "$K", "$SpCount", $Workload, "$DurationSec", $Mode, "$GapMs", "$StickyBatch", "$HoldMs", $SpSelect
)

& java @jvmArgs 2>&1 | Tee-Object -FilePath $log
Write-Host ""
Write-Host "[log] $log"
