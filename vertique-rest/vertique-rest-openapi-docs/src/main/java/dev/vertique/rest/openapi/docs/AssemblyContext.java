// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The inputs the {@link DocumentAssembler} reads besides the publication, shared by every document
 * of the component: the bound {@link OperationSchemaSource}, if the component binds one, and the
 * {@link JsonMapperProfileRegistry} used to build input-direction schema generators, and the
 * {@link DocumentWarnings} guard of the component, and the registered response producers.
 *
 * @param schemaSource the bound operation schema source, or empty when none is bound
 * @param profiles the JSON mapper profile registry
 * @param warnings the documentation module's warnings of the component
 * @param producerBindings the registered response producer bindings; an unmodifiable copy is stored
 * @param securitySchemeHandlers the registered security scheme handlers, looked up by scheme name for
 *     the schemes a document's operations reference
 */
record AssemblyContext(
        Optional<OperationSchemaSource> schemaSource,
        JsonMapperProfileRegistry profiles,
        DocumentWarnings warnings,
        Set<ResponseProducerBinding<?>> producerBindings,
        Set<SecuritySchemeHandler> securitySchemeHandlers) {

    /**
     * Creates a context with the given warnings, response producers and no registered security scheme
     * handlers.
     *
     * @param schemaSource the bound operation schema source, or empty when none is bound
     * @param profiles the JSON mapper profile registry
     * @param warnings the documentation module's warnings of the component
     * @param producerBindings the registered response producer bindings
     */
    AssemblyContext(
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            DocumentWarnings warnings,
            Set<ResponseProducerBinding<?>> producerBindings) {
        this(schemaSource, profiles, warnings, producerBindings, Set.of());
    }

    /**
     * Creates a context with the given warnings and no registered response producers.
     *
     * @param schemaSource the bound operation schema source, or empty when none is bound
     * @param profiles the JSON mapper profile registry
     * @param warnings the documentation module's warnings of the component
     */
    AssemblyContext(
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            DocumentWarnings warnings) {
        this(schemaSource, profiles, warnings, Set.of());
    }

    /**
     * Creates a context with its own fresh {@link DocumentWarnings}.
     *
     * @param schemaSource the bound operation schema source, or empty when none is bound
     * @param profiles the JSON mapper profile registry
     */
    AssemblyContext(Optional<OperationSchemaSource> schemaSource, JsonMapperProfileRegistry profiles) {
        this(schemaSource, profiles, new DocumentWarnings());
    }

    AssemblyContext {
        Objects.requireNonNull(schemaSource, "schemaSource");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(warnings, "warnings");
        producerBindings = Set.copyOf(producerBindings);
        securitySchemeHandlers = Set.copyOf(securitySchemeHandlers);
    }
}
