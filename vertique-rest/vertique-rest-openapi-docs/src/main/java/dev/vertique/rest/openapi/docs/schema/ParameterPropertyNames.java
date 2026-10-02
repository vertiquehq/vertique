// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.schema;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;

/**
 * Refuses a published parameter or form-field schema that holds the {@code propertyNames} keyword.
 *
 * <p>Only a request body carries a redaction manifest locating the reserved-name assertions under
 * {@code propertyNames}, so a captured parameter or form-field schema may not hold the keyword at any
 * schema position. The positions are those {@link SchemaPositions} walks: the text {@code
 * propertyNames} as data (inside {@code const}, {@code enum}, {@code default}, {@code examples}, or
 * {@code example}) or as a property name (a member of {@code properties}, {@code patternProperties},
 * {@code $defs}, or {@code dependentSchemas}) is not the keyword. The failure names the input and its
 * operation, never schema content.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class ParameterPropertyNames {

    /** The refused keyword. */
    private static final String KEYWORD = "propertyNames";

    private ParameterPropertyNames() {}

    /**
     * Fails when a captured parameter or form-field schema holds {@code propertyNames} at a schema
     * position.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param input the parameter or form field the schema was captured for
     * @param schema the document's own copy of the captured schema, only read
     * @throws RestConfigurationException when the schema holds the keyword
     */
    public static void refuse(String subject, SchemaPublicationSubject input, JsonNode schema) {
        SchemaPositions.walk(schema, (owner, keyword, value, pointer, atRoot) -> {
            if (KEYWORD.equals(keyword)) {
                throw new RestConfigurationException(subject + ": " + input.refusalPhrase()
                        + " has a schema holding the keyword '" + KEYWORD
                        + "'; parameter schemas carry no redaction manifest, so a published one may not hold it");
            }
        });
    }
}
