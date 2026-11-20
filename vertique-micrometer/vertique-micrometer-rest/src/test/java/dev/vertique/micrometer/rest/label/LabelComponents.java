// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.micrometer.rest.MicrometerRestModule;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The two Dagger compositions of the application-label proof, each over the production
 * {@code RestModule} (so the ROOT completion emitter runs for every request) and the production
 * {@link MicrometerRestModule} (so the adapter's listener is the one under test, wired the way an
 * application wires it).
 */
public final class LabelComponents {

    private LabelComponents() {}

    /** What every label composition exposes. */
    public interface Provisions {

        /**
         * Creates the deployable {@link HttpVerticle} composing the fixture's mounts.
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();

        /**
         * Returns the registry the metrics adapter records into.
         *
         * @return the shared meter registry
         */
        MeterRegistry meterRegistry();

        /**
         * Returns the log of every REST completion event the composition emitted.
         *
         * @return the completion log
         */
        CompletionLog completionLog();
    }

    /** Applications {@code public}, {@code partner} and {@code mgmt}; explicit mode. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                MicrometerRestModule.class,
                LabelFixtureModule.class,
                LabelRegistrationModule.class
            })
    public interface ApplicationsComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ApplicationsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** No application declaration; the default mount at {@code /*} serves {@link LegacyResource}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                MicrometerRestModule.class,
                LabelFixtureModule.class,
                LegacyResourcesModule.class
            })
    public interface LegacyComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            LegacyComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
