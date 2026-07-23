// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launcher-local test-only {@link ConfigPropertySourceFactory} registered as type
 * {@code "launcher-stub"}.
 *
 * <p>Provides controllable per-test behaviour via a static key→value map (set by tests before
 * launch) and records close lifecycle for verifying the shutdown contract.
 *
 * <p>All instance state is shared through {@link State}:
 * <ul>
 *   <li>{@link State#values} — static key→value map consulted by every created source.</li>
 *   <li>{@link State#closeCount} — total number of {@link ConfigPropertySource#close()} calls
 *       across all instances since the last {@link State#reset()}.</li>
 *   <li>{@link State#closeLabels} — ordered list of labels for each close invocation,
 *       allowing tests to verify reverse-declaration-order close behaviour.</li>
 * </ul>
 *
 * <p>Call {@link State#reset()} in {@code @BeforeEach} and {@code @AfterEach} to isolate tests
 * from each other.
 *
 * <p>Registered via the ServiceLoader SPI in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory}.
 */
public final class LauncherStubSourceFactory implements ConfigPropertySourceFactory {

    // --- State ---

    /**
     * Shared mutable state for all {@link LauncherStubSourceFactory} instances created during a
     * test.
     *
     * <p>The {@link #values} map is consulted by every stub source created by this factory. Set
     * values here before launching; the map is read during {@link ConfigPropertySource#lookup(String)}
     * calls.
     */
    public static final class State {

        /**
         * Key→value map served by every stub source instance. Set entries here before launching.
         * Cleared by {@link #reset()}.
         */
        public static final Map<String, String> values = new HashMap<>();

        /**
         * Total number of {@link ConfigPropertySource#close()} calls across all created
         * instances since the last {@link #reset()}.
         */
        public static final AtomicInteger closeCount = new AtomicInteger(0);

        /**
         * Ordered list of instance labels appended on each {@link ConfigPropertySource#close()}
         * call. Allows tests to verify reverse-declaration-order close behaviour.
         * Thread-safe list; cleared by {@link #reset()}.
         */
        public static final List<String> closeLabels = Collections.synchronizedList(new ArrayList<>());

        private State() {}

        /**
         * Resets all state to zero/empty. Call in {@code @BeforeEach} and {@code @AfterEach}.
         */
        public static void reset() {
            values.clear();
            closeCount.set(0);
            closeLabels.clear();
        }
    }

    // --- Factory SPI ---

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "launcher-stub";
    }

    /**
     * Creates a new launcher stub source. The source will consult {@link State#values} for every
     * key lookup.
     *
     * <p>The {@code "name"} field from the source config entry is used as the instance label in
     * close records. When absent, the source-config object's index-derived default name (supplied
     * by the bootstrap engine) is used.
     *
     * @param name         the instance name for this source (used for close-label recording)
     * @param sourceConfig the per-source config entry; unused beyond the name
     * @return a ready-to-use {@link ConfigPropertySource}
     */
    @Override
    public ConfigPropertySource create(String name, JsonObject sourceConfig) {
        return new LauncherStubSource(name);
    }

    // --- Source implementation ---

    /**
     * Minimal {@link ConfigPropertySource} that consults {@link State#values} for lookups and
     * records its close into {@link State#closeCount} and {@link State#closeLabels}.
     */
    static final class LauncherStubSource implements ConfigPropertySource {

        private final String name;

        /**
         * Constructs a launcher stub source.
         *
         * @param name the instance name used for close-label recording and diagnostics
         */
        LauncherStubSource(String name) {
            this.name = name;
        }

        /** {@inheritDoc} */
        @Override
        public String name() {
            return name;
        }

        /**
         * Looks up the key in {@link State#values}.
         *
         * @param key the placeholder key to resolve; never {@code null}
         * @return the mapped value, or {@link Optional#empty()} when absent
         */
        @Override
        public Optional<String> lookup(String key) {
            return Optional.ofNullable(State.values.get(key));
        }

        /**
         * Records this close into {@link State#closeCount} and appends the instance name to
         * {@link State#closeLabels}.
         */
        @Override
        public void close() {
            State.closeCount.incrementAndGet();
            State.closeLabels.add(name);
        }
    }
}
