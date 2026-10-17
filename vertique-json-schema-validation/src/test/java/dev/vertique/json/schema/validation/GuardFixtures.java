// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;

/**
 * Fixtures for the pattern-input guard tests: schemas with pattern positions, and a validation call that
 * brackets the guard's counting window the way a caller does.
 */
final class GuardFixtures {

    private GuardFixtures() {}

    /**
     * Returns an object schema whose one property, {@code s}, is a string with the given {@code pattern}.
     *
     * @param pattern the regular expression
     * @return {@code {"type": "object", "properties": {"s": {"type": "string", "pattern": <pattern>}}}}
     */
    static JsonObject schemaWithPattern(String pattern) {
        return Schemas.objectWith("s", Schemas.stringWithPattern(pattern));
    }

    /**
     * Returns an object schema with a patterned string {@code s} and an unpatterned string {@code t}.
     *
     * @param pattern the regular expression of {@code s}
     * @return the schema
     */
    static JsonObject schemaWithPatternedAndPlainString(String pattern) {
        return Schemas.objectWith("s", Schemas.stringWithPattern(pattern), "t", new JsonObject().put("type", "string"));
    }

    /**
     * Returns an object schema with two patterned string properties, {@code a} and {@code b}.
     *
     * @param pattern the regular expression of both
     * @return the schema
     */
    static JsonObject schemaWithTwoPatterns(String pattern) {
        return Schemas.objectWith("a", Schemas.stringWithPattern(pattern), "b", Schemas.stringWithPattern(pattern));
    }

    /**
     * Compiles {@code schema} with the guard and the shared Draft 2020-12 options.
     *
     * @param guard  the guard
     * @param schema the schema
     * @return the guarded validator
     */
    static Validator compile(PatternInputGuard guard, JsonObject schema) {
        return guard.compile(schema, JsonSchemaValidationOptions.draft202012());
    }

    /**
     * Compiles {@code schema} with the guard, then validates {@code payload} inside one counting window
     * that is closed before this method returns.
     *
     * @param guard   the guard
     * @param schema  the schema
     * @param payload the instance
     * @return the engine's result
     */
    static OutputUnit validate(PatternInputGuard guard, JsonObject schema, Object payload) {
        return validate(guard, compile(guard, schema), payload);
    }

    /**
     * Validates {@code payload} with an already compiled guarded validator inside one counting window that
     * is closed before this method returns, also when validation throws.
     *
     * @param guard     the guard that compiled {@code validator}
     * @param validator the guarded validator
     * @param payload   the instance
     * @return the engine's result
     */
    static OutputUnit validate(PatternInputGuard guard, Validator validator, Object payload) {
        try (PatternInputGuard.Window window = guard.openWindow()) {
            return validator.validate(payload);
        }
    }
}
