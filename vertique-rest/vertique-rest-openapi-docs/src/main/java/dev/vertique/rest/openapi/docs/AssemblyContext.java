// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import java.util.Objects;
import java.util.Optional;

/**
 * The inputs the {@link DocumentAssembler} reads besides the publication, shared by every document
 * of the component: the bound {@link OperationSchemaSource}, if the component binds one, and the
 * {@link JsonMapperProfileRegistry} used to build input-direction schema generators, and the
 * {@link DocumentWarnings} guard of the component.
 *
 * @param schemaSource the bound operation schema source, or empty when none is bound
 * @param profiles the JSON mapper profile registry
 * @param warnings the documentation module's warnings of the component
 */
record AssemblyContext(
        Optional<OperationSchemaSource> schemaSource, JsonMapperProfileRegistry profiles, DocumentWarnings warnings) {

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
    }
}
