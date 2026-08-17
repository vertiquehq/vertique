// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link JwtAuthFactory}'s construction paths: JWKS location handling for {@code classpath:}
 * and filesystem locations, symmetric-key construction, the arguments and malformed documents each
 * rejects, the delegation the returned {@link JWTAuth} wrapper performs, and — for the asynchronous
 * overloads — the documented worker-thread dispatch and caller-classloader capture.
 */
@ExtendWith(VertxExtension.class)
class JwtAuthFactoryTest {

    /**
     * The {@link Vertx} instance owned by
     * {@link #shouldQueueLocalJwksReadsBehindTheWorkerPool(Path, VertxTestContext)} — sized to a
     * single worker thread so that test can saturate the pool. Every other test uses the
     * {@link VertxExtension}-injected instance and leaves this {@code null}.
     */
    private Vertx ownedVertx;

    /** Awaited by the gate task; counting it down frees the sole worker thread. */
    private CountDownLatch releaseWorker;

    /**
     * Releases a held worker and closes the test-owned {@link Vertx}, in that order.
     *
     * <p>Order matters: a gate still holding the pool's only thread would block {@code close()}.
     * Both fields are null-guarded because only one test in this class creates them, and a test that
     * failed before assigning them must still tear down cleanly.
     *
     * @throws InterruptedException if the JUnit thread is interrupted while awaiting the close
     */
    @AfterEach
    void closeOwnedVertx() throws InterruptedException {
        if (releaseWorker != null) {
            releaseWorker.countDown();
        }
        if (ownedVertx != null) {
            CountDownLatch closed = new CountDownLatch(1);
            Future<Void> close = ownedVertx.close();
            close.onComplete(ignored -> closed.countDown());
            assertTrue(closed.await(20, TimeUnit.SECONDS), "the test-owned Vertx must close within 20 seconds");
            // The latch alone would count down on a failed close too, so the outcome is asserted
            // separately: this instance deploys no verticle and registers no close hook, and the
            // gate is released above, so a failed close is unexpected and worth surfacing.
            assertTrue(close.succeeded(), "the test-owned Vertx must close cleanly");
            ownedVertx = null;
        }
    }

    @Test
    @DisplayName("Should create JWTAuth from classpath JWKS and generate a token")
    void shouldCreateFromClasspathJwks(Vertx vertx) {
        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "classpath:test-jwks.json");

        assertNotNull(auth);
        String token = auth.generateToken(new JsonObject().put("sub", "test"));
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("Should throw UncheckedIOException for missing classpath resource")
    void shouldThrowForMissingClasspathResource(Vertx vertx) {
        assertThrows(UncheckedIOException.class, () -> JwtAuthFactory.fromJwks(vertx, "classpath:nonexistent.json"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for null location")
    void shouldThrowForNullLocation(Vertx vertx) {
        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank location")
    void shouldThrowForBlankLocation(Vertx vertx) {
        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, "  "));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for JWKS without 'keys' array")
    void shouldThrowForJwksWithoutKeysArray(@TempDir Path tempDir, Vertx vertx) throws Exception {
        Path jwksFile = tempDir.resolve("bad-jwks.json");
        Files.writeString(jwksFile, "{\"notkeys\":[]}");

        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, jwksFile.toString()));
    }

    @Test
    @DisplayName("Should create JWTAuth from symmetric key and generate a token")
    void shouldCreateFromSymmetricKey(Vertx vertx) {
        JWTAuth auth =
                JwtAuthFactory.fromSymmetricKey(vertx, "HS256", "super-secret-key-for-testing-minimum-256-bits-long!");

        assertNotNull(auth);
        String token = auth.generateToken(new JsonObject().put("sub", "test"));
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("Should create JWTAuth from filesystem path")
    void shouldCreateFromFilesystemPath(@TempDir Path tempDir, Vertx vertx) throws Exception {
        Path jwksFile = copyJwksToTempDir(tempDir);

        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, jwksFile.toString());
        assertNotNull(auth);
    }

    @Test
    @DisplayName("Should delegate authenticate and both generateToken overloads through the returned JWTAuth")
    void shouldDelegateEveryJwtAuthMethod(Vertx vertx, VertxTestContext testContext) {
        // The factory returns a wrapper that attests the validation config it applied. The wrapper
        // must forward every JWTAuth method: dropping either generateToken overload would silently
        // break token signing for every application (and every test) that signs through the factory.
        JWTAuth auth =
                JwtAuthFactory.fromSymmetricKey(vertx, "HS256", "super-secret-key-for-testing-minimum-256-bits-long!");

        String defaultOptionsToken = auth.generateToken(new JsonObject().put("sub", "delegation-default"));
        String explicitOptionsToken =
                auth.generateToken(new JsonObject().put("sub", "delegation-explicit"), new JWTOptions());

        assertNotNull(defaultOptionsToken);
        assertFalse(defaultOptionsToken.isBlank());
        assertNotNull(explicitOptionsToken);
        assertFalse(explicitOptionsToken.isBlank());

        auth.authenticate(new TokenCredentials(defaultOptionsToken))
                .compose(user -> auth.authenticate(new TokenCredentials(explicitOptionsToken)))
                .onComplete(testContext.succeeding(user -> testContext.completeNow()));
    }

    /**
     * Proves that {@link JwtAuthFactory#fromJwksAsync(Vertx, String)} hands <em>every</em> local
     * JWKS read to the worker pool, for both the {@code classpath:} and the filesystem branch.
     *
     * <p>The proof rests on a framework guarantee rather than on scheduling luck: the test owns a
     * {@link Vertx} whose worker pool has exactly one thread, and a gate task holds that thread for
     * the duration of the assertions. A read that was dispatched therefore <em>cannot</em> have run,
     * so its future is necessarily incomplete; a read performed inline returns a
     * {@code Future.succeededFuture(...)} that is already complete before {@code fromJwksAsync}
     * returns. That is precisely the "local locations are cheap, read them inline" fast path this
     * test exists to catch — the regression fixed by commit {@code 516202b}, which was
     * branch-specific, hence one assertion per location kind with a message naming its branch.
     *
     * <p>What it does <em>not</em> exclude: an implementation that read inline on the event loop but
     * completed the future on a later turn would also pass. The filesystem branch's worker execution
     * is therefore proven transitively — through the single {@code executeBlocking} dispatch site
     * shared by every location kind — while
     * {@link #shouldResolveClasspathLocationThroughTheCallerContextClassLoader(Vertx, VertxTestContext)}
     * pins that dispatch directly for the {@code classpath:} branch.
     *
     * @param tempDir     the JUnit-managed temporary directory holding the filesystem JWKS fixture
     * @param testContext the async assertion sink
     * @throws Exception if the fixture cannot be copied or the gate is never reached
     */
    @Test
    @DisplayName("Should queue both classpath and filesystem JWKS reads behind a saturated worker pool")
    void shouldQueueLocalJwksReadsBehindTheWorkerPool(@TempDir Path tempDir, VertxTestContext testContext)
            throws Exception {
        Path jwksFile = copyJwksToTempDir(tempDir);
        ownedVertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
        CountDownLatch workerOccupied = new CountDownLatch(1);
        releaseWorker = new CountDownLatch(1);

        // The gate is submitted from the JUnit thread deliberately. A Vert.x call made from a
        // non-Vert.x thread creates a *fresh* context, so the gate task and the reads issued from
        // the runOnContext task below sit in two different ordered queues. What serializes them is
        // therefore workerPoolSize(1) — one thread, held by the gate — and not ordered-queue
        // semantics. Moving the gate into the same context to "simplify" the test would silently
        // change what is proved: the ordered queue would hold the reads back even for an
        // implementation that never dispatched them, and the assertions below would pass on the
        // very regression they exist to catch.
        Future<Void> gate = ownedVertx.executeBlocking(() -> {
            workerOccupied.countDown();
            assertTrue(releaseWorker.await(20, TimeUnit.SECONDS), "the gate must be released by the test");
            return null;
        });
        // The gate's own assertion runs on a worker thread, so its only route to JUnit is this
        // future. Left unobserved, a gate that never got released would time out, free the worker
        // itself, let the reads complete, and report the test GREEN on a failed assertion.
        gate.onFailure(testContext::failNow);
        // 20 seconds, matching the gate's own await and the teardown close: this budget is not a
        // proof, only an outer bound on a brand-new pool thread being scheduled, and a saturated CI
        // runner is exactly where that is slow. It stays inside the vertx-junit5 default, which does
        // not start until the test method returns.
        assertTrue(
                workerOccupied.await(20, TimeUnit.SECONDS),
                "the gate task must occupy the pool's only worker thread before the reads are issued");

        ownedVertx.runOnContext(ignored -> {
            try {
                issueReadsAndAssertTheyAreQueued(jwksFile, testContext);
            } catch (Throwable failure) {
                // fromJwksAsync validates its arguments synchronously, so the calls inside can throw
                // before the helper's own finally is reached. Without this catch the gate would hold
                // the worker until its await expired and the test would fail ~20 seconds later
                // accusing the gate, never naming the exception that actually happened.
                releaseWorker.countDown();
                testContext.failNow(failure);
            }
        });
    }

    /**
     * Issues both local JWKS reads on the calling event-loop task and asserts neither can have
     * completed while the gate holds the pool's only worker thread.
     *
     * @param jwksFile    the filesystem JWKS fixture to read
     * @param testContext the async assertion sink
     */
    private void issueReadsAndAssertTheyAreQueued(Path jwksFile, VertxTestContext testContext) {
        Future<JWTAuth> classpathAuth = JwtAuthFactory.fromJwksAsync(ownedVertx, "classpath:test-jwks.json");
        Future<JWTAuth> filesystemAuth = JwtAuthFactory.fromJwksAsync(ownedVertx, jwksFile.toString());

        try {
            // An inline read would return an already-complete future while the sole worker is
            // still occupied; a dispatched read cannot possibly have run yet.
            testContext.verify(() -> {
                assertFalse(
                        classpathAuth.isComplete(),
                        "fromJwksAsync must dispatch a classpath: JWKS read to a worker thread; the returned "
                                + "future was already complete while the pool's only worker was held, so the "
                                + "classpath branch read inline on the event loop");
                assertFalse(
                        filesystemAuth.isComplete(),
                        "fromJwksAsync must dispatch a filesystem JWKS read to a worker thread; the returned "
                                + "future was already complete while the pool's only worker was held, so the "
                                + "filesystem branch read inline on the event loop");
            });
        } finally {
            // Outside the verify block on purpose: a failed assertion must not strand the gate
            // on the only worker thread, or teardown would block instead of the test failing.
            releaseWorker.countDown();
        }

        Future.join(classpathAuth, filesystemAuth)
                .onComplete(testContext.succeeding(joined -> testContext.verify(() -> {
                    assertNotNull(classpathAuth.result(), "the classpath JWKS read must produce a JWTAuth");
                    assertNotNull(filesystemAuth.result(), "the filesystem JWKS read must produce a JWTAuth");
                    testContext.completeNow();
                })));
    }

    /**
     * Proves that a {@code classpath:} location is resolved through the <em>calling</em> thread's
     * context classloader, and that the resolution itself still happens on a worker thread.
     *
     * <p>The packaged {@code META-INF/vertique/module.md} documents the {@code classpath:} location
     * kind as "thread-context classloader resource"; before this test nothing in the module's test
     * sources referenced a {@link ClassLoader} at all, so that contract was stated and unverified.
     *
     * <p>The mechanism is a recording loader installed as the thread context classloader for the
     * duration of the {@code fromJwksAsync} call, and only for that call — Vert.x sets a task's TCCL
     * from the context, but whether it restores a TCCL replaced <em>inside</em> a task is
     * undocumented, so the test restores it itself in an immediate {@code finally}.
     *
     * <p>What each assertion buys:
     * <ol>
     *   <li><b>Non-null {@link JWTAuth}</b> — the read actually went through the recording loader's
     *       delegation and produced a usable result, rather than the loader merely being touched by
     *       something unrelated.</li>
     *   <li><b>The recording loader was consulted</b> — this is the load-bearing assertion. The
     *       loader is only visible as the TCCL on the <em>calling</em> event-loop thread, so it can
     *       be consulted only if {@code fromJwksAsync} captured the classloader before dispatching.
     *       A capture moved inside the {@code executeBlocking} lambda would read the worker thread's
     *       context classloader instead, resolve the fixture through it, and never touch the
     *       recorder — silently breaking classpath resolution for any application running under an
     *       isolated or custom loader.</li>
     *   <li><b>{@link Context#isOnWorkerThread()} was {@code true} at read time</b> — documented to
     *       hold inside an {@code executeBlocking} callable, so this pins the read to a worker
     *       without matching on thread names.</li>
     *   <li><b>The reading thread is not the calling thread</b> — the read did not run inline on the
     *       caller's event loop, independently of the predicate above.</li>
     * </ol>
     *
     * <p>This test uses the {@link VertxExtension}-injected {@link Vertx} and owns nothing: it needs
     * an ordinary worker pool, not the saturated one owned by
     * {@link #shouldQueueLocalJwksReadsBehindTheWorkerPool(Path, VertxTestContext)}.
     *
     * @param vertx       the injected Vert.x instance
     * @param testContext the async assertion sink
     */
    @Test
    @DisplayName("Should resolve a classpath JWKS location through the caller's thread context classloader")
    void shouldResolveClasspathLocationThroughTheCallerContextClassLoader(Vertx vertx, VertxTestContext testContext) {
        RecordingClassLoader recordingLoader = new RecordingClassLoader(JwtAuthFactoryTest.class.getClassLoader());

        vertx.runOnContext(ignored -> {
            Thread caller = Thread.currentThread();

            ClassLoader previousLoader = caller.getContextClassLoader();
            Future<JWTAuth> auth;
            try {
                caller.setContextClassLoader(recordingLoader);
                auth = JwtAuthFactory.fromJwksAsync(vertx, "classpath:test-jwks.json");
            } finally {
                // Restored in the same task that installed it: Vert.x's own restore behaviour for a
                // TCCL replaced inside a task is undocumented, so the test does not rely on it.
                caller.setContextClassLoader(previousLoader);
            }

            auth.onComplete(testContext.succeeding(jwtAuth -> testContext.verify(() -> {
                assertNotNull(jwtAuth, "the classpath JWKS read must produce a JWTAuth");
                assertNotNull(
                        recordingLoader.readThread.get(),
                        "fromJwksAsync must capture the caller's thread context classloader before dispatching; the "
                                + "loader installed on the calling event-loop thread was never consulted for "
                                + "test-jwks.json, so the classpath: location resolved against the worker thread's "
                                + "own context classloader instead");
                assertEquals(
                        Boolean.TRUE,
                        recordingLoader.onWorkerThread.get(),
                        "the classpath JWKS read must run inside an executeBlocking callable, where "
                                + "Context.isOnWorkerThread() is documented to be true");
                assertNotSame(
                        caller,
                        recordingLoader.readThread.get(),
                        "the classpath JWKS read must not run on the caller's event-loop thread");
                testContext.completeNow();
            })));
        });
    }

    /**
     * A {@link ClassLoader} that records the first consultation for the JWKS fixture and then
     * delegates to its parent, which is the test class's own loader.
     *
     * <p>Recording only the <em>first</em> consultation keeps the observation stable: the assertions
     * are about the thread that performed the documented read, and a later incidental lookup on some
     * other thread must not overwrite it.
     */
    private static final class RecordingClassLoader extends ClassLoader {

        /** The thread that first asked this loader for the JWKS fixture; {@code null} until then. */
        private final AtomicReference<Thread> readThread = new AtomicReference<>();

        /** {@link Context#isOnWorkerThread()} as observed during that first consultation. */
        private final AtomicReference<Boolean> onWorkerThread = new AtomicReference<>();

        /**
         * Creates a recording loader delegating to {@code parent}.
         *
         * @param parent the loader that actually resolves the resource
         */
        private RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            record(name);
            return super.getResourceAsStream(name);
        }

        @Override
        public URL getResource(String name) {
            record(name);
            return super.getResource(name);
        }

        /**
         * Records the first consultation for the JWKS fixture, whichever lookup method reached it.
         *
         * <p>Both methods are instrumented so that a behavior-preserving change in the factory —
         * {@code getResource(name).openStream()} instead of {@code getResourceAsStream(name)} — leaves
         * the observation intact. Instrumenting only one would turn such a refactor red, with a
         * message wrongly accusing the read of resolving against the worker thread's classloader.
         *
         * @param name the requested resource name
         */
        private void record(String name) {
            if ("test-jwks.json".equals(name) && readThread.compareAndSet(null, Thread.currentThread())) {
                onWorkerThread.set(Context.isOnWorkerThread());
            }
        }
    }

    /**
     * Copies the test JWKS fixture off the classpath into {@code tempDir}, so a test can exercise
     * the factory's filesystem branch rather than its {@code classpath:} branch.
     *
     * @param tempDir the JUnit-managed temporary directory to copy into
     * @return the path of the copied JWKS document
     * @throws Exception if the fixture cannot be read or written
     */
    private static Path copyJwksToTempDir(Path tempDir) throws Exception {
        Path jwksFile = tempDir.resolve("jwks.json");
        try (var is = JwtAuthFactoryTest.class.getResourceAsStream("/test-jwks.json")) {
            assertNotNull(is, "test-jwks.json must exist on test classpath");
            Files.copy(is, jwksFile);
        }
        return jwksFile;
    }
}
