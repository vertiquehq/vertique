// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.factory;

import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.VertxModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Test-only Dagger component standing in for an embedded host's application graph.
 *
 * <p>It aggregates the framework's {@link VertxModule} (fed the neutral {@code Vertx} + config from
 * a {@link dev.vertique.application.VertiqueRuntime}) and a typed {@link FakeHostAdapterModule} that wires
 * a host-native {@link FakeDataSource}. It exposes the {@link FakeDataSourceConsumer} (which
 * received the host bean by injection) plus the runtime's {@link Vertx} and {@link VertxConfig}
 * config, so the positive test can assert both the typed-adapter delivery and that the
 * runtime inputs flowed through unchanged.
 *
 * <p>Crucially this component lives in {@code vertique-application} test scope, which has <em>no</em>
 * {@code vertique-launcher} on its classpath — structurally proving the
 * {@link dev.vertique.application.VertiqueComponentFactory} seam is launcher-free.
 */
@Singleton
@Component(modules = {VertxModule.class, FakeHostAdapterModule.class})
public interface FakeHostComponent {

    /**
     * Returns the consumer that received the host-native data source via constructor injection.
     *
     * @return the data source consumer
     */
    FakeDataSourceConsumer consumer();

    /**
     * Returns the Vert.x instance provided by {@link VertxModule}.
     *
     * @return the Vert.x instance
     */
    Vertx vertx();

    /**
     * Returns the application configuration provided by {@link VertxModule}.
     *
     * @return the {@link VertxConfig}-qualified configuration
     */
    @VertxConfig
    JsonObject config();
}
