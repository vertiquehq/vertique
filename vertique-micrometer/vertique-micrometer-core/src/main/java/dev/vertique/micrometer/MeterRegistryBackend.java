// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A handle to a single backend registry instance, bundling the registry itself with its
 * lifecycle teardown contract.
 *
 * <p>Implementations are created by {@link MeterRegistryProvider#create(io.vertx.core.json.JsonObject)}
 * and managed by {@link MicrometerAssembly}. The assembly closes all backends in reverse creation
 * order during normal shutdown or bootstrap rollback.
 *
 * <p>The {@link #close()} method must be idempotent: multiple calls must not throw and must not
 * produce observable side-effects beyond the first invocation.
 *
 * @see MeterRegistryProvider
 * @see MicrometerAssembly
 */
public interface MeterRegistryBackend extends AutoCloseable {

    /**
     * Returns the underlying {@link MeterRegistry} contributed by this backend.
     *
     * <p>The returned registry is added to the composite registry assembled by
     * {@link MicrometerAssembly}.
     *
     * @return the backing registry; never {@code null}
     */
    MeterRegistry registry();

    /**
     * Releases the registry and any static state the backend published.
     *
     * <p>Called on normal application shutdown (in reverse creation order) AND on bootstrap
     * rollback if a later provider fails. Must be idempotent.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     */
    @Override
    void close();
}
