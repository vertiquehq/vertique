// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module that provides core Vert.x infrastructure bindings.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link Vertx} — the Vert.x instance passed at construction time
 *   <li>{@link JsonObject} (qualified with {@link VertxConfig}) — the application configuration
 *   <li>{@link EventBus} — shortcut to {@code vertx.eventBus()}
 *   <li>{@link EventBusExceptionMapper} — translates {@link io.vertx.core.eventbus.ReplyException}s
 *   <li>{@link EventBusClient} — framework-level event bus client for service dispatch
 * </ul>
 */
@Module
public class VertxModule {
    private final Vertx vertx;
    private final JsonObject config;

    /**
     * Constructs a new module with the given Vert.x instance and application configuration.
     *
     * @param vertx      the Vert.x instance
     * @param jsonConfig the application configuration
     */
    public VertxModule(Vertx vertx, @VertxConfig JsonObject jsonConfig) {
        this.vertx = vertx;
        this.config = jsonConfig;
    }

    /**
     * Provides the Vert.x instance.
     *
     * @return the Vert.x instance
     */
    @Provides
    @Singleton
    public Vertx vertx() {
        return vertx;
    }

    /**
     * Provides the application configuration.
     *
     * @return the configuration as a {@link JsonObject}
     */
    @Provides
    @Singleton
    public @VertxConfig JsonObject config() {
        return config;
    }

    /**
     * Provides the event bus from the Vert.x instance.
     *
     * @param vertx the Vert.x instance
     * @return the event bus
     */
    @Provides
    @Singleton
    public EventBus eventBus(Vertx vertx) {
        return vertx.eventBus();
    }

    /**
     * Provides the {@link EventBusExceptionMapper} singleton.
     *
     * @return the exception mapper
     */
    @Provides
    @Singleton
    public EventBusExceptionMapper eventBusExceptionMapper() {
        return new EventBusExceptionMapper();
    }

    /**
     * Provides the {@link EventBusClient} singleton.
     *
     * @param vertx           the Vert.x instance
     * @param exceptionMapper the exception mapper
     * @return the event bus client
     */
    @Provides
    @Singleton
    public EventBusClient eventBusClient(Vertx vertx, EventBusExceptionMapper exceptionMapper) {
        return new EventBusClient(vertx, exceptionMapper);
    }
}
