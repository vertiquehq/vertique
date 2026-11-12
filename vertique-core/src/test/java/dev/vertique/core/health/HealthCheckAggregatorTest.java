// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link HealthCheckAggregator}: the aggregation rule, the entry order, the per-check
 * timeout and the containment of every failure a check can produce, evaluated in process with no
 * HTTP layer and, except where a timer is needed, with no Vert.x context on the calling thread.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>Aggregation: empty set, all-UP, all-DOWN, mixed; entries in the set's iteration order; every
 *       check started before any is waited for
 *   <li>Error handling: failed futures, synchronous exceptions, message-less and hostile throwables, a
 *       check returning no future, a future completing with no result, a {@code null} or throwing
 *       {@code name()}, and the {@code Error} policy: synchronous propagation from {@code name()} or
 *       {@code check()}, a failed future from report assembly
 *   <li>Sibling isolation: a misbehaving check never hides a slower sibling's result
 *   <li>Timeout: a check that never settles is {@code DOWN} once the configured timeout fires; the
 *       timeout is validated at construction
 * </ul>
 *
 * <p>The class carries an explicit {@link Timeout} because a regression in this area manifests as a
 * report that is never produced.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
class HealthCheckAggregatorTest {

    private final HealthCheckAggregator aggregator = new HealthCheckAggregator();

    // --- Test HealthCheck implementations ---

    static class UpCheck implements HealthCheck {
        private final String name;

        UpCheck(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    static class DownCheck implements HealthCheck {
        private final String name;
        private final String error;

        DownCheck(String name, String error) {
            this.name = name;
            this.error = error;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.down(error));
        }
    }

    static class UpCheckWithData implements HealthCheck {
        @Override
        public String name() {
            return "detailed";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up(Map.of("pool.active", 2)));
        }
    }

    static class FailingCheck implements HealthCheck {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new RuntimeException("connection refused"));
        }
    }

    static class ThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new RuntimeException("unexpected error");
        }
    }

    /**
     * Fails with a throwable carrying no message. The {@link IllegalStateException} is constructed
     * deliberately without a message: a spontaneous {@code NullPointerException} on Java 21 carries
     * a helpful message that would defuse the null-message path.
     */
    static class NoMessageFailingCheck implements HealthCheck {
        @Override
        public String name() {
            return "failing-nomsg";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new IllegalStateException());
        }
    }

    static class NoMessageThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "throwing-nomsg";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new IllegalStateException();
        }
    }

    /** A throwable whose {@link #getMessage()} itself throws an exception. */
    static final class HostileMessageException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new RuntimeException("getMessage exploded");
        }
    }

    static class HostileMessageCheck implements HealthCheck {
        @Override
        public String name() {
            return "hostile";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new HostileMessageException());
        }
    }

    /** A throwable whose {@link #getMessage()} throws an {@link Error}, which no interior guard absorbs. */
    static final class ErrorMessageException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new Error("getMessage blew up");
        }
    }

    static class ErrorMessageCheck implements HealthCheck {
        @Override
        public String name() {
            return "error-message";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.failedFuture(new ErrorMessageException());
        }
    }

    /** Violates the SPI by returning {@code null} instead of a future. */
    static class NullReturningCheck implements HealthCheck {
        @Override
        public String name() {
            return "null-future";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return null;
        }
    }

    /** Violates the SPI by completing its future with {@code null}. */
    static class NullResultCheck implements HealthCheck {
        @Override
        public String name() {
            return "null-result";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(null);
        }
    }

    /** Violates the non-null {@code name()} contract but is otherwise healthy. */
    static class NullNameCheck implements HealthCheck {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String name() {
            return null;
        }

        @Override
        public Future<HealthCheckResult> check() {
            invocations.incrementAndGet();
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    /** A check whose {@code name()} throws an exception; records whether {@code check()} ran. */
    static class ThrowingNameCheck implements HealthCheck {
        final AtomicInteger nameCalls = new AtomicInteger();
        final AtomicInteger checkCalls = new AtomicInteger();

        @Override
        public String name() {
            nameCalls.incrementAndGet();
            throw new IllegalStateException("name blew up");
        }

        @Override
        public Future<HealthCheckResult> check() {
            checkCalls.incrementAndGet();
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    static class ErrorThrowingNameCheck implements HealthCheck {
        @Override
        public String name() {
            throw new Error("name blew up");
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Future.succeededFuture(HealthCheckResult.up());
        }
    }

    static class ErrorThrowingCheck implements HealthCheck {
        @Override
        public String name() {
            return "error-throwing";
        }

        @Override
        public Future<HealthCheckResult> check() {
            throw new Error("check blew up");
        }
    }

    /** Never completes, so only the timeout can settle it. */
    static class SlowCheck implements HealthCheck {
        @Override
        public String name() {
            return "slow";
        }

        @Override
        public Future<HealthCheckResult> check() {
            return Promise.<HealthCheckResult>promise().future();
        }
    }

    /** Completes UP after a delay on a Vert.x timer, to prove a sibling is waited for. */
    static class DelayedUpCheck implements HealthCheck {
        private final Vertx vertx;
        private final String name;
        private final long delayMs;

        DelayedUpCheck(Vertx vertx, String name, long delayMs) {
            this.vertx = vertx;
            this.name = name;
            this.delayMs = delayMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            Promise<HealthCheckResult> promise = Promise.promise();
            vertx.setTimer(delayMs, id -> promise.complete(HealthCheckResult.up()));
            return promise.future();
        }
    }

    /** Hands its promise to the test so the test decides when, and in what order, checks settle. */
    static class ManualCheck implements HealthCheck {
        private final String name;
        final Promise<HealthCheckResult> promise = Promise.promise();
        final AtomicInteger started = new AtomicInteger();

        ManualCheck(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<HealthCheckResult> check() {
            started.incrementAndGet();
            return promise.future();
        }
    }

    // --- Helpers ---

    private static HealthReport.Entry entryNamed(HealthReport report, String name) {
        return report.checks().stream()
                .filter(entry -> name.equals(entry.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry named " + name + " in " + report));
    }

    /**
     * Evaluates on the calling thread, which has no Vert.x context, and returns the report that every
     * fixture with already-settled futures must produce inline.
     */
    private HealthReport evaluateNow(Set<HealthCheck> checks) {
        Future<HealthReport> future = aggregator.evaluate(checks);
        assertTrue(future.succeeded(), "settled checks must produce the report synchronously: " + future);
        return future.result();
    }

    // --- Aggregation ---

    @Nested
    class Aggregation {

        @Test
        @DisplayName("an empty set is UP with no entries, synchronously")
        void emptySetIsUp() {
            HealthReport report = evaluateNow(Set.of());
            assertEquals(HealthStatus.UP, report.status());
            assertTrue(report.isUp());
            assertTrue(report.checks().isEmpty());
        }

        @Test
        @DisplayName("UP when every check is UP, with one entry per check")
        void allChecksPass() {
            HealthReport report = evaluateNow(Set.of(new UpCheck("db"), new UpCheck("cache")));
            assertEquals(HealthStatus.UP, report.status());
            assertEquals(2, report.checks().size());
            report.checks().forEach(entry -> assertEquals(HealthStatus.UP, entry.status()));
        }

        @Test
        @DisplayName("DOWN when any check is DOWN, keeping the check's own result")
        void anyCheckFails() {
            HealthReport report = evaluateNow(Set.of(new DownCheck("db", "timeout")));
            assertEquals(HealthStatus.DOWN, report.status());
            assertFalse(report.isUp());
            HealthReport.Entry entry = report.checks().get(0);
            assertEquals("db", entry.name());
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals("timeout", entry.result().data().get("error"));
        }

        @Test
        @DisplayName("DOWN with every entry present when UP and DOWN are mixed")
        void mixedChecks() {
            HealthReport report = evaluateNow(Set.of(new UpCheck("cache"), new DownCheck("database", "timeout")));
            assertEquals(HealthStatus.DOWN, report.status());
            assertEquals(2, report.checks().size());
            assertEquals(HealthStatus.UP, entryNamed(report, "cache").status());
            assertEquals(HealthStatus.DOWN, entryNamed(report, "database").status());
        }

        @Test
        @DisplayName("a check's result is carried as it produced it, data included")
        void carriesTheCheckResult() {
            HealthReport report = evaluateNow(Set.of(new UpCheckWithData()));
            HealthReport.Entry entry = entryNamed(report, "detailed");
            assertEquals(HealthStatus.UP, entry.status());
            assertEquals(Map.of("pool.active", 2), entry.result().data());
        }

        @Test
        @DisplayName("entries follow the set's iteration order")
        void entriesFollowSetOrder() {
            List<String> names = List.of("zulu", "yankee", "xray", "whiskey", "victor");
            Set<HealthCheck> checks = new LinkedHashSet<>();
            names.forEach(name -> checks.add(new UpCheck(name)));

            HealthReport report = evaluateNow(checks);

            assertEquals(
                    names,
                    report.checks().stream().map(HealthReport.Entry::name).toList());
        }

        @Test
        @DisplayName("every check is started before any is waited for, and the report waits for all")
        void checksStartTogether(VertxTestContext ctx) {
            ManualCheck first = new ManualCheck("first");
            ManualCheck second = new ManualCheck("second");
            ManualCheck third = new ManualCheck("third");
            Set<HealthCheck> checks = new LinkedHashSet<>(List.of(first, second, third));

            Future<HealthReport> report = aggregator.evaluate(checks);

            assertEquals(1, first.started.get(), "first check must be started");
            assertEquals(1, second.started.get(), "second check must be started before the first settles");
            assertEquals(1, third.started.get(), "third check must be started before the first settles");
            assertFalse(report.isComplete(), "no report while a check is pending");

            second.promise.complete(HealthCheckResult.down("broken"));
            assertFalse(report.isComplete(), "a DOWN check must not settle the report while siblings are pending");
            third.promise.complete(HealthCheckResult.up());
            assertFalse(report.isComplete(), "the report must wait for the last pending check");
            first.promise.complete(HealthCheckResult.up());

            report.onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertEquals(HealthStatus.DOWN, result.status());
                    assertEquals(
                            List.of("first", "second", "third"),
                            result.checks().stream()
                                    .map(HealthReport.Entry::name)
                                    .toList());
                    assertEquals(HealthStatus.DOWN, entryNamed(result, "second").status());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("the set is read once; a later change is not observed")
        void setIsSnapshotted(VertxTestContext ctx) {
            ManualCheck pending = new ManualCheck("pending");
            Set<HealthCheck> checks = new LinkedHashSet<>(List.of(pending));

            Future<HealthReport> report = aggregator.evaluate(checks);
            checks.add(new DownCheck("late", "added after evaluation started"));
            pending.promise.complete(HealthCheckResult.up());

            report.onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertEquals(HealthStatus.UP, result.status());
                    assertEquals(1, result.checks().size());
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Error handling ---

    @Nested
    class ErrorHandling {

        @Test
        @DisplayName("a failed future is DOWN with the throwable's message")
        void failedFuture() {
            HealthReport report = evaluateNow(Set.of(new FailingCheck()));
            assertEquals(HealthStatus.DOWN, report.status());
            HealthReport.Entry entry = entryNamed(report, "failing");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals("connection refused", entry.result().data().get("error"));
        }

        @Test
        @DisplayName("a synchronous exception from check() is DOWN with its message")
        void throwingCheck() {
            HealthReport report = evaluateNow(Set.of(new ThrowingCheck()));
            assertEquals(HealthStatus.DOWN, report.status());
            HealthReport.Entry entry = entryNamed(report, "throwing");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals("unexpected error", entry.result().data().get("error"));
        }

        @Test
        @DisplayName("a message-less failed future is DOWN with the throwable's class name")
        void failedFutureWithNullMessage() {
            HealthReport report = evaluateNow(Set.of(new NoMessageFailingCheck()));
            HealthReport.Entry entry = entryNamed(report, "failing-nomsg");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals(
                    "java.lang.IllegalStateException", entry.result().data().get("error"));
        }

        @Test
        @DisplayName("a message-less synchronous exception is DOWN with the throwable's class name")
        void throwingCheckWithNullMessage() {
            HealthReport report = evaluateNow(Set.of(new NoMessageThrowingCheck()));
            HealthReport.Entry entry = entryNamed(report, "throwing-nomsg");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals(
                    "java.lang.IllegalStateException", entry.result().data().get("error"));
        }

        @Test
        @DisplayName("a throwable whose getMessage() throws an exception is DOWN with its class name")
        void hostileGetMessage() {
            HealthReport report = evaluateNow(Set.of(new HostileMessageCheck()));
            HealthReport.Entry entry = entryNamed(report, "hostile");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals(
                    HostileMessageException.class.getName(),
                    entry.result().data().get("error"));
        }

        @Test
        @DisplayName("a check returning a null future is DOWN")
        void nullReturningCheck() {
            HealthReport report = evaluateNow(Set.of(new NullReturningCheck()));
            assertEquals(HealthStatus.DOWN, report.status());
            HealthReport.Entry entry = entryNamed(report, "null-future");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertNotNull(entry.result().data().get("error"), "the SPI violation must carry a diagnostic");
        }

        @Test
        @DisplayName("a future completing with a null result is DOWN")
        void nullResultCheck() {
            HealthReport report = evaluateNow(Set.of(new NullResultCheck()));
            assertEquals(HealthStatus.DOWN, report.status());
            HealthReport.Entry entry = entryNamed(report, "null-result");
            assertEquals(HealthStatus.DOWN, entry.status());
            assertTrue(entry.result().data().isEmpty());
        }

        @Test
        @DisplayName("a null name() is reported under the class name and the check still runs")
        void nullNameFallsBackToClassName() {
            NullNameCheck check = new NullNameCheck();
            HealthReport report = evaluateNow(Set.of(check));
            assertEquals(1, report.checks().size());
            HealthReport.Entry entry = report.checks().get(0);
            assertEquals(NullNameCheck.class.getName(), entry.name());
            assertEquals(HealthStatus.UP, entry.status(), "the check keeps its own result");
            assertEquals(1, check.invocations.get(), "the check must run");
        }

        @Test
        @DisplayName("a throwing name() is DOWN under the class name and the check is not run")
        void throwingNameIsDownWithoutRunning() {
            ThrowingNameCheck check = new ThrowingNameCheck();
            HealthReport report = evaluateNow(Set.of(check));
            assertEquals(HealthStatus.DOWN, report.status());
            HealthReport.Entry entry = report.checks().get(0);
            assertEquals(ThrowingNameCheck.class.getName(), entry.name());
            assertEquals(HealthStatus.DOWN, entry.status());
            assertEquals("name blew up", entry.result().data().get("error"));
            assertEquals(1, check.nameCalls.get(), "name() is resolved exactly once");
            assertEquals(0, check.checkCalls.get(), "a check that cannot name itself is not run");
        }

        @Test
        @DisplayName("an Error thrown by name() propagates synchronously")
        void errorFromNamePropagates() {
            Error error = assertThrows(Error.class, () -> aggregator.evaluate(Set.of(new ErrorThrowingNameCheck())));
            assertEquals("name blew up", error.getMessage());
        }

        @Test
        @DisplayName("an Error thrown by check() propagates synchronously")
        void errorFromCheckPropagates() {
            Error error = assertThrows(Error.class, () -> aggregator.evaluate(Set.of(new ErrorThrowingCheck())));
            assertEquals("check blew up", error.getMessage());
        }

        @Test
        @DisplayName("an Error raised while describing a failure fails the report instead of hanging it")
        void errorFromGetMessageFailsTheReport() {
            Future<HealthReport> report = aggregator.evaluate(Set.of(new ErrorMessageCheck()));
            assertTrue(report.failed(), "the report must settle, as a failure: " + report);
            assertInstanceOf(Error.class, report.cause());
            assertEquals("getMessage blew up", report.cause().getMessage());
        }

        @Test
        @DisplayName("a null set is rejected")
        void nullSetRejected() {
            assertThrows(NullPointerException.class, () -> aggregator.evaluate(null));
        }

        @Test
        @DisplayName("a set holding a null check is rejected before anything is started")
        void nullElementRejected() {
            assertThrows(NullPointerException.class, () -> aggregator.evaluate(Collections.singleton(null)));
        }
    }

    // --- Sibling isolation ---

    @Nested
    class SiblingIsolation {

        @Test
        @DisplayName("a failing check does not hide a slower sibling's UP result")
        void siblingSurvivesAFailedCheck(Vertx vertx, VertxTestContext ctx) {
            Set<HealthCheck> checks =
                    Set.of(new NoMessageFailingCheck(), new DelayedUpCheck(vertx, "delayed-sibling", 100L));
            aggregator.evaluate(checks).onComplete(ctx.succeeding(report -> {
                ctx.verify(() -> {
                    assertEquals(HealthStatus.DOWN, report.status());
                    assertEquals(2, report.checks().size(), "both checks must be reported");
                    assertEquals(
                            HealthStatus.UP,
                            entryNamed(report, "delayed-sibling").status());
                    assertEquals(
                            HealthStatus.DOWN,
                            entryNamed(report, "failing-nomsg").status());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("a check whose name() throws does not hide a slower sibling")
        void throwingNameDoesNotStarveSiblings(Vertx vertx, VertxTestContext ctx) {
            Set<HealthCheck> checks =
                    Set.of(new ThrowingNameCheck(), new DelayedUpCheck(vertx, "delayed-sibling", 100L));
            aggregator.evaluate(checks).onComplete(ctx.succeeding(report -> {
                ctx.verify(() -> {
                    assertEquals(2, report.checks().size(), "both checks must be reported");
                    assertEquals(
                            HealthStatus.UP,
                            entryNamed(report, "delayed-sibling").status());
                    assertEquals(
                            HealthStatus.DOWN,
                            entryNamed(report, ThrowingNameCheck.class.getName())
                                    .status());
                });
                ctx.completeNow();
            }));
        }
    }

    // --- Timeout ---

    @Nested
    class CheckTimeout {

        @Test
        @DisplayName("a check that exceeds the timeout is DOWN, described by the timeout")
        void slowCheckExceedingTimeoutIsDown(VertxTestContext ctx) {
            new HealthCheckAggregator(1L).evaluate(Set.of(new SlowCheck())).onComplete(ctx.succeeding(report -> {
                ctx.verify(() -> {
                    assertEquals(HealthStatus.DOWN, report.status());
                    HealthReport.Entry entry = entryNamed(report, "slow");
                    assertEquals(HealthStatus.DOWN, entry.status());
                    Object error = entry.result().data().get("error");
                    assertNotNull(error, "a timed-out check must carry a diagnostic");
                    assertTrue(
                            String.valueOf(error).contains(TimeoutException.class.getSimpleName())
                                    || String.valueOf(error).toLowerCase().contains("timeout"),
                            "the diagnostic must name the timeout, got: " + error);
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("the default timeout is five seconds and is exposed")
        void defaultTimeout() {
            assertEquals(5L, HealthCheckAggregator.DEFAULT_CHECK_TIMEOUT_SECONDS);
            assertEquals(5L, new HealthCheckAggregator().checkTimeoutSeconds());
            assertEquals(30L, new HealthCheckAggregator(30L).checkTimeoutSeconds());
        }

        @Test
        @DisplayName("a zero or negative timeout is rejected at construction")
        void nonPositiveTimeoutRejected() {
            IllegalArgumentException zero =
                    assertThrows(IllegalArgumentException.class, () -> new HealthCheckAggregator(0L));
            assertTrue(zero.getMessage().contains("checkTimeoutSeconds"), zero.getMessage());
            assertThrows(IllegalArgumentException.class, () -> new HealthCheckAggregator(-1L));
        }
    }
}
