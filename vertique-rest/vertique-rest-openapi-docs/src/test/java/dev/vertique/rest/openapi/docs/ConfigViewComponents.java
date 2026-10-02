// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Dagger test component that builds a declared-application view for the configuration checks.
 * The view comes from {@code RestModule}, so its builder's name checks run, over a list of
 * registrations the test passes in; each registration is one hand-written
 * {@link GeneratedRestApplicationRegistration#of} call in the generated shape (see
 * {@code fixture.startup.config.ConfigRegistrations}). The component exposes only the view and the
 * canonical configuration parser; it never builds a mount.
 */
public final class ConfigViewComponents {

    private ConfigViewComponents() {}

    /**
     * The registrations of one view.
     *
     * @param registrations the registrations, each a generated-shape factory call
     */
    public record RegistrationList(List<GeneratedRestApplicationRegistration> registrations) {

        /** Copies the list. */
        public RegistrationList {
            registrations = List.copyOf(registrations);
        }
    }

    /** Contributes the registrations of the {@link RegistrationList} the factory received. */
    @Module
    public static final class RegistrationListModule {

        private RegistrationListModule() {}

        /**
         * Contributes every registration of the list.
         *
         * @param list the registrations bound through the factory
         * @return the registrations, in list order
         */
        @Provides
        @ElementsIntoSet
        static Set<GeneratedRestApplicationRegistration> registrations(RegistrationList list) {
            return new LinkedHashSet<>(list.registrations());
        }
    }

    /** A view over the given registrations, with the documentation module installed. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                RegistrationListModule.class
            })
    public interface ViewComponent {

        /**
         * Resolves the component's declared-application view.
         *
         * @return the view
         */
        RestApplications restApplications();

        /**
         * Resolves the component's canonical configuration parser.
         *
         * @return the parser
         */
        ConfigParser configParser();

        /** Factory taking the application configuration and the registrations. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config        the application configuration
             * @param registrations the registrations of the view
             * @return the component
             */
            ViewComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance RegistrationList registrations);
        }
    }
}
