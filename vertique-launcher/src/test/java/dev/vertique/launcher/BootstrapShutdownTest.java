// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.ContributorRunner;
import dev.vertique.bootstrap.DefaultBootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import dev.vertique.config.source.ConfigPropertySource;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BootstrapShutdown}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Contributor shutdown hooks run before property sources are closed.</li>
 *   <li>Property sources are closed in reverse declaration order.</li>
 *   <li>The entire sequence runs exactly once (idempotency).</li>
 *   <li>A throwing source close is logged and remaining sources are still closed.</li>
 *   <li>A {@code null} runner is tolerated (sources-only path).</li>
 * </ul>
 */
class BootstrapShutdownTest {

    // --- Fixtures ---

    /** Stub {@link ConfigPropertySource} that records close calls and optionally throws. */
    private static final class RecordingSource implements ConfigPropertySource {

        private final String sourceName;
        private final List<String> closeOrder;
        private final boolean throwOnClose;

        RecordingSource(String sourceName, List<String> closeOrder, boolean throwOnClose) {
            this.sourceName = sourceName;
            this.closeOrder = closeOrder;
            this.throwOnClose = throwOnClose;
        }

        @Override
        public String name() {
            return sourceName;
        }

        @Override
        public Optional<String> lookup(String key) {
            return Optional.empty();
        }

        @Override
        public void close() {
            closeOrder.add(sourceName + ":close");
            if (throwOnClose) {
                throw new RuntimeException("intentional close failure from " + sourceName);
            }
        }
    }

    /**
     * Stub {@link VertxBuilderContributor} that records shutdown events into a shared list.
     */
    private static final class ShutdownRecordingContributor implements VertxBuilderContributor {

        private final String label;
        private final List<String> events;

        ShutdownRecordingContributor(String label, List<String> events) {
            this.label = label;
            this.events = events;
        }

        @Override
        public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
            return builder;
        }

        @Override
        public void onShutdown() {
            events.add(label + ":shutdown");
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("hooks then sources: contributor shutdown runs before property sources are closed")
    void hooksThenSourcesOrder() {
        List<String> events = new ArrayList<>();
        List<String> closeOrder = events; // same list to observe interleaving

        var contributor = new ShutdownRecordingContributor("alpha", events);
        var runner = new ContributorRunner(List.of(contributor));
        // Advance contributedCount by calling contributeAll
        runner.contributeAll(
                Vertx.builder(),
                new DefaultBootstrapContext(new io.vertx.core.json.JsonObject(), new io.vertx.core.VertxOptions()));

        List<ConfigPropertySource> sources = List.of(new RecordingSource("s1", closeOrder, false));
        var shutdown = new BootstrapShutdown(runner, sources);

        shutdown.runOnce();

        assertTrue(
                events.indexOf("alpha:shutdown") < events.indexOf("s1:close"),
                "contributor shutdown must happen before source close");
    }

    @Test
    @DisplayName("reverse source order: sources are closed in reverse declaration order")
    void reverseSourceOrder() {
        List<String> closeOrder = new ArrayList<>();
        List<ConfigPropertySource> sources = List.of(
                new RecordingSource("s1", closeOrder, false),
                new RecordingSource("s2", closeOrder, false),
                new RecordingSource("s3", closeOrder, false));

        var shutdown = new BootstrapShutdown(null, sources);
        shutdown.runOnce();

        assertEquals(
                List.of("s3:close", "s2:close", "s1:close"),
                closeOrder,
                "sources must be closed in reverse declaration order");
    }

    @Test
    @DisplayName("exactly-once: runOnce is idempotent — second call is a no-op")
    void exactlyOnce() {
        List<String> closeOrder = new ArrayList<>();
        List<ConfigPropertySource> sources = List.of(new RecordingSource("s1", closeOrder, false));

        var shutdown = new BootstrapShutdown(null, sources);
        shutdown.runOnce();
        shutdown.runOnce(); // second call must be a no-op

        assertEquals(1, closeOrder.size(), "s1 must be closed exactly once");
    }

    @Test
    @DisplayName("throwing source close: logged and remaining sources still closed")
    void throwingSourceCloseLogsAndContinues() {
        List<String> closeOrder = new ArrayList<>();
        List<ConfigPropertySource> sources = List.of(
                new RecordingSource("s1", closeOrder, false),
                new RecordingSource("s2-throws", closeOrder, true), // throws
                new RecordingSource("s3", closeOrder, false));

        var shutdown = new BootstrapShutdown(null, sources);
        // Must not propagate the exception from s2-throws
        shutdown.runOnce();

        // Reverse close order: s3, s2-throws, s1
        // s2-throws close throws but s1 must still be closed
        assertTrue(closeOrder.contains("s3:close"), "s3 must be closed");
        assertTrue(closeOrder.contains("s2-throws:close"), "s2-throws must be closed (exception recorded after)");
        assertTrue(closeOrder.contains("s1:close"), "s1 must still be closed after s2-throws exception");
        assertEquals(
                List.of("s3:close", "s2-throws:close", "s1:close"),
                closeOrder,
                "all sources must be closed in reverse order despite exception");
    }

    @Test
    @DisplayName("null runner: sources-only shutdown works without contributor runner")
    void nullRunnerTolerated() {
        List<String> closeOrder = new ArrayList<>();
        List<ConfigPropertySource> sources = List.of(new RecordingSource("s1", closeOrder, false));

        var shutdown = new BootstrapShutdown(null, sources);
        shutdown.runOnce();

        assertEquals(List.of("s1:close"), closeOrder, "sources must be closed even when no runner is present");
    }

    @Test
    @DisplayName("exactly-once: idempotent guard is independent of the runner's own guard")
    void idempotentGuardIsOwnGuard() {
        AtomicInteger hookCallCount = new AtomicInteger(0);
        var contributor = new VertxBuilderContributor() {
            @Override
            public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
                return builder;
            }

            @Override
            public void onShutdown() {
                hookCallCount.incrementAndGet();
            }
        };
        var runner = new ContributorRunner(List.of(contributor));
        runner.contributeAll(
                Vertx.builder(),
                new DefaultBootstrapContext(new io.vertx.core.json.JsonObject(), new io.vertx.core.VertxOptions()));

        List<String> closeOrder = new ArrayList<>();
        List<ConfigPropertySource> sources = List.of(new RecordingSource("s1", closeOrder, false));
        var shutdown = new BootstrapShutdown(runner, sources);

        shutdown.runOnce();
        shutdown.runOnce(); // second call must be a no-op via BootstrapShutdown's own guard

        assertEquals(1, hookCallCount.get(), "contributor onShutdown must run exactly once");
        assertEquals(1, closeOrder.size(), "source must be closed exactly once");
    }
}
