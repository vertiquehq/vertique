// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register declared applications of the input-assembly integration tests
 * and contribute their resources. Every registration is built exactly as the annotation processor
 * would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources,
 * false, "", true)}, since the processor does not run on framework test sources; every resource
 * instance is contributed as a manual {@code @JaxRsResources} instance.
 */
public final class ItApplicationModules {

    private ItApplicationModules() {}

    /**
     * Registers an active application exactly as the generated registration module does.
     *
     * @param declaringType the declaring interface
     * @param name          the application's name
     * @param path          the application's path
     * @param resource      the one resource class the application lists
     * @return the registration
     */
    static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, Class<?> resource) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resource), false, "", true);
    }

    /** The two twin applications, {@code generated} and {@code reflected}, with their resources. */
    @Module
    public static final class Twins {

        private Twins() {}

        /**
         * Registers {@link GeneratedTwinApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration generatedRegistration() {
            return register(
                    GeneratedTwinApi.class,
                    GeneratedTwinApi.NAME,
                    GeneratedTwinApi.PATH,
                    GeneratedSearchResource.class);
        }

        /**
         * Registers {@link ReflectedTwinApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration reflectedRegistration() {
            return register(
                    ReflectedTwinApi.class,
                    ReflectedTwinApi.NAME,
                    ReflectedTwinApi.PATH,
                    ReflectedSearchResource.class);
        }

        /**
         * Contributes the generated-path twin resource.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object generatedResource() {
            return new GeneratedSearchResource();
        }

        /**
         * Contributes the reflective-path twin resource.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object reflectedResource() {
            return new ReflectedSearchResource();
        }
    }

    /** The application {@code verbatim} with its resource. */
    @Module
    public static final class Verbatim {

        private Verbatim() {}

        /**
         * Registers {@link VerbatimApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(VerbatimApi.class, VerbatimApi.NAME, VerbatimApi.PATH, VerbatimResource.class);
        }

        /**
         * Contributes {@link VerbatimResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new VerbatimResource();
        }
    }

    /** The application {@code frozen} with its resource. */
    @Module
    public static final class Frozen {

        private Frozen() {}

        /**
         * Registers {@link FrozenApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(FrozenApi.class, FrozenApi.NAME, FrozenApi.PATH, FrozenResource.class);
        }

        /**
         * Contributes {@link FrozenResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FrozenResource();
        }
    }
}
