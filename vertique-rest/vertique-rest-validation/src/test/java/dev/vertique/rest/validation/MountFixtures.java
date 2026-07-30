// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.test.RestTestContributions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Builds a real {@link JaxRsRouterMount.Factory} from {@link ValidationMountComponent}, absorbing the
 * Dagger component-construction boilerplate {@code vertique-rest-validation}'s integration tests would
 * otherwise each repeat.
 */
final class MountFixtures {

    /** Not instantiable. */
    private MountFixtures() {}

    /**
     * Builds a mount factory from the given Vert.x instance, configuration, and test contributions.
     *
     * @param vertx         the Vert.x instance
     * @param config        the application configuration, exactly as {@code VertxModule} would supply
     *                      it in production
     * @param contributions the additive test contributions
     * @return the real mount factory, wired with the {@code web-validation} strategy
     */
    static JaxRsRouterMount.Factory factory(Vertx vertx, JsonObject config, RestTestContributions contributions) {
        return DaggerValidationMountComponent.factory()
                .create(vertx, config, contributions)
                .mountFactory();
    }

    /**
     * Builds a mount factory with an empty configuration, so every {@code jaxrs}/{@code http} setting
     * stays at its framework default.
     *
     * @param vertx         the Vert.x instance
     * @param contributions the additive test contributions
     * @return the real mount factory
     */
    static JaxRsRouterMount.Factory factory(Vertx vertx, RestTestContributions contributions) {
        return factory(vertx, new JsonObject(), contributions);
    }
}
