// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import jakarta.inject.Singleton;

/** Dagger modules binding one component-scoped schema source as the {@link OperationSchemaSource}. */
public final class SchemaSourceModules {

    private SchemaSourceModules() {}

    /** Binds a deterministic {@link CountingSchemaSource}. */
    @Module
    public static final class Counting {

        private Counting() {}

        /**
         * Provides the component's counting source.
         *
         * @return a new counting source
         */
        @Provides
        @Singleton
        static CountingSchemaSource countingSchemaSource() {
            return new CountingSchemaSource();
        }

        /**
         * Binds the counting source as the schema source.
         *
         * @param source the counting source
         * @return {@code source}
         */
        @Provides
        static OperationSchemaSource operationSchemaSource(CountingSchemaSource source) {
            return source;
        }
    }

    /** Binds a {@link StatefulSchemaSource}, also exposed as its {@link CountingSchemaSource}. */
    @Module
    public static final class Stateful {

        private Stateful() {}

        /**
         * Provides the component's stateful source.
         *
         * @return a new stateful source
         */
        @Provides
        @Singleton
        static StatefulSchemaSource statefulSchemaSource() {
            return new StatefulSchemaSource();
        }

        /**
         * Exposes the stateful source as the component's counting source.
         *
         * @param source the stateful source
         * @return {@code source}
         */
        @Provides
        static CountingSchemaSource countingSchemaSource(StatefulSchemaSource source) {
            return source;
        }

        /**
         * Binds the stateful source as the schema source.
         *
         * @param source the stateful source
         * @return {@code source}
         */
        @Provides
        static OperationSchemaSource operationSchemaSource(StatefulSchemaSource source) {
            return source;
        }
    }
}
