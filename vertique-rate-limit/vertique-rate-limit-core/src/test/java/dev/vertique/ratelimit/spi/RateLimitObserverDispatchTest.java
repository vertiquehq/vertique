// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.RateLimitCoreTestFixtures;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001: every completed rate-limit decision dispatches synchronously to each bound {@link
 * RateLimitObserver}; a throwing observer never affects the admission decision or later
 * observers; every emitted event carries only bounded policy-level fields, never the raw key,
 * caller identity, or an exception (contracts/rate-limit-runtime.md, "Observer SPI"; D016).
 */
class RateLimitObserverDispatchTest {

    private static final String POLICY_NAME = "probe";
    private static final String SENSITIVE_KEY_COMPONENT = "tenant-42-secret";
    private static final String OBSERVER_FAILURE_MESSAGE = "observer-failure-must-never-leak-into-an-event";

    static Stream<MatrixRow> t005ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldReturnTheDecisionUnaffectedWhenNoObserverIsBound",
                        RateLimitObserverDispatchTest::shouldReturnTheDecisionUnaffectedWhenNoObserverIsBound),
                new MatrixRow(
                        "shouldIsolateAThrowingObserverFromAdmissionAndFromLaterObservers",
                        RateLimitObserverDispatchTest
                                ::shouldIsolateAThrowingObserverFromAdmissionAndFromLaterObservers),
                new MatrixRow(
                        "shouldCarryOnlyBoundedPolicyLevelFieldsNeverTheRawKeyIdentityOrAnException",
                        RateLimitObserverDispatchTest
                                ::shouldCarryOnlyBoundedPolicyLevelFieldsNeverTheRawKeyIdentityOrAnException));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t005ContractMatrix")
    @DisplayName("enforces the T005 observer-dispatch contract matrix")
    void shouldEnforceT005ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: zero observers bound ---

    private static void shouldReturnTheDecisionUnaffectedWhenNoObserverIsBound() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            RateLimiters rateLimiters = RateLimitCoreTestFixtures.singlePolicy(vertx, probePolicy(), Set.of());
            RateLimitDecision decision = acquireOnce(rateLimiters);

            assertThat(decision.outcome())
                    .as("decision outcome with zero observers bound")
                    .isEqualTo(RateLimitOutcome.PERMITTED);
        } finally {
            vertx.close();
        }
    }

    // --- Row 2: three observers — first throws, remaining two record every received event ---

    private static void shouldIsolateAThrowingObserverFromAdmissionAndFromLaterObservers() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            RecordingObserver secondObserver = new RecordingObserver();
            RecordingObserver thirdObserver = new RecordingObserver();
            Set<RateLimitObserver> observers = threeObserverComposition(secondObserver, thirdObserver);
            RateLimiters rateLimiters = RateLimitCoreTestFixtures.singlePolicy(vertx, probePolicy(), observers);

            RateLimitDecision decision = acquireOnce(rateLimiters);

            assertThat(decision.outcome())
                    .as("decision outcome unaffected by the throwing observer")
                    .isEqualTo(RateLimitOutcome.PERMITTED);
            assertThat(secondObserver.received())
                    .as("second observer (after the throwing one) received event count")
                    .hasSize(1);
            assertThat(thirdObserver.received())
                    .as("third observer (after the throwing one) received event count")
                    .hasSize(1);
        } finally {
            vertx.close();
        }
    }

    private static Set<RateLimitObserver> threeObserverComposition(
            RecordingObserver secondObserver, RecordingObserver thirdObserver) {
        Set<RateLimitObserver> observers = new LinkedHashSet<>();
        observers.add(new ThrowingObserver());
        observers.add(secondObserver);
        observers.add(thirdObserver);
        return observers;
    }

    // --- Row 3: one observer that captures the event it receives, then throws ---

    private static void shouldCarryOnlyBoundedPolicyLevelFieldsNeverTheRawKeyIdentityOrAnException() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            RecordingThrowingObserver observer = new RecordingThrowingObserver();
            RateLimiters rateLimiters = RateLimitCoreTestFixtures.singlePolicy(vertx, probePolicy(), Set.of(observer));

            RateLimitDecision decision = acquireOnce(rateLimiters);

            assertThat(decision.outcome())
                    .as("decision outcome unaffected by the throwing observer")
                    .isEqualTo(RateLimitOutcome.PERMITTED);
            assertThat(observer.received()).as("captured event count").hasSize(1);

            RateLimitEvent event = observer.received().get(0);
            String eventText = event.toString();
            assertThat(eventText)
                    .as("captured event's toString() never contains the raw key literal")
                    .doesNotContain(SENSITIVE_KEY_COMPONENT);
            assertThat(eventText)
                    .as("captured event's toString() never contains the observer's own failure text")
                    .doesNotContain(OBSERVER_FAILURE_MESSAGE)
                    .doesNotContain("Exception")
                    .doesNotContain("Throwable");
        } finally {
            vertx.close();
        }
    }

    // --- Shared fixtures ---

    private static RateLimitPolicy probePolicy() {
        return new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofSeconds(60))));
    }

    private static RateLimitDecision acquireOnce(RateLimiters rateLimiters) throws Exception {
        RateLimitKey key = RateLimitKey.of(SENSITIVE_KEY_COMPONENT);
        RateLimiter limiter = rateLimiters.limiter(POLICY_NAME);
        return await(limiter.acquire(key));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Records every event it receives; never throws. */
    private static final class RecordingObserver implements RateLimitObserver {
        private final List<RateLimitEvent> received = new ArrayList<>();

        @Override
        public void onEvent(RateLimitEvent event) {
            received.add(event);
        }

        List<RateLimitEvent> received() {
            return received;
        }
    }

    /** Throws unconditionally; never records anything. */
    private static final class ThrowingObserver implements RateLimitObserver {
        @Override
        public void onEvent(RateLimitEvent event) {
            throw new AssertionError(OBSERVER_FAILURE_MESSAGE);
        }
    }

    /** Records the event it receives, then throws — proves the captured payload is clean. */
    private static final class RecordingThrowingObserver implements RateLimitObserver {
        private final List<RateLimitEvent> received = new ArrayList<>();

        @Override
        public void onEvent(RateLimitEvent event) {
            received.add(event);
            throw new AssertionError(OBSERVER_FAILURE_MESSAGE);
        }

        List<RateLimitEvent> received() {
            return received;
        }
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }
}
