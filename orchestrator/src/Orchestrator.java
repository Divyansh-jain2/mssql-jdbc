import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.microsoft.sqlserver.jdbc.PerformanceActivity;
import com.microsoft.sqlserver.jdbc.PerformanceLogCallback;
import com.microsoft.sqlserver.jdbc.SQLServerDriver;

/**
 * Orchestrator — a configurable load generator that drives the JDBC driver built
 * from THIS branch with real connection requests and reports the driver's
 * BUILT-IN performance-log metrics (TOKEN_ACQUISITION, CONNECTION, PRELOGIN,
 * LOGIN).
 *
 * <p>The driver under test is whichever branch of {@code Compilation/mssql-jdbc}
 * is currently checked out and built (its jar lives in {@code ../target}). The
 * branch name is passed in via {@code -Dorchestrator.driver=<branch>} purely for
 * labelling the output; the actual driver code exercised is the one on the
 * classpath.</p>
 *
 * <h2>Configurable knobs</h2>
 * <ul>
 *   <li><b>K</b> — number of executor (worker) threads.</li>
 *   <li><b>SPs</b> — number of distinct Entra service principals to spread load
 *       across (1..{@link Creds#SPS}.length).</li>
 *   <li><b>workload</b> — the shape of the connection-request stream:
 *       <ul>
 *         <li>{@code burst}  — all K threads fire one connection simultaneously
 *             (gated by a barrier), forming repeated stampede waves separated by
 *             {@code gapMs}. Models periodic cold token storms.</li>
 *         <li>{@code steady} — K threads continuously open/close connections
 *             back-to-back for the whole window (sustained throughput).</li>
 *         <li>{@code sticky} — like steady, but each thread reuses one SP for
 *             {@code stickyBatch} connections before switching (locality).</li>
 *       </ul></li>
 *   <li><b>duration</b> — measured window in seconds.</li>
 *   <li><b>mode</b> — {@code cold} (empty MSAL cache, token stampede) or
 *       {@code warm} (prime one token per SP first, then measure cache hits).</li>
 *   <li><b>spSelect</b> — how each request chooses which SP to hit:
 *       {@code random} or {@code roundrobin} (even, deterministic spread).</li>
 * </ul>
 *
 * <h2>How metrics are collected</h2>
 * A {@link PerformanceLogCallback} is registered with the driver. Every
 * connection attempt makes the driver publish its internal timings; this
 * harness aggregates per-activity count / avg / max / p50 / p95 / p99 in
 * nanosecond granularity. This is the driver's own instrumentation — no external
 * timing.
 *
 * <h2>Run</h2>
 * <pre>
 *   java -Dorchestrator.driver=&lt;branch&gt; \
 *        -cp "&lt;driver jar&gt;;&lt;deps&gt;;&lt;orchestrator classes&gt;" \
 *        Orchestrator [K] [spCount] [burst|steady|sticky] [durationSec] [cold|warm] [gapMs] [stickyBatch] [holdMs] [random|roundrobin]
 * </pre>
 * Defaults: K=20, spCount=10, workload=burst, durationSec=30, mode=cold,
 *           gapMs=200, stickyBatch=10, holdMs=0, spSelect=random.
 */
public final class Orchestrator {

    private Orchestrator() {
    }

    public static void main(String[] args) throws Exception {
        int k = intArg(args, 0, 20);
        int spCount = Math.max(1, Math.min(intArg(args, 1, 10), Creds.SPS.length));
        String workload = arg(args, 2, "burst").toLowerCase();
        long durationSec = Math.max(1L, longArg(args, 3, 30L));
        String mode = arg(args, 4, "cold").toLowerCase();
        boolean warm = mode.startsWith("warm");
        long gapMs = longArg(args, 5, 200L);
        int stickyBatch = Math.max(1, intArg(args, 6, 10));
        long holdMs = longArg(args, 7, 0L);
        String spSelect = arg(args, 8, "random").toLowerCase();
        String driverLabel = System.getProperty("orchestrator.driver", "under-test");

        if (!workload.equals("burst") && !workload.equals("steady") && !workload.equals("sticky")) {
            System.out.println("Unknown workload '" + workload + "'; using 'burst'.");
            workload = "burst";
        }
        if (!spSelect.equals("random") && !spSelect.equals("roundrobin")) {
            System.out.println("Unknown spSelect '" + spSelect + "'; using 'random'.");
            spSelect = "random";
        }
        boolean roundRobin = spSelect.equals("roundrobin");

        System.out.println("[step] Loading SQLServerDriver (" + driverLabel + ")...");
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

        PerfCollector perf = new PerfCollector();
        SQLServerDriver.registerPerformanceLogCallback(perf);
        System.out.println("[step] Registered performance-log callback (TOKEN_ACQUISITION, CONNECTION, PRELOGIN, LOGIN).");

        System.out.println("============================================================");
        System.out.println(" Orchestrator  driver=" + driverLabel);
        System.out.println("   K(threads)=" + k + "  SPs=" + spCount + "  workload=" + workload
                + "  duration=" + durationSec + "s  mode=" + (warm ? "warm" : "cold"));
        System.out.println("   gapMs=" + gapMs + "  stickyBatch=" + stickyBatch + "  holdMs=" + holdMs
                + "  spSelect=" + spSelect);
        System.out.println("============================================================");

        // Pre-build one JDBC URL per SP.
        String[] urls = new String[spCount];
        for (int i = 0; i < spCount; i++) {
            urls[i] = jdbcUrl(Creds.SPS[i][0], Creds.SPS[i][1]);
        }

        // DB-wake (serverless guard): ensure the DB is resumed before measuring.
        wakeDatabase(urls[0]);

        // Optional warm-up: prime one token per SP so the measured run hits cache.
        if (warm) {
            System.out.println("[warmup] Priming one connection per SP...");
            for (String url : urls) {
                try (Connection c = DriverManager.getConnection(url)) {
                    probe(c);
                } catch (Exception ignore) {
                    // a failed warm-up still acquired (and counted) its token
                }
            }
            perf.reset();
            System.out.println("[warmup] Done; perf counters reset (measured run = cache hits).");
        } else {
            System.out.println("[warmup] SKIPPED -- cold start (token stampede).");
        }

        long t0 = System.currentTimeMillis();
        Result r;
        switch (workload) {
            case "steady":
                r = runSteady(urls, k, durationSec * 1000L, holdMs, /*sticky*/ false, stickyBatch, roundRobin);
                break;
            case "sticky":
                r = runSteady(urls, k, durationSec * 1000L, holdMs, /*sticky*/ true, stickyBatch, roundRobin);
                break;
            case "burst":
            default:
                r = runBurst(urls, k, durationSec * 1000L, holdMs, gapMs, roundRobin);
                break;
        }
        long wallMs = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("Summary:");
        System.out.println("  driver          : " + driverLabel);
        System.out.println("  workload        : " + workload);
        System.out.println("  K threads       : " + k);
        System.out.println("  SPs             : " + spCount);
        System.out.println("  spSelect        : " + spSelect);
        System.out.println("  wall            : " + wallMs + " ms");
        System.out.println("  connection ok   : " + r.ok.sum());
        System.out.println("  connection fail : " + r.fail.sum());
        if (workload.equals("burst")) {
            System.out.println("  burst waves     : " + r.waves);
        }
        System.out.println();
        perf.printAll(driverLabel);
        System.out.println();
        System.out.println("[done]");

        SQLServerDriver.unregisterPerformanceLogCallback();
    }

    // ---------------------------------------------------------------------
    // Workloads
    // ---------------------------------------------------------------------

    /**
     * BURST: K threads repeatedly synchronize on a barrier and fire one
     * connection each at the same instant, forming stampede waves separated by
     * {@code gapMs}, until the duration elapses.
     */
    private static Result runBurst(String[] urls, int k, long durationMs, long holdMs, long gapMs,
            boolean roundRobin) throws InterruptedException {
        Result res = new Result();
        ExecutorService ex = Executors.newFixedThreadPool(k);
        long deadline = System.currentTimeMillis() + durationMs;
        CyclicBarrier barrier = new CyclicBarrier(k + 1);
        AtomicLong waveCounter = new AtomicLong();
        AtomicInteger rrCursor = new AtomicInteger(0);

        for (int w = 0; w < k; w++) {
            final int seed = w * 7919 + 13;
            ex.submit(() -> {
                Random rnd = new Random(seed);
                while (System.currentTimeMillis() < deadline) {
                    try {
                        barrier.await();              // line up for the next wave
                    } catch (Exception e) {
                        return;                        // barrier broken -> stop
                    }
                    String url = urls[nextSpIdx(roundRobin, rrCursor, rnd, urls.length)];
                    oneConnection(url, holdMs, res);
                }
            });
        }

        // Coordinator: release a wave, wait gapMs, repeat until deadline.
        while (System.currentTimeMillis() < deadline) {
            try {
                barrier.await();                       // release all K at once
                waveCounter.incrementAndGet();
            } catch (Exception e) {
                break;
            }
            if (gapMs > 0) {
                Thread.sleep(gapMs);
            }
        }
        barrier.reset();
        ex.shutdownNow();
        ex.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        res.waves = waveCounter.get();
        return res;
    }

    /**
     * STEADY / STICKY: K threads continuously open/close connections for the
     * window. With {@code sticky}, a thread reuses one SP for {@code stickyBatch}
     * connections before switching.
     */
    private static Result runSteady(String[] urls, int k, long durationMs, long holdMs,
            boolean sticky, int stickyBatch, boolean roundRobin) throws InterruptedException {
        Result res = new Result();
        ExecutorService ex = Executors.newFixedThreadPool(k);
        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.currentTimeMillis() + durationMs;
        AtomicInteger rrCursor = new AtomicInteger(0);

        for (int w = 0; w < k; w++) {
            final int seed = w * 7919 + 13;
            ex.submit(() -> {
                Random rnd = new Random(seed);
                int spIdx = nextSpIdx(roundRobin, rrCursor, rnd, urls.length);
                int onCurrent = 0;
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (System.currentTimeMillis() < deadline) {
                    if (sticky) {
                        if (onCurrent >= stickyBatch) {
                            spIdx = nextSpIdx(roundRobin, rrCursor, rnd, urls.length);
                            onCurrent = 0;
                        }
                        onCurrent++;
                    } else {
                        spIdx = nextSpIdx(roundRobin, rrCursor, rnd, urls.length);
                    }
                    oneConnection(urls[spIdx], holdMs, res);
                }
            });
        }

        start.countDown();
        ex.shutdown();
        ex.awaitTermination(durationMs + 60_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        return res;
    }

    /**
     * Chooses the next SP index. Round-robin uses a shared atomic cursor so
     * requests cycle evenly through all SPs in order; random uses the worker's
     * own RNG.
     */
    private static int nextSpIdx(boolean roundRobin, AtomicInteger rrCursor, Random rnd, int n) {
        if (roundRobin) {
            return Math.floorMod(rrCursor.getAndIncrement(), n);
        }
        return rnd.nextInt(n);
    }

    /** Opens one connection (the unit a perf-log activity is measured around). */
    private static void oneConnection(String url, long holdMs, Result res) {
        try (Connection c = DriverManager.getConnection(url)) {
            probe(c);
            if (holdMs > 0) {
                Thread.sleep(holdMs);
            }
            res.ok.increment();
        } catch (Exception e) {
            // Token may have been acquired before login failed; perf callback
            // records that independently.
            res.fail.increment();
        }
    }

    private static void probe(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        } catch (Exception ignore) {
            // token already acquired during login; probe failure is irrelevant
        }
    }

    // ---------------------------------------------------------------------
    // DB wake + URL
    // ---------------------------------------------------------------------

    private static void wakeDatabase(String url) {
        System.out.println("[wake] Ensuring DB is resumed (serverless auto-pause guard)...");
        final int maxAttempts = 8;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long t0 = System.currentTimeMillis();
            try (Connection c = DriverManager.getConnection(url);
                    Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                System.out.printf("[wake] DB awake (attempt %d/%d, %d ms).%n",
                        attempt, maxAttempts, System.currentTimeMillis() - t0);
                return;
            } catch (Exception e) {
                System.out.printf("[wake] attempt %d/%d failed after %d ms: %s%n",
                        attempt, maxAttempts, System.currentTimeMillis() - t0, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        System.out.println("[wake] WARNING: DB not confirmed awake; proceeding anyway.");
    }

    private static String jdbcUrl(String clientId, String secret) {
        return String.format(
                "jdbc:sqlserver://%s:1433;database=%s;"
                        + "encrypt=true;trustServerCertificate=false;"
                        + "hostNameInCertificate=*.database.windows.net;"
                        + "loginTimeout=30;"
                        + "authentication=ActiveDirectoryServicePrincipal;"
                        + "user=%s;password=%s",
                Creds.SERVER_FQDN, Creds.DATABASE_NAME, clientId, secret);
    }

    // ---------------------------------------------------------------------
    // Args
    // ---------------------------------------------------------------------

    private static String arg(String[] a, int i, String def) {
        return (a.length > i && !a[i].isBlank()) ? a[i] : def;
    }

    private static int intArg(String[] a, int i, int def) {
        return (a.length > i && !a[i].isBlank()) ? Integer.parseInt(a[i].trim()) : def;
    }

    private static long longArg(String[] a, int i, long def) {
        return (a.length > i && !a[i].isBlank()) ? Long.parseLong(a[i].trim()) : def;
    }

    private static final class Result {
        final LongAdder ok = new LongAdder();
        final LongAdder fail = new LongAdder();
        volatile long waves;
    }

    // ---------------------------------------------------------------------
    // Performance-log collector
    // ---------------------------------------------------------------------

    /**
     * Captures the driver's per-activity timings via the public
     * {@link PerformanceLogCallback}. Uses nanosecond granularity and keeps a
     * per-activity sample list so true p50/p95/p99 can be reported.
     */
    private static final class PerfCollector implements PerformanceLogCallback {

        private final ConcurrentHashMap<PerformanceActivity, ActivityStats> byActivity =
                new ConcurrentHashMap<>();

        @Override
        public boolean useNanoseconds() {
            return true;
        }

        @Override
        public void publish(PerformanceActivity activity, int connectionId, long duration, Exception exception) {
            byActivity.computeIfAbsent(activity, a -> new ActivityStats()).record(duration, exception);
        }

        @Override
        public void publish(PerformanceActivity activity, int connectionId, int statementId, long duration,
                Exception exception) {
            // Statement-level activities are not the focus here.
        }

        void reset() {
            byActivity.clear();
        }

        void printAll(String driverLabel) {
            // Report the connection-level activities in a sensible order.
            PerformanceActivity[] order = {
                    PerformanceActivity.TOKEN_ACQUISITION,
                    PerformanceActivity.CONNECTION,
                    PerformanceActivity.PRELOGIN,
                    PerformanceActivity.LOGIN
            };
            System.out.println("Performance-log metrics (from " + driverLabel + " perf logger):");
            for (PerformanceActivity a : order) {
                ActivityStats s = byActivity.get(a);
                if (s == null) {
                    continue;
                }
                s.print(a.name());
            }
            // Any other activities that showed up.
            byActivity.forEach((a, s) -> {
                for (PerformanceActivity known : order) {
                    if (known == a) {
                        return;
                    }
                }
                s.print(a.name());
            });
        }
    }

    private static final class ActivityStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder sumNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong(0);
        private final LongAdder errors = new LongAdder();
        private final ConcurrentLinkedQueue<Long> samples = new ConcurrentLinkedQueue<>();

        void record(long nanos, Exception ex) {
            count.increment();
            sumNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
            samples.add(nanos);
            if (ex != null) {
                errors.increment();
            }
        }

        void print(String label) {
            long n = count.sum();
            System.out.println("  " + label + ":");
            System.out.println("    count         : " + n);
            System.out.println("    errors        : " + errors.sum());
            if (n == 0) {
                return;
            }
            long avgNs = sumNanos.sum() / n;
            long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf("    avg           : %.3f ms%n", avgNs / 1_000_000.0);
            System.out.printf("    p50           : %.3f ms%n", pct(sorted, 50) / 1_000_000.0);
            System.out.printf("    p95           : %.3f ms%n", pct(sorted, 95) / 1_000_000.0);
            System.out.printf("    p99           : %.3f ms%n", pct(sorted, 99) / 1_000_000.0);
            System.out.printf("    max           : %.3f ms%n", maxNanos.get() / 1_000_000.0);
        }

        private static long pct(long[] sorted, int p) {
            if (sorted.length == 0) {
                return 0;
            }
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            idx = Math.max(0, Math.min(idx, sorted.length - 1));
            return sorted[idx];
        }
    }
}
