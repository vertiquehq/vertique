// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import dev.vertique.rest.validation.WebValidationStrategy;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;

/**
 * The {@code web-validation} wirings that wrap the canonical {@link AnnotationSchemaSource}. Each
 * nested module is used <em>instead of</em> {@code RestValidationModule}: both bind the {@code
 * OperationSchemaSource}, so a component lists exactly one of them.
 *
 * <p>Each nested module re-declares the bindings of {@code RestValidationModule} through {@link
 * WebValidation} (the {@code web-validation} strategy and the optional Bean Validation {@code
 * Validator}) and binds one component-scoped decorator of the canonical source the component builds
 * as the schema source. Like {@code RestValidationModule}, none binds a {@code BeanValidator}.
 *
 * <p>The configuration must select {@code web-validation} ({@code jaxrs.validationStrategy}), or the
 * schema source is never asked.
 */
public final class SharedSchemaSourceModules {

    private SharedSchemaSourceModules() {}

    /** The bindings every nested module shares. */
    @Module
    public abstract static class WebValidation {

        /**
         * Contributes the {@code web-validation} strategy to the strategy set.
         *
         * @param strategy the strategy
         * @return {@code strategy}
         */
        @Binds
        @IntoSet
        abstract RequestValidationStrategy webValidationStrategy(WebValidationStrategy strategy);

        /**
         * Declares the optional Bean Validation {@link Validator} the canonical source consults.
         *
         * @return the optional binding declaration
         */
        @BindsOptionalOf
        abstract Validator validator();
    }

    /** Binds a {@link CountingCanonicalSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class Counting {

        /**
         * Provides the component's counting source.
         *
         * @param canonical the canonical source
         * @return the counting source
         */
        @Provides
        @Singleton
        static CountingCanonicalSchemaSource source(AnnotationSchemaSource canonical) {
            return new CountingCanonicalSchemaSource(canonical);
        }

        /**
         * Binds the counting source as the component's schema source.
         *
         * @param source the counting source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(CountingCanonicalSchemaSource source);
    }

    /**
     * Binds an {@link OrderBodySwitchingSchemaSource} wrapping the canonical source that describes
     * {@link OrderV1} first and {@link OrderV2} afterwards.
     */
    @Module(includes = WebValidation.class)
    public abstract static class SwitchingFromFirstVariant {

        /**
         * Provides the component's switching source.
         *
         * @param canonical the canonical source
         * @return the switching source
         */
        @Provides
        @Singleton
        static OrderBodySwitchingSchemaSource source(AnnotationSchemaSource canonical) {
            return OrderBodySwitchingSchemaSource.startingAtFirstVariant(canonical);
        }

        /**
         * Binds the switching source as the component's schema source.
         *
         * @param source the switching source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(OrderBodySwitchingSchemaSource source);
    }

    /**
     * Binds an {@link OrderBodySwitchingSchemaSource} wrapping the canonical source that describes
     * {@link OrderV2} from its first resolution on.
     */
    @Module(includes = WebValidation.class)
    public abstract static class SwitchingFromSecondVariant {

        /**
         * Provides the component's switching source.
         *
         * @param canonical the canonical source
         * @return the switching source
         */
        @Provides
        @Singleton
        static OrderBodySwitchingSchemaSource source(AnnotationSchemaSource canonical) {
            return OrderBodySwitchingSchemaSource.startingAtSecondVariant(canonical);
        }

        /**
         * Binds the switching source as the component's schema source.
         *
         * @param source the switching source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(OrderBodySwitchingSchemaSource source);
    }
}
