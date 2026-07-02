# ============================================================================
# build-orchestrator.ps1 — build THIS branch's driver jar (if needed) and
#   compile the Orchestrator load generator against it.
#
# Runs from inside <repo>/orchestrator on any checked-out branch of
# Compilation/mssql-jdbc. Self-contained after a one-time bootstrap:
#   * driver : ../target/mssql-jdbc-13.5.0.jre11-preview.jar — built here via
#              Maven (jre11 profile) from the currently checked-out branch.
#   * deps   : orchestrator/lib/*.jar — runtime dependencies (MSAL, Azure,
#              Netty, ...). Bootstrapped once from walmart-test/lib/common.
#   * creds  : orchestrator/src/Creds.java — the service-principal list.
#              Bootstrapped once from walmart-test/src/main/java/Creds.java
#              (gitignored; contains live secrets).
#
# Usage:
#   pwsh scripts/build-orchestrator.ps1              # build driver if missing, then compile
#   pwsh scripts/build-orchestrator.ps1 -RebuildDriver   # force-rebuild the driver jar
# ============================================================================

param(
    [switch] $RebuildDriver
)

$ErrorActionPreference = 'Stop'

# --- Toolchain ---------------------------------------------------------------
$jdk = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
if (Test-Path $jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH      = "$jdk\bin;$env:PATH"
}
$mvnBin = 'C:\Tools\apache-maven-3.9.9\bin'
if (Test-Path $mvnBin) {
    $env:PATH = "$mvnBin;$env:PATH"
}

$orchRoot   = Split-Path -Parent $PSScriptRoot          # <repo>/orchestrator
$driverRoot = Split-Path -Parent $orchRoot              # <repo> (mssql-jdbc)
$workspace  = Split-Path -Parent (Split-Path -Parent $driverRoot)  # Q:\Workspace

$srcDir     = Join-Path $orchRoot 'src'
$classesDir = Join-Path $orchRoot 'target\classes'
$libDir     = Join-Path $orchRoot 'lib'
$driverJar  = Join-Path $driverRoot 'target\mssql-jdbc-13.5.0.jre11-preview.jar'

# Report which branch we're building for (label only).
$branch = (& git -C $driverRoot rev-parse --abbrev-ref HEAD 2>$null)
Write-Host "[build] branch under test: $branch"

# --- Build the driver jar for this branch (if missing or forced) ------------
if ($RebuildDriver -or -not (Test-Path $driverJar)) {
    Write-Host "[build] Building driver jar via Maven (jre11 profile)... this can take a few minutes."
    Push-Location $driverRoot
    try {
        & mvn -P jre11 -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "driver Maven build failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $driverJar)) {
    throw "driver jar still not found after build: $driverJar"
}
Write-Host "[build] driver jar: $driverJar"

# --- Bootstrap dependency jars (one time) -----------------------------------
$srcCommon = Join-Path $workspace 'walmart-test\lib\common'
if (-not (Test-Path $libDir) -or -not (Get-ChildItem $libDir -Filter *.jar -EA SilentlyContinue)) {
    if (-not (Test-Path $srcCommon)) {
        throw "Dependency source not found: $srcCommon. Cannot bootstrap orchestrator/lib."
    }
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    Copy-Item (Join-Path $srcCommon '*.jar') $libDir -Force
    Write-Host "[bootstrap] copied $((Get-ChildItem $libDir -Filter *.jar).Count) dependency jars -> orchestrator/lib"
}

# --- Bootstrap Creds.java (one time) ----------------------------------------
$credsDst = Join-Path $srcDir 'Creds.java'
if (-not (Test-Path $credsDst)) {
    $credsSrc = Join-Path $workspace 'walmart-test\src\main\java\Creds.java'
    if (-not (Test-Path $credsSrc)) {
        throw "Creds.java not found: $credsSrc. Cannot bootstrap orchestrator/src/Creds.java."
    }
    New-Item -ItemType Directory -Force -Path $srcDir | Out-Null
    Copy-Item $credsSrc $credsDst -Force
    Write-Host "[bootstrap] copied Creds.java -> orchestrator/src (gitignored; live secrets)"
}

# --- Compile the orchestrator -----------------------------------------------
$cpEntries = @($driverJar) + (Get-ChildItem $libDir -Filter *.jar | Select-Object -ExpandProperty FullName)
$compileCp = ($cpEntries -join ';')

New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$sources = Get-ChildItem $srcDir -Filter *.java | Select-Object -ExpandProperty FullName
Write-Host "[build] Compiling $($sources.Count) source file(s) -> $classesDir"
& javac --release 11 -encoding UTF-8 -cp $compileCp -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }
Write-Host "[build] OK -> $classesDir"
