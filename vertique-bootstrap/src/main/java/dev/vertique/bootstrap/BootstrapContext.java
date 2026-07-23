// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;

/**
 * Immutable view of bootstrap state made available to each {@link VertxBuilderContributor}.
 *
 * <p>The context is constructed once per bootstrap run and shared across all contributors in the
 * contribution chain. Each call to {@link #config()} returns a <em>defensive copy</em> of the
 * underlying {@link JsonObject} so that contributors cannot mutate each other's view of the
 * configuration. The {@link #vertxOptions()} object is the <em>live, shared</em>
 * {@link VertxOptions} instance; mutations made by one contributor are visible to subsequent
 * contributors and take effect when the {@link io.vertx.core.Vertx} instance is built.
 */
public interface BootstrapContext {

    /**
     * Returns a defensive copy of the bootstrap configuration.
     *
     * <p>The returned object is a snapshot — mutations to it do not affect the context or any
     * other contributor's view. Callers that need to inspect config safely can do so without
     * worrying about aliasing.
     *
     * @return a non-null defensive copy of the current bootstrap config
     */
    JsonObject config();

    /**
     * Returns the live, mutable {@link VertxOptions} instance that will be used to build the
     * {@link io.vertx.core.Vertx} instance.
     *
     * <p>Mutations made via the returned object are immediately visible to all subsequent
     * contributors and are applied at build time.
     *
     * @return the non-null live {@link VertxOptions} instance
     */
    VertxOptions vertxOptions();
}
