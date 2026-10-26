// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies encoded byte and container depth bounds without exposing values or overflowing the stack. */
class RecordTimePayloadValidatorTest {

    @Test
    @DisplayName("record-time payloads respect the encoded byte and container depth bounds")
    void payloadOverTheSizeOrDepthBoundIsRefused() {
        // Given a 1024-byte limit and payloads measured in encoded JSON bytes, including UTF-8 and escapes
        var validator = new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024));
        JsonObject atSizeLimit = Payloads.ofSize(1024);
        JsonObject overSizeLimit = Payloads.ofSize(1025);
        assertThat(Json.encodeToBuffer(atSizeLimit).length()).isEqualTo(1024);
        assertThat(Json.encodeToBuffer(overSizeLimit).length()).isEqualTo(1025);

        // When each size boundary and each kind of nested container is checked
        // Then the boundaries pass and every excess is a typed, value-free refusal
        assertAll(
                "record-time payload bounds",
                () -> assertDoesNotThrow(() -> validator.validate(atSizeLimit), "1024 encoded bytes are accepted"),
                () -> assertRefused(validator, overSizeLimit, "1025 encoded bytes", "maxPayloadBytes"),
                () -> assertDepthBounds(validator, Kind.OBJECT),
                () -> assertDepthBounds(validator, Kind.ARRAY));
    }

    /** Checks both sides of the depth boundary and a chain too deep for a recursive walk. */
    private static void assertDepthBounds(RecordTimePayloadValidator validator, Kind kind) {
        Object atDepthLimit = Payloads.nested(64, kind);
        Object overDepthLimit = Payloads.nested(65, kind);
        Object deeplyNested = Payloads.nested(100_000, kind);

        assertAll(
                kind + " nesting",
                () -> assertDoesNotThrow(() -> validator.validate(atDepthLimit), kind + " depth 64 is accepted"),
                () -> assertRefused(validator, overDepthLimit, kind + " depth 65", "depth", "64"),
                () -> assertRefused(validator, deeplyNested, kind + " depth 100000", "depth", "64"));
    }

    /** Requires the named bound and checks the whole cause chain for values and stack overflow. */
    private static void assertRefused(
            RecordTimePayloadValidator validator, Object payload, String description, String... boundFragments) {
        WorkflowInvalidPayloadException refusal = assertThrows(
                WorkflowInvalidPayloadException.class, () -> validator.validate(payload), description + " is refused");
        assertThat(refusal.getMessage()).as(description + " names its bound").contains(boundFragments);
        for (Throwable failure = refusal; failure != null; failure = failure.getCause()) {
            assertThat(failure)
                    .as(description + " never overflows the stack")
                    .isNotInstanceOf(StackOverflowError.class);
            if (failure.getMessage() != null) {
                assertThat(failure.getMessage())
                        .as(description + " never exposes the submitted value")
                        .doesNotContain(Payloads.VALUE);
            }
        }
        assertThat(refusal.violations().toString()).doesNotContain(Payloads.VALUE);
    }

    private enum Kind {
        OBJECT,
        ARRAY
    }

    private static final class Payloads {

        private static final String VALUE = "private-payload-marker";

        /** Builds a shallow value of exactly the requested encoded JSON byte length. */
        private static JsonObject ofSize(int encodedBytes) {
            String value = VALUE + "\u00e9\"\\\n";
            JsonObject payload = new JsonObject().put("value", value);
            int paddingBytes = encodedBytes - Json.encodeToBuffer(payload).length();
            return payload.put("value", value + "x".repeat(paddingBytes));
        }

        /** Wraps a scalar iteratively; every object or array contributes exactly one depth level. */
        private static Object nested(int depth, Kind kind) {
            Object payload = VALUE;
            for (int level = 0; level < depth; level++) {
                payload = switch (kind) {
                    case OBJECT -> new JsonObject().put("child", payload);
                    case ARRAY -> new JsonArray().add(payload);
                };
            }
            return payload;
        }
    }
}
