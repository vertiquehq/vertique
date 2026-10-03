// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * The Dagger modules of the complete-document fixture. Each declaration is registered exactly as the
 * annotation processor would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType,
 * name, path, resources, false, "", true)}, since the processor does not run on framework test
 * sources, and each resource instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>The two twins share their operation ids, which one composition refuses across its mounts, so
 * {@link Ref} and {@link Gen} each belong to a composition of their own; both include {@link
 * Shared}.
 */
public final class CompleteModules {

    private CompleteModules() {}

    /**
     * What both compositions bind besides their declaration: the two described scheme handlers of
     * {@link CompleteSchemes}, the {@link AuthEnforcementCapability} marker an installed authentication
     * module would bind (so the scoped requirements, which fold into a restrictive policy, register
     * without a startup violation), and the two response producer bindings of {@link
     * CompleteProducers}.
     */
    @Module
    public static final class Shared {

        private Shared() {}

        /**
         * Contributes the {@value CompleteEntries#BEARER_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler bearerAuthHandler() {
            return CompleteSchemes.bearerAuth();
        }

        /**
         * Contributes the {@value CompleteEntries#API_KEY_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler apiKeyAuthHandler() {
            return CompleteSchemes.apiKeyAuth();
        }

        /**
         * Provides the auth enforcement marker.
         *
         * @return the marker instance
         */
        @Provides
        static AuthEnforcementCapability authEnforcementCapability() {
            return AuthEnforcementCapability.INSTANCE;
        }

        /**
         * Contributes the producer binding of {@link ArchiveTicket}.
         *
         * @return the binding
         */
        @Provides
        @IntoSet
        static ResponseProducerBinding<?> archiveProducer() {
            return CompleteProducers.archive();
        }

        /**
         * Contributes the producer binding of {@link ExportTicket}.
         *
         * @return the binding
         */
        @Provides
        @IntoSet
        static ResponseProducerBinding<?> exportProducer() {
            return CompleteProducers.export();
        }
    }

    /** The application {@code ref} with the reflective twin. */
    @Module(includes = Shared.class)
    public static final class Ref {

        private Ref() {}

        /**
         * Registers {@link RefEntriesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    RefEntriesApi.class,
                    RefEntriesApi.NAME,
                    RefEntriesApi.PATH,
                    List.of(ReflectedEntryResource.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes {@link ReflectedEntryResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ReflectedEntryResource();
        }
    }

    /** The application {@code gen} with the generated-shape twin. */
    @Module(includes = Shared.class)
    public static final class Gen {

        private Gen() {}

        /**
         * Registers {@link GenEntriesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    GenEntriesApi.class,
                    GenEntriesApi.NAME,
                    GenEntriesApi.PATH,
                    List.of(GeneratedEntryResource.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes {@link GeneratedEntryResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new GeneratedEntryResource();
        }
    }
}
