# Approach: `per-sp-sem` — per-service-principal semaphore gate

## Summary
Replaces the single global `Semaphore(1)` with **one semaphore per service principal**,
keyed by the credential's `hashedSecret`. Concurrent callers for the **same** principal
still serialize (so the first populates the shared token cache), but **unrelated
principals never block one another**.

## Problem this addresses
In the baseline (`main` / `driver-org`), a single JVM-wide `Semaphore(1)` gates **all**
token acquisitions across **every** service principal. When many distinct principals
connect concurrently, they contend on one lock even though they share no token state —
an artificial global bottleneck.

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +20 / −6 |

### `SQLServerMSAL4JUtils.java`
- **New field — per-principal semaphore registry:**
  ```java
  private static final ConcurrentHashMap<String, Semaphore> PER_SP_SEM = new ConcurrentHashMap<>();
  ```
  The original global `private static final Semaphore sem = new Semaphore(1);` is left in
  place (unused by the principal flow) to minimize churn.
- **`getSqlFedAuthTokenPrincipal(...)`:**
  - The `hashedSecret` is now computed **before** acquiring the gate, because it is the
    key for both the semaphore registry and the `TOKEN_CACHE_MAP` — so the gate partition
    matches the cache partition exactly.
  - Replaces the global acquire:
    ```java
    isSemAcquired = sem.tryAcquire(...);
    ```
    with a per-principal acquire:
    ```java
    spSem = PER_SP_SEM.computeIfAbsent(hashedSecret, k -> new Semaphore(1));
    isSemAcquired = spSem.tryAcquire(...);
    ```
  - The `finally` block now releases `spSem` (the per-principal semaphore) instead of the
    global `sem`.
- The refresh timeout, endpoint-stampede protection, and cache population logic are all
  otherwise unchanged.

## Behavioral contract
- **Correctness unchanged:** same `hashedSecret` keying and cache population.
- **Only difference:** the gate is partitioned per principal, so distinct principals run
  in parallel while same-principal callers still coordinate.

## Trade-off note
`PER_SP_SEM` is an unbounded map — one `Semaphore` per distinct credential that is never
evicted. The `pooled-sem` variant addresses this by bounding the number of semaphores.

## Relationship to other approaches
- Baseline (`driver-org`): one global `Semaphore(1)`.
- `per-sp-sem` (this branch): one semaphore **per principal** (unbounded map).
- `pooled-sem`: a **fixed-size pool** of semaphores (bounded).
- `fast-per-sp-sem`: this gate **+** a silent fast-path.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
