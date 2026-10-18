// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.PatternInputGuard;
import io.vertx.core.json.JsonObject;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers shared by the keyed validation tests. */
final class Validations {

    /** A Jakarta validator, so that the generator's constraint supplement is on. */
    static final Validator BEAN_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Validations() {}

    /**
     * Asserts that no text of the result contains any of the forbidden fragments: not the result's own text form and
     * not one of its violations' path, message, type, argument names or argument values.
     *
     * @param result the result under test
     * @param forbidden the fragments that must not appear, such as a submitted value or an undeclared property name
     * @throws AssertionError if a fragment appears anywhere
     */
    static void assertNoValueIn(PayloadValidationResult result, String... forbidden) {
        assertThat(forbidden).as("fragments to search for").isNotEmpty().doesNotContain("");
        for (Map.Entry<String, String> field : fieldsOf(result).entrySet()) {
            assertThat(field.getValue()).as(field.getKey()).doesNotContain(forbidden);
        }
    }

    /**
     * What the validation engine itself reports for {@code payload}, as text, taken through the validator the cache
     * holds for {@code type}. This is the raw output a translation must not pass on.
     *
     * @param cache the cache the registry filled
     * @param type the payload type
     * @param payload the payload
     * @return the engine's result in text form
     */
    static String rawEngineOutput(PayloadSchemaCache cache, Class<?> type, JsonObject payload) {
        CachedPayloadSchema entry = cache.findByTypeName(type.getName()).orElseThrow();
        try (PatternInputGuard.Window ignored = entry.guard().openWindow()) {
            return entry.validator().validate(payload).toString();
        }
    }

    private static Map<String, String> fieldsOf(PayloadValidationResult result) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("result text", String.valueOf(result));
        int index = 0;
        for (ViolationDetail violation : result.violations()) {
            String at = "violation " + index++;
            fields.put(at + " text", String.valueOf(violation));
            fields.put(at + " path", String.valueOf(violation.path()));
            fields.put(at + " message", String.valueOf(violation.message()));
            fields.put(at + " type", String.valueOf(violation.type()));
            if (violation.args() != null) {
                for (Map.Entry<String, Object> arg : violation.args().entrySet()) {
                    fields.put(at + " args key", String.valueOf(arg.getKey()));
                    fields.put(at + " args value of " + arg.getKey(), String.valueOf(arg.getValue()));
                }
            }
        }
        return fields;
    }
}
