# Approach: `fast-per-sp-sem` — silent fast-path + per-service-principal semaphore

## Summary
Combines the two independent optimizations:
1. a **lock-free silent fast-path** (from `fast-cache`), and
2. a **per-service-principal semaphore gate** (from `per-sp-sem`).

Cache hits return with no locking; cache misses serialize only against **the same
principal**, never globally.

## Problem this addresses
The baseline (`main` / `driver-org`) serializes every token acquisition on one global
`Semaphore(1)`. That penalizes both (a) trivial in-memory cache hits and (b) unrelated
principals. This branch removes both penalties at once.

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +51 / −12 |

### `SQLServerMSAL4JUtils.java`
- **New field — per-principal semaphore registry:**
  ```java
  private static final ConcurrentHashMap<String, Semaphore> PER_SP_SEM = new ConcurrentHashMap<>();
  ```
- **`getSqlFedAuthTokenPrincipal(...)`:**
  - **Silent fast-path first:** builds the `ConfidentialClientApplication` and attempts
    `acquireTokenSilently(...)`. On a hit, returns immediately with **no semaphore
    acquired**. On a miss (`ExecutionException`), falls through.
  - **Per-principal gate on the slow path:** computes `hashedSecret` up front and gates on
    `PER_SP_SEM.computeIfAbsent(hashedSecret, k -> new Semaphore(1))` instead of the global
    `sem`. After the gate, MSAL's own cache check runs first, so callers that queued behind
    the winner ride the token the winner just cached instead of issuing a redundant AAD
    call.
  - The `finally` block releases the per-principal `spSem`.
- `SilentParameters` is already imported by the baseline — **no new imports**.

## Behavioral contract
- **Correctness unchanged:** same `hashedSecret` keying and cache population.
- **Two differences vs baseline:** (1) cache hits bypass the gate entirely; (2) the gate
  is partitioned per principal.

## Relationship to other approaches
- `fast-cache`: silent fast-path only (still a global gate on miss).
- `per-sp-sem`: per-principal gate only (no fast-path).
- `fast-per-sp-sem` (this branch): **both** combined.
- `fast-pooled-sem`: same idea but the per-principal registry is replaced by a bounded pool.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
