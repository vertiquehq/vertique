// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;

/**
 * Compiles plain, unguarded validators with the shared Draft 2020-12 options, so a test can obtain the
 * engine's own result for a schema and payload in one line.
 */
final class Validators {

    private Validators() {}

    /**
     * Compiles a private copy of {@code schema}, because compiling writes into the object it is given.
     *
     * @param schema the schema
     * @return the validator
     */
    static Validator compile(JsonObject schema) {
        return Validator.create(JsonSchema.of(schema.copy()), JsonSchemaValidationOptions.draft202012());
    }

    /**
     * Compiles the boolean schema {@code false}, which rejects every instance.
     *
     * @return the validator
     */
    static Validator compileFalseSchema() {
        return Validator.create(JsonSchema.of(false), JsonSchemaValidationOptions.draft202012());
    }

    /**
     * Validates {@code payload} against {@code schema}.
     *
     * @param schema  the schema
     * @param payload the instance
     * @return the engine's result
     */
    static OutputUnit validate(JsonObject schema, Object payload) {
        return compile(schema).validate(payload);
    }
}
