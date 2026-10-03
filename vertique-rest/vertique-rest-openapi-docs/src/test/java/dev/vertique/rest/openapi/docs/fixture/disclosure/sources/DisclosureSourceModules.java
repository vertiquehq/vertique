// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.openapi.docs.fixture.input.AcceptingBeanValidator;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import dev.vertique.rest.validation.WebValidationStrategy;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;
import java.util.Optional;

/**
 * The {@code web-validation} wirings that bind a fixture schema source in place of the canonical
 * one. Each nested module is used <em>instead of</em> {@code RestValidationModule} and {@code
 * InputValidationModule}: all three bind the {@code OperationSchemaSource}, so a component lists
 * exactly one of them.
 *
 * <p>Each nested module re-declares the bindings of {@code RestValidationModule} through {@link
 * WebValidation} (the {@code web-validation} strategy and the optional Bean Validation {@code
 * Validator}), binds an {@link AcceptingBeanValidator} as the {@link BeanValidator}, as {@code
 * InputValidationModule} does, and binds one component-scoped source as the schema source: the
 * canonical source itself ({@link Canonical}) or a fixture source.
 * The decorating sources wrap the real canonical {@link AnnotationSchemaSource} the component
 * builds; a component may expose the concrete source, for example {@code ManifestFreeSchemaSource
 * manifestFreeSource()}.
 *
 * <p>The configuration must select {@code web-validation} ({@code jaxrs.validationStrategy}), or the
 * schema source is never asked. A component typically lists {@code RestModule}, the documentation
 * module when it serves documents, {@code ConfigParsingModule}, {@code DocsTestSupportModule}, one of
 * these modules, and its application modules.
 */
public final class DisclosureSourceModules {

    private DisclosureSourceModules() {}

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

        /**
         * Provides the accepting Bean Validation binding.
         *
         * @return a validator that accepts everything
         */
        @Provides
        @Singleton
        static BeanValidator beanValidator() {
            return new AcceptingBeanValidator();
        }
    }

    /**
     * Binds the canonical {@link AnnotationSchemaSource} itself, unwrapped, with no recording sink: the
     * wiring of {@code RestValidationModule} plus the accepting {@link BeanValidator}.
     */
    @Module(includes = WebValidation.class)
    public abstract static class Canonical {

        /**
         * Binds the canonical source as the component's schema source.
         *
         * @param source the canonical source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(AnnotationSchemaSource source);
    }

    /** Binds a {@link ManifestFreeSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class ManifestFree {

        /**
         * Provides the component's source.
         *
         * @param canonical the canonical source
         * @return the source
         */
        @Provides
        @Singleton
        static ManifestFreeSchemaSource source(AnnotationSchemaSource canonical) {
            return new ManifestFreeSchemaSource(canonical);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(ManifestFreeSchemaSource source);
    }

    /** Binds a {@link ReplacingSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class Replacing {

        /**
         * Provides the component's source.
         *
         * @param canonical the canonical source
         * @return the source
         */
        @Provides
        @Singleton
        static ReplacingSchemaSource source(AnnotationSchemaSource canonical) {
            return new ReplacingSchemaSource(canonical);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(ReplacingSchemaSource source);
    }

    /** Binds an {@link InPlaceEditingSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class InPlaceEditing {

        /**
         * Provides the component's source.
         *
         * @param canonical the canonical source
         * @return the source
         */
        @Provides
        @Singleton
        static InPlaceEditingSchemaSource source(AnnotationSchemaSource canonical) {
            return new InPlaceEditingSchemaSource(canonical);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(InPlaceEditingSchemaSource source);
    }

    /** Binds a {@link ForeignProvenanceSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class ForeignProvenance {

        /**
         * Provides the component's source.
         *
         * @param canonical the canonical source
         * @return the source
         */
        @Provides
        @Singleton
        static ForeignProvenanceSchemaSource source(AnnotationSchemaSource canonical) {
            return new ForeignProvenanceSchemaSource(canonical);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(ForeignProvenanceSchemaSource source);
    }

    /** Binds a {@link PassThroughSchemaSource} wrapping the canonical source. */
    @Module(includes = WebValidation.class)
    public abstract static class PassThrough {

        /**
         * Provides the component's source.
         *
         * @param canonical the canonical source
         * @return the source
         */
        @Provides
        @Singleton
        static PassThroughSchemaSource source(AnnotationSchemaSource canonical) {
            return new PassThroughSchemaSource(canonical);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(PassThroughSchemaSource source);
    }

    /** Binds a {@link CanonicalSubclassSchemaSource} consulting the optional Bean Validation validator. */
    @Module(includes = WebValidation.class)
    public abstract static class CanonicalSubclass {

        /**
         * Provides the component's source.
         *
         * @param validator the optional Bean Validation validator
         * @return the source
         */
        @Provides
        @Singleton
        static CanonicalSubclassSchemaSource source(Optional<Validator> validator) {
            return new CanonicalSubclassSchemaSource(validator);
        }

        /**
         * Binds the source as the component's schema source.
         *
         * @param source the source
         * @return {@code source}
         */
        @Binds
        abstract OperationSchemaSource operationSchemaSource(CanonicalSubclassSchemaSource source);
    }
}
