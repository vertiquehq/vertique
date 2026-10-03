// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register one declared application of the reserved-name redaction
 * integration tests and contribute its resource. Every registration is built exactly as the
 * annotation processor would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType,
 * name, path, resources, false, "", true)}, since the processor does not run on framework test
 * sources; every resource instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>The public and protected declarations of one application share its name, path, and resource; a
 * component lists exactly one of the two modules.
 */
public final class RedactionApplicationModules {

    private RedactionApplicationModules() {}

    private static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, Class<?> resource) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resource), false, "", true);
    }

    /** The application {@code notes} declared by {@link NotesApi}, with its resource. */
    @Module
    public static final class Notes {

        private Notes() {}

        /**
         * Registers {@link NotesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(NotesApi.class, NotesApi.NAME, NotesApi.PATH, NotesResource.class);
        }

        /**
         * Contributes {@link NotesResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new NotesResource();
        }
    }

    /** The application {@code notes} declared by {@link ProtectedNotesApi}, with its resource. */
    @Module
    public static final class ProtectedNotes {

        private ProtectedNotes() {}

        /**
         * Registers {@link ProtectedNotesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(ProtectedNotesApi.class, NotesApi.NAME, NotesApi.PATH, NotesResource.class);
        }

        /**
         * Contributes {@link NotesResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new NotesResource();
        }
    }

    /** The application {@code folds} declared by {@link FoldsApi}, with its resource. */
    @Module
    public static final class Folds {

        private Folds() {}

        /**
         * Registers {@link FoldsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(FoldsApi.class, FoldsApi.NAME, FoldsApi.PATH, FoldsResource.class);
        }

        /**
         * Contributes {@link FoldsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FoldsResource();
        }
    }

    /** The application {@code folds} declared by {@link ProtectedFoldsApi}, with its resource. */
    @Module
    public static final class ProtectedFolds {

        private ProtectedFolds() {}

        /**
         * Registers {@link ProtectedFoldsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(ProtectedFoldsApi.class, FoldsApi.NAME, FoldsApi.PATH, FoldsResource.class);
        }

        /**
         * Contributes {@link FoldsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FoldsResource();
        }
    }

    /** The application {@code orders} declared by {@link OrdersApi}, with its resource. */
    @Module
    public static final class Orders {

        private Orders() {}

        /**
         * Registers {@link OrdersApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(OrdersApi.class, OrdersApi.NAME, OrdersApi.PATH, OrdersResource.class);
        }

        /**
         * Contributes {@link OrdersResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new OrdersResource();
        }
    }
}
