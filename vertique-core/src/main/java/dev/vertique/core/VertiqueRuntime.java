// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * Neutral, host-agnostic carrier of the framework inputs needed to build an application's
 * dependency-injection graph.
 *
 * <p>A {@code VertiqueRuntime} carries <strong>only</strong> the two inputs the framework's core
 * graph depends on — the {@link Vertx} instance and the root application configuration
 * ({@link JsonObject}). It deliberately exposes <em>no</em> Dagger types, <em>no</em> host concept
 * (Spring {@code ApplicationContext}, Quarkus {@code Arc}, …), and <em>no</em> service locator. It
 * is the single seam every bootstrap path — standalone launcher or an embedding host bridge —
 * funnels through to hand the framework what it needs without coupling the framework to how those
 * inputs were produced.
 *
 * <p>On the standalone path, the supplied values flow straight into a
 * {@link VertxModule} — {@code new VertxModule(rt.vertx(), rt.config())} — which then
 * {@code @Provides} {@link Vertx} and the {@link VertxConfig}-qualified {@link JsonObject} to the
 * rest of the graph. An embedding host bridge constructs the same {@code VertxModule} from a
 * {@code VertiqueRuntime} it assembles from host-native equivalents, then adds its own typed Dagger
 * adapter modules for host beans (no locator).
 *
 * <p>Instances are immutable; both components are required and rejected when {@code null}.
 *
 * @param vertx  the Vert.x instance the application runs on; never {@code null}
 * @param config the root application configuration; never {@code null}
 */
public record VertiqueRuntime(Vertx vertx, JsonObject config) {

    /**
     * Validates that neither component is {@code null}.
     *
     * @throws NullPointerException if {@code vertx} or {@code config} is {@code null}
     */
    public VertiqueRuntime {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(config, "config");
    }

    /**
     * Creates a runtime from the given Vert.x instance and configuration.
     *
     * @param vertx  the Vert.x instance; must not be {@code null}
     * @param config the root application configuration; must not be {@code null}
     * @return a new {@code VertiqueRuntime} wrapping the given inputs
     * @throws NullPointerException if {@code vertx} or {@code config} is {@code null}
     */
    public static VertiqueRuntime of(Vertx vertx, JsonObject config) {
        return new VertiqueRuntime(vertx, config);
    }
}
