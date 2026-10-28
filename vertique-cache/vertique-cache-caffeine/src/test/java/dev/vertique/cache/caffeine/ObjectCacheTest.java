// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.aop.T011CacheCompositionFixtures;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.ObjectCacheConfig;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.config.parser.ConfigParsingModule;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behavior proof for the by-reference, size-bounded, single-flight object cache. */
class ObjectCacheTest {

    private static final String PROVIDER = "caffeine-object";
    private static final long WAIT_SECONDS = 5;

    @Test
    void loadsOnceAndReturnsTheSameInstanceWhenCacheIsDisabled() throws Exception {
        // Given: a graph with cache.enabled=false and a recording observer, and an object cache built from
        // that graph's observers
        DisabledComponent graph = DaggerObjectCacheTest_DisabledComponent.builder()
                .configModule(new T011CacheCompositionFixtures.ConfigModule(
                        new JsonObject().put("cache", cacheSectionDisabled())))
                .build();
        assertFalse(graph.config().enabled(), "the graph must really run with caching disabled");
        RecordingObserver observer = graph.observer();
        ObjectCache<String, Plan> cache =
                new CaffeineObjectCache<>("plans", config(10, 0), error -> false, graph.observers());
        CountingLoader loader = countingLoader();

        // When: the same key is loaded twice
        Future<Plan> first = settled(cache.getOrLoad("k", loader));
        Future<Plan> second = settled(cache.getOrLoad("k", loader));

        // Then: the loader ran once and both calls hold the identical instance
        assertEquals(1, loader.calls());
        assertSame(first.result(), second.result());
        assertSame(first.result(), cache.getIfPresent("k").orElseThrow());
        assertEquals(
                List.of(
                        new Observed(CacheOperation.GET, PROVIDER, "plans", CacheOutcome.MISS),
                        new Observed(CacheOperation.LOAD, PROVIDER, "plans", CacheOutcome.SUCCESS)),
                observer.events("plans").stream().map(Observed::withoutElapsed).toList());
    }

    @Test
    @Timeout(10)
    void concurrentCallersForOneKeyShareOneLoad() throws Exception {
        // Given: a cache and a loader that stays in flight until its promise completes
        ObjectCache<String, Plan> cache = new CaffeineObjectCache<>("plans", config(10, 0), error -> false, Set.of());
        Promise<Plan> inFlight = Promise.promise();
        AtomicInteger loads = new AtomicInteger();
        Supplier<Future<Plan>> loader = () -> {
            loads.incrementAndGet();
            return inFlight.future();
        };

        // When: eight threads call getOrLoad for the same key, then the load completes
        List<Future<Plan>> futures = racers(8, () -> cache.getOrLoad("k", loader));
        Plan loaded = new Plan();
        inFlight.complete(loaded);

        // Then: the loader ran once and every caller received the identical instance
        assertEquals(1, loads.get());
        assertEquals(8, futures.size());
        for (Future<Plan> future : futures) {
            assertSame(loaded, settled(future).result());
        }
    }

    @Test
    void cachesOnlyFailuresThePredicateAccepts() throws Exception {
        // Given: a cache that keeps only IllegalStateException failures, and a loader failing per key
        RecordingObserver observer = new RecordingObserver();
        ObjectCache<String, Plan> cache = new CaffeineObjectCache<>(
                "plans", config(10, 0), error -> error instanceof IllegalStateException, Set.of(observer));
        IllegalStateException deterministic = new IllegalStateException("deterministic");
        UncheckedIOException transientFailure = new UncheckedIOException(new IOException("transient"));
        FailingLoader loader = failingLoader(Map.of("det", deterministic, "transient", transientFailure));

        // When: each key is loaded twice
        Future<Plan> det1 = settled(cache.getOrLoad("det", loader.forKey("det")));
        Future<Plan> det2 = settled(cache.getOrLoad("det", loader.forKey("det")));
        long detLoadErrors = observer.count("plans", CacheOperation.LOAD, CacheOutcome.ERROR);
        Future<Plan> transient1 = settled(cache.getOrLoad("transient", loader.forKey("transient")));
        Future<Plan> transient2 = settled(cache.getOrLoad("transient", loader.forKey("transient")));
        long allLoadErrors = observer.count("plans", CacheOperation.LOAD, CacheOutcome.ERROR);

        // Then: the accepted failure loaded once and is served again; the rejected one loaded twice
        assertEquals(1, loader.calls("det"));
        assertSame(deterministic, det1.cause());
        assertSame(deterministic, det2.cause());
        assertEquals(2, loader.calls("transient"));
        assertSame(transientFailure, transient1.cause());
        assertSame(transientFailure, transient2.cause());
        assertEquals(1, detLoadErrors);
        assertEquals(2, allLoadErrors - detLoadErrors);
    }

    @Test
    void reportsSizeEvictionsAndNoHitEvents() throws Exception {
        // Given: a cache bounded to two entries and a recording observer
        RecordingObserver observer = new RecordingObserver();
        ObjectCache<String, Plan> cache =
                new CaffeineObjectCache<>("plans", config(2, 0), error -> false, Set.of(observer));
        CountingLoader loader = countingLoader();

        // When: three keys are loaded, then read again through getOrLoad
        for (String key : List.of("a", "b", "c")) {
            settled(cache.getOrLoad(key, loader));
        }
        for (String key : List.of("a", "b", "c")) {
            settled(cache.getOrLoad(key, loader));
        }

        // Then: the cache settles within its bound and reports at least one size eviction
        awaitTrue(() -> cache.size() <= 2);
        awaitTrue(() -> observer.count("plans", CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED) >= 1);
        List<Observed> events = observer.events("plans");
        assertTrue(cache.size() <= 2);
        assertTrue(events.stream()
                .filter(event -> event.outcome() == CacheOutcome.SIZE_EVICTED)
                .allMatch(event -> event.operation() == CacheOperation.EVICT
                        && event.elapsed().isZero()));
        assertTrue(events.stream().allMatch(event -> event.provider().equals(PROVIDER)));
        assertTrue(events.stream().noneMatch(event -> event.outcome() == CacheOutcome.HIT));

        // And: explicitly invalidating a resident key is not reported as a size eviction
        String resident = List.of("a", "b", "c").stream()
                .filter(key -> cache.getIfPresent(key).isPresent())
                .findFirst()
                .orElseThrow();
        long evictionsBefore = observer.count("plans", CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED);
        cache.invalidate(resident);
        awaitTrue(() -> cache.getIfPresent(resident).isEmpty());
        Thread.sleep(300); // eviction notifications may be delivered asynchronously; give them time to surface
        assertEquals(evictionsBefore, observer.count("plans", CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED));
    }

    @Test
    void expiredEntriesLoadAgainAndAreNotReportedAsSizeEvictions() throws Exception {
        // Given: a cache whose entries live 10 seconds on a ticker the test controls
        RecordingObserver observer = new RecordingObserver();
        long[] nanos = {0};
        ObjectCache<String, Plan> cache =
                new CaffeineObjectCache<>("plans", config(10, 10), error -> false, Set.of(observer), () -> nanos[0]);
        CountingLoader loader = countingLoader();

        // When: a key is loaded, read within its lifetime, and read again after it
        settled(cache.getOrLoad("k", loader));
        nanos[0] = Duration.ofSeconds(9).toNanos();
        settled(cache.getOrLoad("k", loader));
        int withinLifetime = loader.calls();
        nanos[0] = Duration.ofSeconds(11).toNanos();
        boolean presentAfterExpiry = cache.getIfPresent("k").isPresent();
        settled(cache.getOrLoad("k", loader));

        // Then: the entry was shared while alive, gone after expiry, loaded again, and no size eviction was reported
        assertEquals(1, withinLifetime);
        assertFalse(presentAfterExpiry);
        assertEquals(2, loader.calls());
        Thread.sleep(300); // removal notifications may be delivered asynchronously; give them time to surface
        assertEquals(0, observer.count("plans", CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED));
    }

    @Test
    void zeroExpiryNeverExpiresEntries() throws Exception {
        // Given: a cache without time expiration, on a ticker far in the future
        long[] nanos = {0};
        ObjectCache<String, Plan> cache =
                new CaffeineObjectCache<>("plans", config(10, 0), error -> false, Set.of(), () -> nanos[0]);
        CountingLoader loader = countingLoader();

        // When: a key is loaded and read a century later
        settled(cache.getOrLoad("k", loader));
        nanos[0] = Duration.ofDays(36_500).toNanos();

        // Then: it is still resident and not loaded again
        assertTrue(cache.getIfPresent("k").isPresent());
        settled(cache.getOrLoad("k", loader));
        assertEquals(1, loader.calls());
    }

    @Test
    void refusesInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectCacheConfig(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectCacheConfig(1, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaffeineObjectCache<>(" ", config(10, 0), error -> false, Set.of()));
        assertThrows(
                NullPointerException.class, () -> new CaffeineObjectCache<>("plans", null, error -> false, Set.of()));
    }

    private static ObjectCacheConfig config(int maximumEntries, long ttlSeconds) {
        return new ObjectCacheConfig(maximumEntries, ttlSeconds);
    }

    @Test
    @Timeout(10)
    void eachCallerCompletesOnItsOwnContext() throws Exception {
        Vertx vertx = Vertx.vertx();
        ExecutorService completer = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "completer"));
        try {
            // Given: two distinct event-loop contexts, a cache, and a loader that stays in flight
            Context contextA = vertx.getOrCreateContext();
            Context contextB = vertx.getOrCreateContext();
            assertNotSame(contextA, contextB);
            ObjectCache<String, Plan> cache =
                    new CaffeineObjectCache<>("plans", config(10, 0), error -> false, Set.of());
            Promise<Plan> inFlight = Promise.promise();
            AtomicInteger loads = new AtomicInteger();
            Supplier<Future<Plan>> loader = () -> {
                loads.incrementAndGet();
                return inFlight.future();
            };

            // When: callers on A, on B and on a thread without a context load the key, then the load completes
            // from a worker thread outside both contexts
            Future<Plan> onA = callOnContext(contextA, () -> cache.getOrLoad("k", loader));
            Future<Plan> onB = callOnContext(contextB, () -> cache.getOrLoad("k", loader));
            Future<Plan> withoutContext = cache.getOrLoad("k", loader);
            CompletableFuture<Completion> seenA = completionOf(onA);
            CompletableFuture<Completion> seenB = completionOf(onB);
            CompletableFuture<Completion> seenWithoutContext = completionOf(withoutContext);
            Plan loaded = new Plan();
            Thread[] completingThread = new Thread[1];
            completer
                    .submit(() -> {
                        completingThread[0] = Thread.currentThread();
                        inFlight.complete(loaded);
                    })
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);

            // Then: the loader ran once; each callback ran on its caller's context, the context-free one on the
            // completing thread; all hold the identical instance
            Completion a = seenA.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Completion b = seenB.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Completion none = seenWithoutContext.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, loads.get());
            assertSame(contextA, a.context());
            assertSame(contextB, b.context());
            assertSame(completingThread[0], none.thread());
            assertSame(loaded, a.value());
            assertSame(loaded, b.value());
            assertSame(loaded, none.value());
        } finally {
            completer.shutdownNow();
            vertx.close().toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ---- helpers ----

    /** The cache configuration section with every default kept except {@code enabled}, which is false. */
    private static JsonObject cacheSectionDisabled() {
        return JsonObject.mapFrom(CacheConfig.defaults()).put("enabled", false);
    }

    /** A non-serializable runtime object standing in for a compiled plan. */
    private static final class Plan {}

    private static final class CountingLoader implements Supplier<Future<Plan>> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Future<Plan> get() {
            calls.incrementAndGet();
            return Future.succeededFuture(new Plan());
        }

        int calls() {
            return calls.get();
        }
    }

    private static CountingLoader countingLoader() {
        return new CountingLoader();
    }

    private static final class FailingLoader {
        private final Map<String, Throwable> failures;
        private final Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();

        FailingLoader(Map<String, Throwable> failures) {
            this.failures = failures;
        }

        Supplier<Future<Plan>> forKey(String key) {
            return () -> {
                calls.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                return Future.failedFuture(failures.get(key));
            };
        }

        int calls(String key) {
            return calls.getOrDefault(key, new AtomicInteger()).get();
        }
    }

    private static FailingLoader failingLoader(Map<String, Throwable> failures) {
        return new FailingLoader(failures);
    }

    /** One observed operation, as a comparable tuple. */
    private record Observed(
            CacheOperation operation, String provider, String cacheName, CacheOutcome outcome, Duration elapsed) {
        Observed(CacheOperation operation, String provider, String cacheName, CacheOutcome outcome) {
            this(operation, provider, cacheName, outcome, Duration.ZERO);
        }

        Observed withoutElapsed() {
            return new Observed(operation, provider, cacheName, outcome);
        }
    }

    static final class RecordingObserver implements CacheObserver {
        private final List<CacheEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(CacheEvent event) {
            events.add(event);
        }

        List<Observed> events(String cacheName) {
            return events.stream()
                    .filter(CacheOperationCompleted.class::isInstance)
                    .map(CacheOperationCompleted.class::cast)
                    .filter(event -> event.cacheName().equals(cacheName))
                    .map(event -> new Observed(
                            event.operation(), event.provider(), event.cacheName(), event.outcome(), event.elapsed()))
                    .toList();
        }

        long count(String cacheName, CacheOperation operation, CacheOutcome outcome) {
            return events(cacheName).stream()
                    .filter(event -> event.operation() == operation && event.outcome() == outcome)
                    .count();
        }
    }

    private record Completion(Context context, Thread thread, Plan value) {}

    private static CompletableFuture<Completion> completionOf(Future<Plan> future) {
        CompletableFuture<Completion> seen = new CompletableFuture<>();
        future.onComplete(result ->
                seen.complete(new Completion(Vertx.currentContext(), Thread.currentThread(), result.result())));
        return seen;
    }

    private static Future<Plan> callOnContext(Context context, Supplier<Future<Plan>> call) throws Exception {
        CompletableFuture<Future<Plan>> result = new CompletableFuture<>();
        context.runOnContext(ignored -> result.complete(call.get()));
        return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Runs {@code count} threads released together by one latch and returns what each call returned. */
    private static <T> List<T> racers(int count, Supplier<T> call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.get();
                }));
            }
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (java.util.concurrent.Future<T> future : pending) {
                results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Waits for the future to complete (either way) and returns it. */
    private static <T> Future<T> settled(Future<T> future) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        future.onComplete(ignored -> done.countDown());
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "future did not complete");
        return future;
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached in time");
            Thread.sleep(10);
        }
    }

    @Singleton
    @Component(
            modules = {
                CacheCaffeineModule.class,
                ConfigParsingModule.class,
                T011CacheCompositionFixtures.ConfigModule.class,
                ObserverModule.class
            })
    interface DisabledComponent {
        Set<CacheObserver> observers();

        RecordingObserver observer();

        CacheConfig config();
    }

    @Module
    static final class ObserverModule {
        @Provides
        @Singleton
        static RecordingObserver recordingObserver() {
            return new RecordingObserver();
        }

        @Provides
        @IntoSet
        static CacheObserver cacheObserver(RecordingObserver observer) {
            return observer;
        }
    }
}
