// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.examples.services.service.CompositionProbeServiceHandler;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.CircuitCallRejected;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Characterizes the shipped cache, quota, and resilience aspect ordering through one proxy. */
@ExtendWith(VertxExtension.class)
@Timeout(20)
class AspectCompositionIT {

    private static final String HIT_KEY = "hit";
    private static final String MISS_SUCCESS_KEY = "miss-success";
    private static final String BREAKER_KEY = "breaker";

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(config());

    @Test
    @DisplayName("pins cache-hit, cache-miss, breaker-open, and retry-exhausted paths")
    void pinsCacheHitCacheMissBreakerOpenAndRetryExhaustedPaths() {
        AppComponent component = app.<AppComponent>component();
        CompositionProbeServiceHandler handler = component.compositionProbeServiceHandler();
        CompositionEventCollector events = component.compositionEventCollector();

        assertNotNull(
                handler.probe(HIT_KEY).toCompletionStage().toCompletableFuture().join());
        events.clear();

        int hitStart = events.size();
        assertEquals(
                HIT_KEY,
                handler.probe(HIT_KEY)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join()
                        .key());
        List<Object> hitEvents = events.since(hitStart);
        assertCount(hitEvents, RateLimitDecisionCompleted.class, 1);
        assertCount(hitEvents, ResilienceEvent.class, 0);
        assertCount(hitEvents, CacheOperationCompleted.class, 1);
        assertCacheOutcome(hitEvents, CacheOutcome.HIT);

        int missStart = events.size();
        assertEquals(
                MISS_SUCCESS_KEY,
                handler.probe(MISS_SUCCESS_KEY)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join()
                        .key());
        List<Object> missEvents = events.since(missStart);
        assertCount(missEvents, RateLimitDecisionCompleted.class, 1);
        assertCount(missEvents, RetryScheduled.class, 2);
        assertCount(missEvents, ExecutionCompleted.class, 1);
        assertCacheOutcome(missEvents, CacheOutcome.MISS);
        assertCacheOutcome(missEvents, CacheOutcome.SUCCESS);
        assertTrue(
                indexOf(missEvents, RateLimitDecisionCompleted.class) < indexOf(missEvents, ExecutionCompleted.class));
        assertTrue(indexOf(missEvents, ExecutionCompleted.class)
                < indexOf(
                        missEvents,
                        event -> event instanceof CacheOperationCompleted completed
                                && completed.operation() == CacheOperation.PUT
                                && completed.outcome() == CacheOutcome.SUCCESS));
        assertEquals(3, handler.attemptsFor(MISS_SUCCESS_KEY));

        int breakerStart = events.size();
        assertThrowsCompletion(handler, BREAKER_KEY);
        List<Object> breakerEvents = events.since(breakerStart);
        assertCount(breakerEvents, RateLimitDecisionCompleted.class, 1);
        assertCount(breakerEvents, RetryExhausted.class, 1);
        assertCount(breakerEvents, CircuitStateChanged.class, 1);
        assertCount(breakerEvents, CacheOperationCompleted.class, 1);
        assertCacheOutcome(breakerEvents, CacheOutcome.MISS);
        assertFalse(hasCachePut(breakerEvents));
        assertEquals(3, handler.attemptsFor(BREAKER_KEY));

        int rejectedStart = events.size();
        assertThrowsCompletion(handler, BREAKER_KEY);
        List<Object> rejectedEvents = events.since(rejectedStart);
        assertCount(rejectedEvents, RateLimitDecisionCompleted.class, 1);
        assertCount(rejectedEvents, CircuitCallRejected.class, 1);
        assertCount(rejectedEvents, AttemptStarted.class, 0);
        assertTrue(indexOf(rejectedEvents, RateLimitDecisionCompleted.class)
                < indexOf(rejectedEvents, CircuitCallRejected.class));
        assertEquals(3, handler.attemptsFor(BREAKER_KEY));
    }

    private static JsonObject config() {
        return new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("management", new JsonObject().put("enabled", false))
                .put("resilience", ResilienceTestPolicies.probeConfig())
                .put("cache", cacheConfig())
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put("rate-limit-probe-shared", RateLimitTestPolicies.probeShared())
                                                .put("composition", RateLimitTestPolicies.composition())))
                .put(
                        "services",
                        new JsonObject()
                                .put(
                                        "contracts",
                                        new JsonObject()
                                                .put(
                                                        "resilience",
                                                        new JsonObject()
                                                                .put("probe", new JsonObject().put("instances", 1)))
                                                .put(
                                                        "composition",
                                                        new JsonObject()
                                                                .put("probe", new JsonObject().put("instances", 1)))
                                                .put(
                                                        "rate-limit",
                                                        new JsonObject()
                                                                .put("probe", new JsonObject().put("instances", 1)))
                                                .put(
                                                        "cache",
                                                        new JsonObject()
                                                                .put("probe", new JsonObject().put("instances", 1)))));
    }

    private static JsonObject cacheConfig() {
        return new JsonObject()
                .put("enabled", true)
                .put("defaultMode", "LOCAL")
                .put("defaultTtlSeconds", 60)
                .put("maxTtlSeconds", 86_400)
                .put("jsonProfile", "vertx")
                .put("maxKeyBytes", 1_024)
                .put("maxValueBytes", 1_048_576)
                .put("maximumEntries", 10_000)
                .put("backendTimeoutMs", 100)
                .put(
                        "caches",
                        new JsonObject()
                                .put(
                                        "cache-probe",
                                        new JsonObject()
                                                .put("mode", "LOCAL")
                                                .put("ttlSeconds", 60)
                                                .put("jsonProfile", "vertx"))
                                .put(
                                        "composition",
                                        new JsonObject()
                                                .put("mode", "LOCAL")
                                                .put("ttlSeconds", 60)
                                                .put("jsonProfile", "vertx")));
    }

    private static void assertThrowsCompletion(CompositionProbeServiceHandler handler, String key) {
        try {
            handler.probe(key).toCompletionStage().toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException expected) {
            return;
        }
        throw new AssertionError("probe unexpectedly succeeded for " + key);
    }

    private static void assertCacheOutcome(List<Object> events, CacheOutcome outcome) {
        assertTrue(
                events.stream()
                        .anyMatch(event ->
                                event instanceof CacheOperationCompleted completed && completed.outcome() == outcome),
                () -> "missing cache outcome " + outcome + " in " + events);
    }

    private static boolean hasCachePut(List<Object> events) {
        return events.stream()
                .anyMatch(event -> event instanceof CacheOperationCompleted completed
                        && completed.operation() == CacheOperation.PUT);
    }

    private static void assertCount(List<Object> events, Class<?> type, long expected) {
        assertEquals(expected, events.stream().filter(type::isInstance).count(), () -> "events: " + events);
    }

    private static int indexOf(List<Object> events, Class<?> type) {
        return indexOf(events, type::isInstance);
    }

    private static int indexOf(List<Object> events, Predicate<Object> match) {
        for (int i = 0; i < events.size(); i++) {
            if (match.test(events.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
