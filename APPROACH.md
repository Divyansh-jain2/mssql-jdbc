# Approach: `single-flight` — per-credential request coalescing

## Summary
Replaces the global `Semaphore(1)` with **single-flight (request coalescing)**. The first
caller for a given credential becomes the **leader** and performs the real AAD acquisition
on a shared future; concurrent callers for the **same** credential become **followers** and
join that same future, receiving the leader's token instead of merely being granted
permission to retry.

## Problem this addresses
The baseline semaphore only lets one thread *attempt* acquisition at a time — followers
that time out on the gate then go and issue their **own** AAD calls, so a cold burst can
still produce many concurrent token-endpoint hits. Coalescing guarantees **at most one
in-flight AAD call per credential**, and every waiter benefits from the single result.

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +123 / −27 |

### `SQLServerMSAL4JUtils.java`
- **New import:**
  ```java
  import java.util.concurrent.CompletionException;
  ```
- **New fields — in-flight registry + executor:**
  ```java
  // First caller installs a shared future; concurrent callers for the same credential join it.
  private static final ConcurrentHashMap<String, CompletableFuture<SqlAuthenticationToken>> INFLIGHT_TOKENS = new ConcurrentHashMap<>();

  // Daemon pool that runs the leader futures, independent of any single caller's per-connection executor.
  private static final ExecutorService COALESCE_EXEC = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "mssql-msal-coalesce");
      t.setDaemon(true);
      return t;
  });
  ```
  The original global `sem` field is left in place (unused by the principal flow).
- **`getSqlFedAuthTokenPrincipal(...)` — rewritten to three stages:**
  1. **Fast path:** `acquireTokenSilently(...)` with no coordination; a cache hit returns
     immediately.
  2. **Coalesced slow path:** `INFLIGHT_TOKENS.computeIfAbsent(hashedSecret, ...)` — the
     lambda fires exactly once and designates the **leader**, which runs a
     `CompletableFuture.supplyAsync(..., COALESCE_EXEC)` that (a) does one more silent
     double-check, then (b) performs the real `acquireToken(...)`. The leader uses its own
     `ExecutorService` so its AAD trip is not tied to any single caller's per-connection
     executor lifetime.
  3. **Join:** every caller (leader or follower) blocks on the same future under **its own**
     `millisecondsRemaining` deadline, so a slow leader never pins a follower past that
     follower's own login timeout. The leader deregisters the map entry via
     `whenComplete(...)` so the next cold call re-leaders cleanly.
- **Error propagation:** the leader wraps failures in `CompletionException`; the join site
  unwraps the cause and routes `TimeoutException` / other exceptions through the same
  `getCorrectedException(...)` paths the baseline used.

## Behavioral contract
- **Correctness unchanged:** same `hashedSecret` keying and cache population.
- **Key difference vs baseline:** at most one AAD call per credential per cold window, and
  followers receive the actual token rather than a retry opportunity.

## Relationship to other approaches
- Semaphore-based branches (`per-sp-sem`, `pooled-sem`, ...) *gate* attempts.
- `single-flight` (this branch) *coalesces* attempts into one shared result.
- `sem-remove` builds on this idea and additionally caches the MSAL application object and
  applies HTTP timeouts.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
