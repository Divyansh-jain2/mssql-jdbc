# Approach: `sem-remove` — remove the semaphore; cache MSAL apps + coalesce + bound HTTP

## Summary
The most substantial redesign. It **removes the global `Semaphore(1)` entirely** and
replaces the per-call token-acquisition machinery with a layered, .NET-inspired design:
- **Layer A — per-credential MSAL application cache** (long-lived, reused across
  connections).
- **Layer B — single-flight coalescing** (one AAD call per credential per cold window).
- **Layer C — bounded HTTP timeouts** on MSAL's default HTTP client.

Both the **username/password** flow and the **service-principal** flow are rewritten.

## Problem this addresses
The baseline rebuilds a fresh MSAL application (and a fresh single-thread `ExecutorService`)
on **every** call, gates everything on one global semaphore, and leaves MSAL4J's HTTP
connect/read timeouts at their default of `0` (infinite) — so a hung AAD socket is only
bounded by the driver's own `Future.get(20s)`, which abandons the wait but does not cancel
the socket. This branch fixes all three at once.

## Files changed
Only one production file is modified.

| File | Change |
| --- | --- |
| `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerMSAL4JUtils.java` | +378 / −240 |

### `SQLServerMSAL4JUtils.java`

**Removed**
- `private static final Semaphore sem = new Semaphore(1);` — the global gate is gone.
- The per-call `ExecutorService executorService = Executors.newSingleThreadExecutor();`
  created at the top of each flow, and the per-call `sem.tryAcquire(...)` /
  `finally { sem.release(); }` scaffolding.

**Added — new static fields**
```java
// Layer C: bound MSAL4J's DefaultHttpClient (defaults are 0 = infinite).
static final int MSAL_HTTP_CONNECT_TIMEOUT_MS = Integer.getInteger("mssql.msal.httpConnectTimeoutMs", 10_000);
static final int MSAL_HTTP_READ_TIMEOUT_MS    = Integer.getInteger("mssql.msal.httpReadTimeoutMs", 30_000);

// Layer A: per-credential MSAL application caches (survive across connections).
private static final ConcurrentMap<String, ConfidentialClientApplication> CCA_CACHE = new ConcurrentHashMap<>();
private static final ConcurrentMap<String, PublicClientApplication>        PCA_CACHE = new ConcurrentHashMap<>();

// Layer B: per-key single-flight rendezvous (replaces the JVM-wide semaphore).
private static final ConcurrentMap<String, CompletableFuture<SqlAuthenticationToken>> IN_FLIGHT = new ConcurrentHashMap<>();

// Shared daemon executor for the cached MSAL applications (cannot shutdown a per-call executor when the app is cached).
private static final ExecutorService SHARED_MSAL_EXECUTOR = Executors.newCachedThreadPool(<daemon thread factory>);
```

**`getSqlFedAuthToken(...)` (username/password flow) — rewritten**
- Computes `hashedSecret` and populates the token-cache aspect up front.
- **Layer A:** `PCA_CACHE.computeIfAbsent("pwd:" + hashedSecret, ...)` builds a
  `PublicClientApplication` once (with `SHARED_MSAL_EXECUTOR`, the cache aspect, and the
  Layer-C connect/read timeouts) and reuses it thereafter.
- **Layer B:** `IN_FLIGHT.putIfAbsent(cacheKey, mine)` — a follower waits on the winner's
  future; the leader performs the real `acquireToken(UserNamePasswordParameters...)` once,
  completes its future, and removes the entry in `finally`.

**`getSqlFedAuthTokenPrincipal(...)` (service-principal flow) — rewritten**
- Same three layers, using `CCA_CACHE.computeIfAbsent("sp:" + hashedSecret, ...)` to build
  and cache a `ConfidentialClientApplication` via the `buildSpCca(...)` helper.
- **Layer B** single-flight identical in shape to the password flow (leader does the one
  real `acquireToken(ClientCredentialParameters...)`; followers join).

**New helper**
```java
// Builds a ConfidentialClientApplication with explicit HTTP connect/read timeouts.
private static ConfidentialClientApplication buildSpCca(ExecutorService executorService, String clientId,
        IClientCredential credential, PersistentTokenCacheAccessAspect aspect, String authority) throws MalformedURLException
```

## Behavioral contract
- **Correctness unchanged:** tokens are still keyed by `hashedSecret`; the persistent
  token-cache aspect is populated the same way.
- **Differences vs baseline:** no global serialization; MSAL application objects are reused
  instead of rebuilt per call; at most one AAD call per credential per cold window; and AAD
  sockets are bounded by explicit connect/read timeouts.

## Tunables
- `-Dmssql.msal.httpConnectTimeoutMs` (default `10000`)
- `-Dmssql.msal.httpReadTimeoutMs` (default `30000`)

## Relationship to other approaches
- `single-flight`: introduces coalescing but still builds the MSAL app per call and does
  not set HTTP timeouts.
- `sem-remove` (this branch): coalescing **+** cached MSAL apps **+** bounded HTTP timeouts,
  and removes the semaphore field entirely. It is the furthest departure from baseline.

## Build & verify
```powershell
$env:JAVA_HOME = 'C:\Users\t-divjain\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH      = "C:\Tools\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:PATH"
mvn -P jre11 -DskipTests compile
```
This branch compiles cleanly (`BUILD SUCCESS`) against the upstream `main` baseline.
