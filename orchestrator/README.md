# Orchestrator (per-branch load generator + perf-log reporter)

A configurable load generator that lives inside this branch of
`Compilation/mssql-jdbc`. It drives the driver **built from the currently
checked-out branch** with real connection requests, then reports the driver's
**built-in performance-log metrics**: `TOKEN_ACQUISITION`, `CONNECTION`,
`PRELOGIN`, `LOGIN`.

This is the same harness used to compare all the token-acquisition approaches, so
you can check out any branch, build, and run to get that approach's numbers under
identical load.

## What it measures

`Orchestrator` registers a `PerformanceLogCallback` with the driver via
`SQLServerDriver.registerPerformanceLogCallback(...)` (nanosecond precision). Each
connection attempt makes the driver publish its internal timings; the harness
aggregates **count / avg / p50 / p95 / p99 / max** per activity. These are the
driver's own metrics — no external timing is involved.

## Configurable knobs

| Knob | Param | Meaning |
|---|---|---|
| Threads **K** | `-K` | number of worker threads generating load |
| Service principals | `-SpCount` | number of distinct Entra SPs to spread load across (1–100) |
| Workload type | `-Workload` | `burst`, `steady`, or `sticky` |
| Duration | `-DurationSec` | measured window length (seconds) |
| Cache mode | `-Mode` | `cold` (token stampede) or `warm` (prime then measure cache hits) |
| Burst gap | `-GapMs` | (burst only) pause between stampede waves |
| Sticky batch | `-StickyBatch` | (sticky only) connections per SP before switching |
| Hold time | `-HoldMs` | how long each connection is held before close |
| SP selection | `-SpSelect` | `random` or `roundrobin` |

### Workload shapes
- **burst** — all K threads synchronize on a barrier and fire one connection at
  the same instant, forming repeated stampede waves separated by `GapMs`. Models
  periodic cold token storms (the path gated by the driver's token machinery).
- **steady** — K threads continuously open/close connections back-to-back for the
  whole window (sustained throughput).
- **sticky** — like steady, but each thread reuses one SP for `StickyBatch`
  connections before switching (locality).

## Layout

```
orchestrator/
  src/Orchestrator.java   the harness (knobs + perf-log collector)
  src/Creds.java          service-principal list (bootstrapped, gitignored)
  scripts/build-orchestrator.ps1
  scripts/run-orchestrator.ps1
  lib/                    vendored runtime deps (bootstrapped, gitignored)
  target/classes/         compiled output (gitignored)
  reports/                per-run logs (gitignored)
```

## First-time bootstrap

The build makes the orchestrator self-contained by copying two things (both
gitignored):
- runtime dependency jars from `walmart-test/lib/common` → `orchestrator/lib/`
- `Creds.java` (the SP list; contains live secrets) from
  `walmart-test/src/main/java/Creds.java` → `orchestrator/src/`

The driver under test is `../target/mssql-jdbc-13.5.0.jre11-preview.jar`, built by
the build script from **this branch** via the Maven `jre11` profile.

## Quick start

```powershell
# 1. build this branch's driver jar (if needed) + compile the orchestrator
pwsh scripts/build-orchestrator.ps1

#    force a fresh driver rebuild after editing the driver source:
pwsh scripts/build-orchestrator.ps1 -RebuildDriver

# 2. run with defaults (K=20, 10 SPs, burst, 30s, cold)
pwsh scripts/run-orchestrator.ps1

# 3. examples
pwsh scripts/run-orchestrator.ps1 -K 40 -SpCount 20 -Workload burst  -DurationSec 30
pwsh scripts/run-orchestrator.ps1 -K 50 -SpCount 10 -Workload steady -DurationSec 60 -Mode warm
pwsh scripts/run-orchestrator.ps1 -K 30 -SpCount 10 -Workload sticky -StickyBatch 10
```

## Comparing branches

```powershell
git checkout fast-cache
pwsh orchestrator/scripts/build-orchestrator.ps1 -RebuildDriver
pwsh orchestrator/scripts/run-orchestrator.ps1 -Workload steady -K 50 -DurationSec 60

git checkout sem-remove
pwsh orchestrator/scripts/build-orchestrator.ps1 -RebuildDriver
pwsh orchestrator/scripts/run-orchestrator.ps1 -Workload steady -K 50 -DurationSec 60
```
Each run writes a timestamped log to `orchestrator/reports/<branch>-...log`.
Always `-RebuildDriver` after switching branches so `../target` holds that
branch's driver.

## Notes

- Real connections are opened against a serverless Azure SQL DB; a wake step
  resumes it before measuring. Keep `K` × `SpCount` modest on the free tier.
- The baseline (`driver-org`) has its own equivalent orchestrator under
  `driver-org/orchestrator/` for apples-to-apples comparison against `main`.
