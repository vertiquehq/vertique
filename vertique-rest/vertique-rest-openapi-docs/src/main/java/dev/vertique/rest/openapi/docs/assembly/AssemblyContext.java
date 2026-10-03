// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.diagnostics.DocumentWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The inputs the {@link DocumentAssembler} reads besides the publication, shared by every document
 * of the component: the bound {@link OperationSchemaSource}, if the component binds one, and the
 * {@link JsonMapperProfileRegistry} used to build input-direction schema generators, and the
 * {@link DocumentWarnings} guard of the component, and the registered response producers.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param schemaSource the bound operation schema source, or empty when none is bound
 * @param profiles the JSON mapper profile registry
 * @param warnings the documentation module's warnings of the component
 * @param producerBindings the registered response producer bindings; an unmodifiable copy is stored
 * @param securitySchemeHandlers the registered security scheme handlers, looked up by scheme name for
 *     the schemes a document's operations reference
 */
public record AssemblyContext(
        Optional<OperationSchemaSource> schemaSource,
        JsonMapperProfileRegistry profiles,
        DocumentWarnings warnings,
        Set<ResponseProducerBinding<?>> producerBindings,
        Set<SecuritySchemeHandler> securitySchemeHandlers) {

    public AssemblyContext {
        Objects.requireNonNull(schemaSource, "schemaSource");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(warnings, "warnings");
        producerBindings = Set.copyOf(producerBindings);
        securitySchemeHandlers = Set.copyOf(securitySchemeHandlers);
    }
}
