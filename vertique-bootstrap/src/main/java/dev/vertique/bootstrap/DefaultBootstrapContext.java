// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * Default implementation of {@link BootstrapContext}.
 *
 * <p>Holds the bootstrap config and the live {@link VertxOptions} instance. Each call to
 * {@link #config()} returns a defensive copy so that contributors cannot mutate each other's view
 * of the configuration. The {@link VertxOptions} returned by {@link #vertxOptions()} is the live,
 * shared instance — mutations are immediately visible to all subsequent contributors.
 */
public final class DefaultBootstrapContext implements BootstrapContext {

    private final JsonObject config;
    private final VertxOptions vertxOptions;

    /**
     * Constructs a new context.
     *
     * @param config      the bootstrap config; must not be {@code null}
     * @param vertxOptions the live {@link VertxOptions}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public DefaultBootstrapContext(JsonObject config, VertxOptions vertxOptions) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.vertxOptions = Objects.requireNonNull(vertxOptions, "vertxOptions must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject config() {
        return config.copy();
    }

    /** {@inheritDoc} */
    @Override
    public VertxOptions vertxOptions() {
        return vertxOptions;
    }
}
