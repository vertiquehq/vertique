// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import io.vertx.config.spi.ConfigStore;
import io.vertx.config.spi.ConfigStoreFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link ConfigStoreFactory} that counts how many times a store has been created
 * and returns a configurable payload from each store.
 *
 * <p>Registered as the {@code "counting-test"} type via the ServiceLoader SPI in
 * {@code META-INF/services/io.vertx.config.spi.ConfigStoreFactory}.
 *
 * <p>Usage in tests:
 * <ol>
 *   <li>Call {@link #reset()} in {@code @BeforeEach} / {@code @AfterEach} to clear state.</li>
 *   <li>Set {@link #failMode} to {@code true} to make every store's {@code get()} return a
 *       failed {@link Future}.</li>
 *   <li>Supply a store declaration with {@code "config": {"payload": {...}}} to control
 *       the JSON this store contributes to the merged tree.</li>
 * </ol>
 */
public final class CountingTestStoreFactory implements ConfigStoreFactory {

    // --- Static test-state ---

    /**
     * Number of store instances created since the last {@link #reset()}.
     * Tests inspect this to verify that phase-2 did or did not create stores.
     */
    public static final AtomicInteger createCount = new AtomicInteger(0);

    /**
     * When {@code true} every store's {@code get()} returns a failed {@link Future}.
     * Tests set this to verify that a failing declared store aborts the bootstrap.
     */
    public static volatile boolean failMode = false;

    // --- Factory SPI ---

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "counting-test";
    }

    /**
     * Creates a {@link ConfigStore} whose {@code get()} returns the {@code "payload"} entry
     * from {@code configuration}, or an empty {@link JsonObject} when absent.
     *
     * <p>Increments {@link #createCount} on every invocation. When {@link #failMode} is
     * {@code true} at the time {@code get()} is called, the returned {@code Future} fails.
     *
     * @param vertx         the Vert.x instance (unused by this test store)
     * @param configuration the store configuration; the {@code "payload"} field (JsonObject)
     *                      is returned verbatim by the store
     * @return a {@link ConfigStore} controlled by this factory's static flags
     */
    @Override
    public ConfigStore create(Vertx vertx, JsonObject configuration) {
        createCount.incrementAndGet();
        JsonObject payload = configuration.getJsonObject("payload", new JsonObject());
        return new CountingTestStore(payload);
    }

    /**
     * Resets all shared state: clears {@link #createCount} and sets {@link #failMode} to
     * {@code false}. Call in {@code @BeforeEach} and/or {@code @AfterEach}.
     */
    public static void reset() {
        createCount.set(0);
        failMode = false;
    }

    // --- Store implementation ---

    /**
     * Minimal {@link ConfigStore} that returns a fixed JSON payload.
     *
     * @param payload the JSON object to return from {@code get()}
     */
    private record CountingTestStore(JsonObject payload) implements ConfigStore {

        /**
         * Returns the fixed payload, or a failed {@link Future} when {@link #failMode} is
         * {@code true}.
         *
         * @return a {@code Future} carrying the payload buffer, or a failed {@code Future}
         */
        @Override
        public Future<Buffer> get() {
            if (failMode) {
                return Future.failedFuture(new RuntimeException("CountingTestStoreFactory.failMode is true"));
            }
            return Future.succeededFuture(Buffer.buffer(payload.encode()));
        }

        /**
         * Closes this store. This implementation is a no-op.
         *
         * @return a succeeded {@code Future<Void>}
         */
        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    }
}
