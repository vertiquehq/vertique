// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.core.extension.ExtensionPhase;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ordering, threading, failure, single-use, and shutdown semantics of
 * {@link ContributorRunner}.
 */
class ContributorRunnerTest {

    @BeforeEach
    void resetState() {
        TestContributorState.reset();
        FakeVertxMetrics.resetCreated();
    }

    // --- Ordering ---

    @Nested
    @DisplayName("Ordering")
    class OrderingTests {

        @Test
        @DisplayName(
                "phase dominates priority — SYSTEM_FIRST contributor runs before APPLICATION contributors regardless of numeric priority")
        void sortsByPhaseBeforePriority() {
            // SystemFirstContributor has phase=SYSTEM_FIRST and priority=999 (high) but still
            // must sort before APPLICATION-phase contributors with low priorities.
            VertxBuilderContributor systemFirst = new SystemFirstHighPriorityContributor();
            VertxBuilderContributor alpha = new OrderedAlphaContributor(); // APPLICATION, priority=20
            VertxBuilderContributor beta = new OrderedBetaContributor(); // APPLICATION, priority=10

            ContributorRunner runner = new ContributorRunner(List.of(alpha, beta, systemFirst));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());

            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);

            // Expected order: systemFirst (SYSTEM_FIRST, prio 999), then beta (APPLICATION, prio 10), then alpha
            // (APPLICATION, prio 20)
            assertEquals(
                    List.of("system:contribute", "beta:contribute", "alpha:contribute"),
                    List.copyOf(TestContributorState.invocations));
        }

        @Test
        @DisplayName("within same phase, lower priority value runs first")
        void sortsByPriorityWithinPhase() {
            // Beta priority=10 must run before Alpha priority=20, both APPLICATION phase
            ContributorRunner runner =
                    new ContributorRunner(List.of(new OrderedAlphaContributor(), new OrderedBetaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);

            assertEquals(List.of("beta:contribute", "alpha:contribute"), List.copyOf(TestContributorState.invocations));
        }

        @Test
        @DisplayName("equal phase and priority fall back to orderKey (FQCN) for stable ordering")
        void sortsByOrderKeyAsTieBreak() {
            // Two contributors with same phase and priority; orderKey (FQCN) is the tie-break.
            // dev.vertique.bootstrap.EarlyOrderKeyContributor < dev.vertique.bootstrap.LateOrderKeyContributor
            // alphabetically, so Early must run first.
            EarlyOrderKeyContributor early = new EarlyOrderKeyContributor();
            LateOrderKeyContributor late = new LateOrderKeyContributor();

            // Pass them in reverse FQCN order to verify sorting corrects it
            ContributorRunner runner = new ContributorRunner(List.of(late, early));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);

            assertEquals(List.of("early:contribute", "late:contribute"), List.copyOf(TestContributorState.invocations));
        }
    }

    // --- Builder threading ---

    @Nested
    @DisplayName("Builder threading")
    class BuilderThreadingTests {

        @Test
        @DisplayName(
                "each contributor receives the builder returned by its predecessor and the final builder is returned")
        void threadsBuilders() throws Exception {
            VertxBuilder b1 = mock(VertxBuilder.class);
            VertxBuilder b2 = mock(VertxBuilder.class);
            VertxBuilder b3 = mock(VertxBuilder.class);

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());

            // Explicit ascending priorities pin the contribution order deterministically. Bare
            // lambdas share equal phase/priority, so the comparator falls back to orderKey() (the
            // JVM-assigned lambda FQCN), whose relative order is non-deterministic across runs.
            VertxBuilderContributor c1 = new OrderedContributor(1) {
                @Override
                public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
                    assertSame(b1, builder, "c1 should receive the initial builder");
                    return b2;
                }
            };
            VertxBuilderContributor c2 = new OrderedContributor(2) {
                @Override
                public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
                    assertSame(b2, builder, "c2 should receive b2 from c1");
                    return b3;
                }
            };
            VertxBuilderContributor c3 = new OrderedContributor(3) {
                @Override
                public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
                    assertSame(b3, builder, "c3 should receive b3 from c2");
                    return b3;
                }
            };

            // Pass in shuffled order to prove the runner sorts by priority before threading.
            ContributorRunner runner = new ContributorRunner(List.of(c3, c1, c2));
            VertxBuilder result = runner.contributeAll(b1, ctx);

            assertSame(b3, result, "contributeAll must return the last contributor's return value");
        }
    }

    // --- Failure handling ---

    @Nested
    @DisplayName("Failure handling")
    class FailureHandlingTests {

        @Test
        @DisplayName("throwing contributor wraps exception in ContributorFailureException with FQCN in message")
        void throwingContributorWrapsException() {
            TestContributorState.armThrowing = true;

            ContributorRunner runner = new ContributorRunner(List.of(new ThrowingContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            ContributorFailureException ex =
                    assertThrows(ContributorFailureException.class, () -> runner.contributeAll(builder, ctx));

            assertInstanceOf(IllegalStateException.class, ex.getCause(), "original exception must be the cause");
            assertEquals("boom", ex.getCause().getMessage());

            String fqcn = ThrowingContributor.class.getName();
            assertTrue(ex.getMessage().contains(fqcn), "exception message must contain the contributor FQCN: " + fqcn);
        }

        @Test
        @DisplayName("null-returning contributor throws ContributorFailureException with FQCN in message")
        void nullReturningContributorThrows() {
            VertxBuilderContributor nullReturner = new NullReturningContributor();

            ContributorRunner runner = new ContributorRunner(List.of(nullReturner));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            ContributorFailureException ex =
                    assertThrows(ContributorFailureException.class, () -> runner.contributeAll(builder, ctx));

            String fqcn = NullReturningContributor.class.getName();
            assertTrue(ex.getMessage().contains(fqcn), "exception message must contain the contributor FQCN: " + fqcn);
            assertTrue(ex.getMessage().contains("null"), "message must mention null return");
        }
    }

    // --- Single-use ---

    @Nested
    @DisplayName("Single-use")
    class SingleUseTests {

        @Test
        @DisplayName("second contributeAll() call throws IllegalStateException")
        void secondContributeAllThrows() {
            ContributorRunner runner = new ContributorRunner(List.of(new OrderedBetaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);

            assertThrows(
                    IllegalStateException.class,
                    () -> runner.contributeAll(builder, ctx),
                    "a second contributeAll on the same runner must throw IllegalStateException");
        }

        @Test
        @DisplayName("second contributeAll() does not re-invoke any contributor")
        void secondContributeAllDoesNotReinvokeContributors() {
            ContributorRunner runner = new ContributorRunner(List.of(new OrderedBetaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);
            TestContributorState.invocations.clear();

            assertThrows(IllegalStateException.class, () -> runner.contributeAll(builder, ctx));

            assertTrue(
                    TestContributorState.invocations.isEmpty(),
                    "a rejected second contributeAll must not invoke any contributor");
        }
    }

    // --- Shutdown ordering ---

    @Nested
    @DisplayName("Shutdown ordering")
    class ShutdownOrderingTests {

        @Test
        @DisplayName("shutdown runs in reverse contribution order")
        void shutdownReverseOrder() {
            // Beta priority=10 contributes first, Alpha priority=20 second.
            // Shutdown must be: alpha:shutdown then beta:shutdown (reverse).
            ContributorRunner runner =
                    new ContributorRunner(List.of(new OrderedAlphaContributor(), new OrderedBetaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);
            TestContributorState.invocations.clear(); // Clear contribution records; focus on shutdown only

            runner.runShutdownHooks();

            assertEquals(
                    List.of("alpha:shutdown", "beta:shutdown"),
                    List.copyOf(TestContributorState.invocations),
                    "shutdown must run in reverse of contribution order");
        }

        @Test
        @DisplayName("second runShutdownHooks() call is a no-op")
        void exactlyOnce() {
            ContributorRunner runner = new ContributorRunner(List.of(new OrderedAlphaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);
            TestContributorState.invocations.clear();

            runner.runShutdownHooks();
            runner.runShutdownHooks(); // second call must be a no-op

            assertEquals(1, TestContributorState.invocations.size(), "onShutdown must be called exactly once");
        }

        @Test
        @DisplayName("mid-stream abort: only successfully-contributed prefix receives shutdown hook")
        void midStreamAbortOnlyCompletedPrefixGetsShutdown() {
            TestContributorState.armThrowing = true; // Throwing fails after Beta

            // Beta(10) contributes first; Throwing(30) fails second
            ContributorRunner runner =
                    new ContributorRunner(List.of(new OrderedBetaContributor(), new ThrowingContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            assertThrows(ContributorFailureException.class, () -> runner.contributeAll(builder, ctx));
            TestContributorState.invocations.clear();

            runner.runShutdownHooks();

            // Only Beta completed; only Beta's shutdown must run
            assertEquals(
                    List.of("beta:shutdown"),
                    List.copyOf(TestContributorState.invocations),
                    "only the completed prefix must receive shutdown hooks");
        }

        @Test
        @DisplayName("throwing onShutdown does not prevent subsequent hooks from running")
        void throwingShutdownDoesNotBlockOthers() {
            // In-test fixture: first contributor's shutdown throws; second (Beta) must still run
            VertxBuilderContributor throwingShutdown = new ThrowingOnShutdownContributor();

            // ThrowingOnShutdown priority=5 (runs first in contribute, last in shutdown among these two).
            // Beta priority=10 (runs second in contribute, first in shutdown among these two).
            // Contribute order: ThrowingOnShutdown(5), Beta(10)
            // Shutdown order (reverse): Beta(10), ThrowingOnShutdown(5)
            ContributorRunner runner = new ContributorRunner(List.of(throwingShutdown, new OrderedBetaContributor()));

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder builder = mock(VertxBuilder.class);

            runner.contributeAll(builder, ctx);
            TestContributorState.invocations.clear();

            assertDoesNotThrow(runner::runShutdownHooks, "shutdown failures must not propagate");

            // Beta must have received its shutdown callback despite the earlier failure
            assertTrue(
                    TestContributorState.invocations.contains("beta:shutdown"),
                    "beta:shutdown must still run after a preceding hook throws");
        }
    }

    // --- ServiceLoader discovery ---

    @Nested
    @DisplayName("ServiceLoader discovery")
    class DiscoveryTests {

        @Test
        @DisplayName("discover() returns all five registered fixtures sorted by priority")
        void discoverReturnsFixturesSortedByPriority() {
            ContributorRunner runner = ContributorRunner.discover();

            BootstrapContext ctx = new DefaultBootstrapContext(new JsonObject(), new VertxOptions());
            VertxBuilder initialBuilder = mock(VertxBuilder.class);

            runner.contributeAll(initialBuilder, ctx);

            // OptionsRecording(5) < Beta(10) < Alpha(20) < Throwing(30) < FakeMetrics(40) — all APPLICATION phase
            // OptionsRecording records silently, Throwing and FakeMetrics are disarmed so they contribute
            // silently; only Beta and Alpha leave invocation records
            assertEquals(
                    List.of("beta:contribute", "alpha:contribute"),
                    List.copyOf(TestContributorState.invocations),
                    "sorted order should be Beta(10), Alpha(20) based on priority");
        }
    }

    // --- In-test fixtures ---

    /**
     * Contributor with an explicit numeric priority, so a list of them sorts deterministically by
     * priority rather than falling back to the JVM-assigned lambda FQCN tie-break.
     */
    private abstract static class OrderedContributor implements VertxBuilderContributor {
        private final int priority;

        OrderedContributor(int priority) {
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    /** Priority 999, SYSTEM_FIRST phase — used to prove phase dominates priority. */
    private static final class SystemFirstHighPriorityContributor implements VertxBuilderContributor {
        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.SYSTEM_FIRST;
        }

        @Override
        public int priority() {
            return 999;
        }

        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            TestContributorState.invocations.add("system:contribute");
            return builder;
        }
    }

    /** Returns null from contribute — used to test null-return failure path. */
    private static final class NullReturningContributor implements VertxBuilderContributor {
        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            return null;
        }
    }

    /**
     * Priority 5, APPLICATION phase — onShutdown throws to verify subsequent hooks still run.
     */
    private static final class ThrowingOnShutdownContributor implements VertxBuilderContributor {
        @Override
        public int priority() {
            return 5;
        }

        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            return builder;
        }

        @Override
        public void onShutdown() {
            throw new RuntimeException("shutdown hook failure");
        }
    }

    /**
     * APPLICATION phase, priority=0, orderKey returns "early..." for tie-break test.
     */
    private static final class EarlyOrderKeyContributor implements VertxBuilderContributor {
        @Override
        public int priority() {
            return 50;
        }

        @Override
        public String orderKey() {
            return "dev.vertique.bootstrap.EarlyOrderKeyContributor";
        }

        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            TestContributorState.invocations.add("early:contribute");
            return builder;
        }
    }

    /**
     * APPLICATION phase, priority=0, orderKey returns "late..." for tie-break test.
     */
    private static final class LateOrderKeyContributor implements VertxBuilderContributor {
        @Override
        public int priority() {
            return 50;
        }

        @Override
        public String orderKey() {
            return "dev.vertique.bootstrap.LateOrderKeyContributor";
        }

        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            TestContributorState.invocations.add("late:contribute");
            return builder;
        }
    }
}
