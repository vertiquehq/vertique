// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import jakarta.validation.Validator;

/**
 * Dagger module for the default annotation-driven request-validation strategy ({@code web-validation}).
 *
 * <p>Contributes the {@link WebValidationStrategy} into the {@code Set<RequestValidationStrategy>}
 * multibinding (selected by id {@code "web-validation"}) and binds {@link AnnotationSchemaSource} as the
 * {@link OperationSchemaSource} the gate uses to synthesize per-operation schemas. These bindings are
 * present only when an application depends on {@code vertique-rest-validation}; the default framework
 * distribution does, so {@code web-validation} (the {@link dev.vertique.rest.core.config.JaxRsConfig}
 * default) has a provider.
 */
@Module
public abstract class RestValidationModule {

    /**
     * Contributes the {@code web-validation} {@link RequestValidationStrategy} into the strategy
     * multibinding.
     *
     * @param strategy the web-validation strategy
     * @return the strategy as a {@link RequestValidationStrategy} set element
     */
    @Binds
    @IntoSet
    abstract RequestValidationStrategy webValidationStrategy(WebValidationStrategy strategy);

    /**
     * Binds the victools-backed {@link AnnotationSchemaSource} as the {@link OperationSchemaSource} for
     * the {@code web-validation} gate.
     *
     * @param source the annotation-driven schema source
     * @return the schema source binding
     */
    @Binds
    abstract OperationSchemaSource operationSchemaSource(AnnotationSchemaSource source);

    /**
     * Declares the optional application-bound {@link Validator}: when present (an application depends
     * on {@code vertique-validation}, or binds its own), {@link AnnotationSchemaSource} additionally
     * sources body-schema value constraints from Bean Validation metadata — the annotation walk still
     * runs first, as the floor every generation carries, and the metadata source only supplements or,
     * for a bounded set of shapes, corrects it; when absent, generation is unchanged from before this
     * binding existed. Mirrors the same {@code @BindsOptionalOf Validator} pattern {@code
     * vertique-mcp-server}'s {@code McpServerModule} already uses for tool input validation.
     */
    @BindsOptionalOf
    abstract Validator validator();
}
