// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import dev.vertique.rest.validation.WebValidationStrategy;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;

/**
 * The {@code web-validation} wiring of the input-assembly tests, used <em>instead of</em> {@code
 * RestValidationModule} (both bind the schema source, so a component lists one of them).
 *
 * <p>It re-declares every binding of {@code RestValidationModule} with one change: the schema source
 * is a component-scoped {@link RecordingSchemaSource} wrapping the canonical {@link
 * AnnotationSchemaSource}. Beside that it binds an {@link AcceptingBeanValidator} as the optional
 * {@link BeanValidator}, and contributes a component-scoped {@link RecordingInventoryPublicationHook} to the
 * publication hooks. A component exposes both recorders, for example {@code RecordingSchemaSource
 * recordingSource()} and {@code RecordingInventoryPublicationHook recordingHook()}.
 */
@Module
public abstract class InputValidationModule {

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
     * Binds the recording source as the component's schema source.
     *
     * @param source the recording source
     * @return {@code source}
     */
    @Binds
    abstract OperationSchemaSource operationSchemaSource(RecordingSchemaSource source);

    /**
     * Provides the component's recording source, wrapping the canonical source.
     *
     * @param canonical the canonical, annotation-driven source
     * @return the recording source
     */
    @Provides
    @Singleton
    static RecordingSchemaSource recordingSchemaSource(AnnotationSchemaSource canonical) {
        return new RecordingSchemaSource(canonical);
    }

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

    /**
     * Provides the component's recording inventory hook.
     *
     * @return a new hook
     */
    @Provides
    @Singleton
    static RecordingInventoryPublicationHook recordingInventoryHook() {
        return new RecordingInventoryPublicationHook();
    }

    /**
     * Contributes the recording inventory hook beside every other hook.
     *
     * @param hook the component's recording hook
     * @return {@code hook}
     */
    @Binds
    @IntoSet
    abstract MountPublicationHook inventoryHook(RecordingInventoryPublicationHook hook);
}
