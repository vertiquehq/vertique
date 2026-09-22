// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestNoSecurityModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * BG1: the validator-present counterpart of {@link ValidationMountComponent}. The identical graph
 * plus {@link RestTestValidatorModule}, which supplies a concrete {@link jakarta.validation.Validator}
 * so {@link RestValidationModule}'s {@code @BindsOptionalOf Validator} resolves present for every
 * mount this component builds: {@link AnnotationSchemaSource} then generates body schemas through
 * Bean Validation metadata (the {@code MetadataConstraintSource} supplement), not the annotation walk
 * alone, exactly as an application that depends on {@code vertique-validation} (or binds its own
 * {@code Validator}) would see in production.
 *
 * <p>A separate component rather than a fourth {@code @BindsInstance} on {@link
 * ValidationMountComponent.Factory}: widening that factory's arity would be source- and
 * binary-breaking for every existing call through {@link MountFixtures}. This graph is additive —
 * nothing about the default, validator-absent graph changes.
 */
@Singleton
@Component(
        modules = {
            RestTestFixtureModule.class,
            RestTestNoSecurityModule.class,
            RestValidationModule.class,
            RestTestValidatorModule.class
        })
interface ValidatorBackedValidationMountComponent {

    /**
     * Returns the mount handle assembled by the framework graph, validator-backed — see {@link
     * ValidationMountComponent#testMount()} for what the handle itself offers.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /** Factory binding the three instances a consumer supplies to the graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the validator-backed validation mount graph.
         *
         * @param vertx         the Vert.x instance
         * @param config        the application configuration, exactly as {@code VertxModule} would
         *                      supply it in production
         * @param contributions the additive test contributions
         * @return the assembled component
         */
        ValidatorBackedValidationMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }
}
