// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the static payload and guard helpers in {@link WorkflowPayloads}.
 *
 * <p>Covers the two behaviors specified in the C1 red-test spec:
 * <ul>
 *   <li>{@link WorkflowPayloads#coercePayload} returns {@code null} when given a {@code null}
 *       input, regardless of the target type.</li>
 *   <li>{@link WorkflowPayloads#requireRowUpdated} throws {@link WorkflowConflictException} when
 *       the row count is zero.</li>
 *   <li>{@link WorkflowPayloads#validateAndCoerceTaskPayload} fails caller payload mistakes (a
 *       payload for a void decision, a missing payload, a payload that does not bind) with
 *       {@link WorkflowInvalidPayloadException} and keeps an unresolvable declared class a
 *       {@link WorkflowDefinitionException}.</li>
 * </ul>
 *
 * <p>No database or Vert.x context is required — all helpers are pure static methods.
 */
class WorkflowPayloadsTest {

    @Test
    @DisplayName("coercePayload returns null for null input")
    void coercePayloadReturnsNullForNullInput() {
        Object result = WorkflowPayloads.coercePayload(null, String.class);
        assertNull(result, "coercePayload(null, ...) must return null without throwing");
    }

    @Test
    @DisplayName("requireRowUpdated throws WorkflowConflictException when rowCount is zero")
    void requireRowUpdatedThrowsConflictExceptionWhenZero() {
        Future<Void> future = WorkflowPayloads.requireRowUpdated(0, "test-entity");
        assertTrue(future.failed(), "Future should be failed when rowCount is 0");
        assertInstanceOf(
                WorkflowConflictException.class,
                future.cause(),
                "Failed future must carry a WorkflowConflictException");
    }

    // --- Task payload rejection ---

    /** Payload type of the decisions the rejection tests bind to. */
    record Comment(String comment) {}

    private static final String SENTINEL = "ZZSENTINEL";

    @Test
    @DisplayName("an unresolvable decision class stays a definition exception that names the type")
    void unresolvableDecisionClassStaysADefinitionException() {
        // Given: a payload and a declared type name that cannot be loaded
        // When: it is validated; Then: a definition exception (not the payload exception) names the type
        WorkflowDefinitionException failure = assertThrows(
                WorkflowDefinitionException.class,
                () -> WorkflowPayloads.validateAndCoerceTaskPayload(new JsonObject(), "com.example.Missing"));

        assertEquals(WorkflowDefinitionException.class, failure.getClass());
        assertTrue(failure.getMessage().contains("com.example.Missing"), "message must name the type");
    }

    private static void assertSingleDetail(WorkflowInvalidPayloadException rejection, String message, String type) {
        List<ViolationDetail> violations = rejection.violations();
        assertEquals(1, violations.size(), "exactly one detail expected, got " + violations);
        assertEquals("", violations.get(0).path());
        assertEquals(message, violations.get(0).message());
        assertEquals(type, violations.get(0).type());
        assertEquals("invalid decision payload", rejection.getMessage());
    }

    /** Presence rules: a void decision accepts only null, a non-void decision requires a payload. */
    @Nested
    @DisplayName("Payload presence rules")
    class PresenceRules {

        @Test
        @DisplayName("a payload for a void decision fails with the payload detail, for both void markers")
        void payloadForAVoidDecisionFailsWithThePayloadDetail() {
            for (String voidName : List.of("void", "java.lang.Void")) {
                // Given: a void decision type name; When: an (empty) payload object is supplied
                WorkflowInvalidPayloadException rejection = assertThrows(
                        WorkflowInvalidPayloadException.class,
                        () -> WorkflowPayloads.validateAndCoerceTaskPayload(new JsonObject(), voidName),
                        voidName);

                // Then: one payload detail
                assertSingleDetail(
                        rejection, "payload is not accepted for a decision without a payload type", "payload");
            }
        }

        @Test
        @DisplayName("a null payload for a non-void decision fails with the payload-required detail")
        void nullPayloadForANonVoidDecisionFailsWithThePayloadRequiredDetail() {
            // Given: a non-void decision type; When: the payload is null
            WorkflowInvalidPayloadException rejection = assertThrows(
                    WorkflowInvalidPayloadException.class,
                    () -> WorkflowPayloads.validateAndCoerceTaskPayload(null, Comment.class.getName()));

            // Then: one payload detail
            assertSingleDetail(rejection, "payload is required", "payload");
        }
    }

    /** Binding: a payload Jackson cannot map fails value-free with the decode failure as cause. */
    @Nested
    @DisplayName("Payload binding")
    class Binding {

        @Test
        @DisplayName("a binding failure is wrapped value-free with the decode cause")
        void bindingFailureIsWrappedValueFreeWithTheDecodeCause() {
            // Given: a payload whose comment is an object, so it cannot bind to Comment
            JsonObject payload = new JsonObject().put("comment", new JsonObject().put("n", SENTINEL));

            // When: it is validated against Comment
            WorkflowInvalidPayloadException rejection = assertThrows(
                    WorkflowInvalidPayloadException.class,
                    () -> WorkflowPayloads.validateAndCoerceTaskPayload(payload, Comment.class.getName()));

            // Then: the frozen binding detail, the decode failure as cause, and no value or type name
            assertSingleDetail(rejection, "payload does not bind to the declared type", "binding");
            assertInstanceOf(DecodeException.class, rejection.getCause());
            String visible = rejection.getMessage() + rejection.violations();
            assertFalse(visible.contains(SENTINEL), "no payload value may appear in the message or details");
            assertFalse(visible.contains("Comment"), "the payload class name must not appear");
        }
    }
}
