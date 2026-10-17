// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;

/**
 * The engine options shared by the framework modules that validate payloads against generated schemas:
 * Draft 2020-12, a fixed base URI, and the {@code Basic} output format.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract.
 * It is a stateless utility; every member is {@code static}.
 */
public final class JsonSchemaValidationOptions {

    private JsonSchemaValidationOptions() {}

    /**
     * Returns the shared options: {@link Draft#DRAFT202012}, base URI {@code https://vertique.local/}, and
     * {@link OutputFormat#Basic}.
     *
     * @return a new options instance on every call, so no caller can change another's options
     */
    public static JsonSchemaOptions draft202012() {
        return new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
    }
}
