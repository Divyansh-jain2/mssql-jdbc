# Approach: `fast-cache` — lock-free silent fast-path before the token semaphore

## Summary
Adds a **lock-free silent token read** in front of the existing global `Semaphore(1)`
gate. Threads that can be satisfied from the MSAL in-memory token cache return
immediately **without touching the semaphore**, so cache hits never queue behind a
thread that is refreshing a token.

## Problem this addresses
In the baseline (`main` / `driver-org`), **every** service-principal token acquisition
serializes on a single JVM-wide `Semaphore(1)`. Under a burst of connections this makes
even trivial in-memory cache hits wait behind whichever thread currently holds the
permit, turning a fast local read into a head-of-line-blocked operation.

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +40 / −11 |

### `SQLServerMSAL4JUtils.java` — `getSqlFedAuthTokenPrincipal(...)`
- **Adds a silent fast-path.** Before acquiring the semaphore, the method now calls
  `clientApplication.acquireTokenSilently(SilentParameters.builder(scopes).build())`.
  - On a **cache hit** it returns a `SqlAuthenticationToken` right away — the global
    semaphore is never acquired.
  - On a cache **miss** (`ExecutionException`), it falls through to the original gated
    slow path unchanged.
- The `ConfidentialClientApplication` is built once and reused for both the silent
  attempt and the gated acquisition.
- The existing semaphore acquire/`tryAcquire` + `finally { sem.release(); }` slow path
  is preserved exactly, so refresh behavior and endpoint-stampede protection are
  unchanged for cache misses.
- `SilentParameters` is already imported by the baseline, so **no new imports** are
  required.

## Behavioral contract
- **Correctness unchanged:** tokens are still keyed by the same `hashedSecret`, and the
  persistent token cache aspect is populated exactly as before.
- **Only difference:** a cache-hit path that bypasses the gate. A miss behaves
  identically to baseline.

## Relationship to other approaches
- Baseline (`driver-org`): global `Semaphore(1)`, no fast-path.
- `fast-cache` (this branch): global semaphore **+** silent fast-path.
- `fast-per-sp-sem`: silent fast-path **+** per-service-principal gate.
- `fast-pooled-sem`: silent fast-path **+** fixed-size pool of gates.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
