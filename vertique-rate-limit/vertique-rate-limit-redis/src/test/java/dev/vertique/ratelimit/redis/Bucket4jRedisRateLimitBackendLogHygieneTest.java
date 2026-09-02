// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisAPI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * T017 TP-008 (i): {@link Bucket4jRedisRateLimitBackend}'s two log sites — {@code issueTtl}'s
 * {@code PEXPIRE}-failure warn and {@code ambiguousFailureResult}'s pre-deadline debug — render
 * only the failure's simple class name, never the raw {@link Throwable} itself (which SLF4J would
 * otherwise render as a message plus stack trace, potentially echoing the physical Redis key).
 *
 * <p>Drives both sites directly through {@link Bucket4jRedisRateLimitBackend}'s package-private
 * constructor with a mocked {@link AsyncProxyManager}/{@link AsyncBucketProxy} and a mocked {@link
 * RedisAPI} TTL client — no real Redis connection, no Bucket4j CAS engine, and no Docker — since
 * only the log-rendering behavior at each site is under test, not the CAS or TTL mechanics
 * themselves (already proven by {@code RateLimitRedisTtlMaintenanceIT} and {@code
 * RateLimitRedisFailureClassificationIT}).
 */
class Bucket4jRedisRateLimitBackendLogHygieneTest {

    private Logger backendLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureBackendLogs() {
        backendLogger = (Logger) LoggerFactory.getLogger(Bucket4jRedisRateLimitBackend.class);
        previousLevel = backendLogger.getLevel();
        backendLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        backendLogger.addAppender(appender);
    }

    @AfterEach
    void releaseBackendLogs() {
        backendLogger.detachAppender(appender);
        appender.stop();
        backendLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("PEXPIRE-failure warn and pre-deadline CAS debug both log the failure class name only,"
            + " never the throwable or the physical key")
    void shouldLogFailureClassNameOnlyNeverTheThrowableItself() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(5, 5, 60_000);

            // --- Site 1 (issueTtl): a genuinely committed consumption whose immediately-following
            // PEXPIRE fails with an exception whose message carries this scenario's real physical
            // key (the "physical-key-derived detail" TP-008 requires never be rendered).
            String pexpireStorageKey = RateLimitRedisTestFixture.storageKey("log-hygiene-pexpire");
            String pexpirePhysicalKey = RedisPhysicalKey.physicalKey(
                    RateLimitRedisTestFixture.NAMESPACE, RateLimitRedisTestFixture.SECRET, pexpireStorageKey);
            PexpireCanaryFailure pexpireFailure =
                    new PexpireCanaryFailure("PEXPIRE failed near key " + pexpirePhysicalKey);

            AsyncProxyManager<String> committingProxyManager =
                    proxyManagerReturning(CompletableFuture.completedFuture(ConsumptionProbe.consumed(4L, 0L)));
            RedisAPI failingTtlClient = mock(RedisAPI.class);
            when(failingTtlClient.pexpire(anyList())).thenReturn(Future.failedFuture(pexpireFailure));

            RateLimitBackend committedBackend = new Bucket4jRedisRateLimitBackend(
                    committingProxyManager,
                    failingTtlClient,
                    vertx,
                    RateLimitRedisTestFixture.NAMESPACE,
                    RateLimitRedisTestFixture.SECRET,
                    2_000L,
                    1_000L);
            RateLimitBackendResult committedResult = RateLimitRedisTestFixture.await(
                    committedBackend.consume(RateLimitRedisTestFixture.request(pexpireStorageKey, algorithm, 1)));
            assertTrue(committedResult.consumed(), "the committed-consumption fixture must actually commit");

            // --- Site 2 (ambiguousFailureResult): a Bucket4j CAS completion that fails exceptionally
            // before the operation deadline, with an exception whose message carries this scenario's
            // own (different) physical key.
            String ambiguousStorageKey = RateLimitRedisTestFixture.storageKey("log-hygiene-ambiguous");
            String ambiguousPhysicalKey = RedisPhysicalKey.physicalKey(
                    RateLimitRedisTestFixture.NAMESPACE, RateLimitRedisTestFixture.SECRET, ambiguousStorageKey);
            CasCanaryFailure casFailure = new CasCanaryFailure("CAS failed near key " + ambiguousPhysicalKey);

            AsyncProxyManager<String> failingProxyManager =
                    proxyManagerReturning(CompletableFuture.failedFuture(casFailure));
            RedisAPI unusedTtlClient = mock(RedisAPI.class);

            RateLimitBackend ambiguousBackend = new Bucket4jRedisRateLimitBackend(
                    failingProxyManager,
                    unusedTtlClient,
                    vertx,
                    RateLimitRedisTestFixture.NAMESPACE,
                    RateLimitRedisTestFixture.SECRET,
                    2_000L,
                    1_000L);
            RateLimitBackendResult ambiguousResult = RateLimitRedisTestFixture.await(
                    ambiguousBackend.consume(RateLimitRedisTestFixture.request(ambiguousStorageKey, algorithm, 1)));
            assertFalse(ambiguousResult.consumed(), "the ambiguous-failure fixture must never commit");

            // Then: exactly one warn (site 1) and one debug (site 2) event were captured, and both
            // decisive assertions hold — class name present, canary/physical key and stack frame
            // absent, no attached throwable proxy at all.
            List<ILoggingEvent> pexpireWarnEvents = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("PEXPIRE failed"))
                    .toList();
            List<ILoggingEvent> casDebugEvents = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .filter(event -> event.getFormattedMessage().contains("Redis CAS completed exceptionally"))
                    .toList();
            assertEquals(1, pexpireWarnEvents.size(), "exactly one PEXPIRE-failure warn must be logged");
            assertEquals(1, casDebugEvents.size(), "exactly one pre-deadline CAS debug must be logged");

            ILoggingEvent pexpireEvent = pexpireWarnEvents.get(0);
            assertTrue(
                    pexpireEvent.getFormattedMessage().contains(PexpireCanaryFailure.class.getSimpleName()),
                    "the PEXPIRE-failure warn must name the failure's class");
            assertFalse(
                    pexpireEvent.getFormattedMessage().contains(pexpirePhysicalKey),
                    "the PEXPIRE-failure warn must never render the physical key");
            assertNull(
                    pexpireEvent.getThrowableProxy(),
                    "the PEXPIRE-failure warn must never attach the raw throwable (which would carry a stack"
                            + " trace)");

            ILoggingEvent casEvent = casDebugEvents.get(0);
            assertTrue(
                    casEvent.getFormattedMessage().contains(CasCanaryFailure.class.getSimpleName()),
                    "the pre-deadline CAS debug must name the failure's class");
            assertFalse(
                    casEvent.getFormattedMessage().contains(ambiguousPhysicalKey),
                    "the pre-deadline CAS debug must never render the physical key");
            assertNull(
                    casEvent.getThrowableProxy(),
                    "the pre-deadline CAS debug must never attach the raw throwable (which would carry a stack"
                            + " trace)");

            // Belt-and-braces: across every captured event, neither physical key ever appears and no
            // event carries an attached throwable.
            for (ILoggingEvent event : appender.list) {
                assertFalse(
                        event.getFormattedMessage().contains(pexpirePhysicalKey),
                        "no log event may ever render the PEXPIRE scenario's physical key");
                assertFalse(
                        event.getFormattedMessage().contains(ambiguousPhysicalKey),
                        "no log event may ever render the ambiguous-CAS scenario's physical key");
                assertNull(event.getThrowableProxy(), "no log event from this backend may carry a throwable proxy");
            }
        } finally {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    /**
     * Builds a mocked {@link AsyncProxyManager} whose single {@link AsyncBucketProxy} answers every
     * {@code tryConsumeAndReturnRemaining} call with the given outcome — standing in for a genuine
     * Bucket4j CAS engine, which this test deliberately never touches.
     */
    @SuppressWarnings("unchecked")
    private static AsyncProxyManager<String> proxyManagerReturning(CompletableFuture<ConsumptionProbe> outcome) {
        AsyncBucketProxy bucketProxy = mock(AsyncBucketProxy.class);
        when(bucketProxy.tryConsumeAndReturnRemaining(anyLong())).thenReturn(outcome);
        AsyncProxyManager<String> proxyManager = mock(AsyncProxyManager.class);
        when(proxyManager.getProxy(anyString(), any())).thenReturn(bucketProxy);
        return proxyManager;
    }

    /** Marker failure standing in for a real {@code PEXPIRE} transport exception. */
    private static final class PexpireCanaryFailure extends RuntimeException {
        PexpireCanaryFailure(String message) {
            super(message);
        }
    }

    /** Marker failure standing in for a real pre-deadline Bucket4j CAS exception. */
    private static final class CasCanaryFailure extends RuntimeException {
        CasCanaryFailure(String message) {
            super(message);
        }
    }
}
