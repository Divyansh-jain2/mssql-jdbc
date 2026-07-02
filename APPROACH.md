# Approach: `fast-pooled-sem` — silent fast-path + fixed-size semaphore pool

## Summary
Combines:
1. a **lock-free silent fast-path** (from `fast-cache`), and
2. a **fixed-size pool of semaphores** (from `pooled-sem`).

Cache hits return with no locking; cache misses serialize only against other credentials
mapped to the **same bounded pool slot**, and the number of semaphores is capped.

## Problem this addresses
This is the "best of both" variant: it removes the global-gate penalty for cache hits
(like `fast-cache`) **and** avoids both the global bottleneck and the unbounded-map growth
of `per-sp-sem` (like `pooled-sem`).

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +70 / −12 |

### `SQLServerMSAL4JUtils.java`
- **New fields / helpers — fixed semaphore pool:**
  ```java
  private static final int SEM_POOL_SIZE = 10;
  private static final Semaphore[] SEM_POOL = new Semaphore[SEM_POOL_SIZE];
  static { for (int i = 0; i < SEM_POOL_SIZE; i++) SEM_POOL[i] = new Semaphore(1); }
  private static int poolIndexForHashedSecret(String hashedSecret) { return (hashedSecret.hashCode() & 0x7fffffff) % SEM_POOL_SIZE; }
  private static Semaphore semaphoreForHashedSecret(String hashedSecret) { return SEM_POOL[poolIndexForHashedSecret(hashedSecret)]; }
  ```
- **`getSqlFedAuthTokenPrincipal(...)`:**
  - **Silent fast-path first:** attempts `acquireTokenSilently(...)`; a hit returns
    immediately with **no semaphore acquired**; a miss falls through.
  - **Pooled gate on the slow path:** gates on `semaphoreForHashedSecret(hashedSecret)`
    instead of the global `sem`. On a miss, `acquireToken` performs its own cache lookup
    first, so callers that waited on the gate ride the token the winner just cached rather
    than issuing a redundant AAD call.
  - The `finally` block releases the pooled `spSem`.
- `SilentParameters` is already imported by the baseline — **no new imports**.

## Correctness of slot sharing
Slot collisions between distinct principals only cause a little extra serialization; token
correctness comes entirely from the `hashedSecret`-keyed `TOKEN_CACHE_MAP`.

## Relationship to other approaches
- `fast-cache`: silent fast-path only.
- `pooled-sem`: fixed pool only.
- `fast-per-sp-sem`: fast-path + **unbounded** per-principal registry.
- `fast-pooled-sem` (this branch): fast-path + **bounded** pool.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
