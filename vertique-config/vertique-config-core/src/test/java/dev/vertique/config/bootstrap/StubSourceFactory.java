// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link ConfigPropertySourceFactory} registered as type {@code "stub-source"}.
 *
 * <p>Provides controllable per-instance behaviour via fields in the source config entry:
 * <ul>
 *   <li>{@code values} — a {@link JsonObject} of key→string entries the source will serve.</li>
 *   <li>{@code failCreate} — boolean; when {@code true} {@link #create(String, JsonObject)}
 *       throws {@link IllegalStateException} immediately.</li>
 * </ul>
 *
 * <p>All created source instances share static counters accessible via {@link #State}:
 * <ul>
 *   <li>{@link State#createCount} — how many {@link #create} calls completed successfully.</li>
 *   <li>{@link State#totalCloseCount} — sum of {@link ConfigPropertySource#close()} calls across
 *       all instances.</li>
 * </ul>
 *
 * <p>Call {@link State#reset()} in {@code @BeforeEach} / {@code @AfterEach} to clear all
 * shared state between tests.
 *
 * <p>Registered via the ServiceLoader SPI in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory}.
 */
public final class StubSourceFactory implements ConfigPropertySourceFactory {

    // --- State ---

    /**
     * Shared mutable state for all {@link StubSourceFactory} instances created during a test.
     *
     * <p>Access is not thread-safe beyond what {@link AtomicInteger} guarantees for the counters —
     * these are only used from the single-threaded bootstrap path.
     *
     * <p>The {@link #staticValues} map allows tests to register literal key→value pairs (including
     * values containing {@code ${...}} syntax) in a way that bypasses pass-1 source-entry
     * resolution. Sources created with {@code type="stub-source"} will consult this map during
     * lookup <em>in addition to</em> any values baked into the source entry config. This map is
     * cleared by {@link #reset()}.
     */
    public static final class State {

        /** Number of source instances created successfully since the last {@link #reset()}. */
        public static final AtomicInteger createCount = new AtomicInteger(0);

        /** Total number of {@link ConfigPropertySource#close()} calls since the last {@link #reset()}. */
        public static final AtomicInteger totalCloseCount = new AtomicInteger(0);

        /**
         * Out-of-band key→value registry: values here are served literally by any stub source,
         * bypassing pass-1 entry resolution. Use this to register values that contain
         * {@code ${...}} without triggering bootstrap-mode resolution failures.
         */
        public static final Map<String, String> staticValues = new HashMap<>();

        private State() {}

        /**
         * Resets all counters and the static-values registry to zero / empty.
         * Call in {@code @BeforeEach} and {@code @AfterEach}.
         */
        public static void reset() {
            createCount.set(0);
            totalCloseCount.set(0);
            staticValues.clear();
        }
    }

    // --- Factory SPI ---

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "stub-source";
    }

    /**
     * Creates a new stub source from the given source-config entry.
     *
     * <p>If the entry contains {@code "failCreate": true}, throws {@link IllegalStateException}
     * immediately without incrementing {@link State#createCount}.
     *
     * @param name         the instance name for this source
     * @param sourceConfig the per-source config; may contain {@code values} and {@code failCreate}
     * @return a ready-to-use {@link ConfigPropertySource}
     * @throws IllegalStateException if {@code failCreate} is {@code true} in the config
     */
    @Override
    public ConfigPropertySource create(String name, JsonObject sourceConfig) {
        if (sourceConfig.getBoolean("failCreate", false)) {
            throw new IllegalStateException("StubSourceFactory.failCreate=true for source '" + name + "'");
        }

        JsonObject valuesObj = sourceConfig.getJsonObject("values", new JsonObject());
        Map<String, String> values = new HashMap<>();
        for (String key : valuesObj.fieldNames()) {
            values.put(key, valuesObj.getString(key));
        }

        State.createCount.incrementAndGet();
        return new StubSource(name, values);
    }

    // --- Source implementation ---

    /**
     * Minimal {@link ConfigPropertySource} backed by a fixed map.
     *
     * <p>Increments {@link State#totalCloseCount} on close. Consults {@link State#staticValues}
     * first for any lookup, falling back to the per-instance values map from the entry config.
     */
    static final class StubSource implements ConfigPropertySource {

        private final String name;
        private final Map<String, String> values;

        /**
         * Constructs a stub source.
         *
         * @param name   the source instance name
         * @param values the key→value map served by {@link #lookup(String)}
         */
        StubSource(String name, Map<String, String> values) {
            this.name = name;
            this.values = Map.copyOf(values);
        }

        /** {@inheritDoc} */
        @Override
        public String name() {
            return name;
        }

        /**
         * Looks up the key in this source's value maps.
         *
         * <p>Resolution order:
         * <ol>
         *   <li>Consults {@link State#staticValues} first (out-of-band values registered by the
         *       test, bypassing pass-1 entry resolution).</li>
         *   <li>Falls back to the per-instance {@code values} map from the entry config.</li>
         * </ol>
         *
         * @param key the placeholder key; never {@code null}
         * @return the mapped value or {@link Optional#empty()} when absent in both maps
         */
        @Override
        public Optional<String> lookup(String key) {
            // Check static (out-of-band) values first — these bypass pass-1 resolution
            if (State.staticValues.containsKey(key)) {
                return Optional.of(State.staticValues.get(key));
            }
            return Optional.ofNullable(values.get(key));
        }

        /**
         * Releases this source; increments {@link State#totalCloseCount}.
         */
        @Override
        public void close() {
            State.totalCloseCount.incrementAndGet();
        }
    }
}
