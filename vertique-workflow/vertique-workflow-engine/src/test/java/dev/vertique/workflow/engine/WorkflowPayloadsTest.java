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
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowType;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /** Start payload type of the start rejection tests. */
    record StartPayload(String requester) {}

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

    // --- Type-name resolution ---

    /** Decision payload type that carries a logical name. */
    @WorkflowType("approval")
    record Approval(boolean approved, String note) {}

    record ApprovalState(String id) {}

    record ApprovalStart(String id) {}

    @WorkflowContract(definitionId = "approval-saga", definitionVersion = 1)
    interface ApprovalContract {}

    @Test
    @DisplayName("a decision payload resolves through the type names by its logical and by its binary name")
    void decisionPayloadResolvesThroughTypeNames() {
        // Given: an index holding Approval under its logical name, and a context class loader that records requests
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        new DefaultWorkflowRegistry(null, Set.of(), names).register(approvalDefinition());
        RecordingLoader loader = new RecordingLoader(getClass().getClassLoader());
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        JsonObject payload = new JsonObject().put("approved", true).put("note", "looks fine");
        try {
            // When: the payload is validated against the logical name, then against the binary name of a row
            // written before the annotation existed
            Object byLogicalName = WorkflowPayloads.validateAndCoerceTaskPayload(payload, "approval", names);
            Object byBinaryName =
                    WorkflowPayloads.validateAndCoerceTaskPayload(payload, Approval.class.getName(), names);

            // Then: both calls return an Approval with the payload's values
            Approval expected = new Approval(true, "looks fine");
            assertEquals(expected, assertInstanceOf(Approval.class, byLogicalName));
            assertEquals(expected, assertInstanceOf(Approval.class, byBinaryName));

            // And: the index was the only lookup path, so the context class loader never saw the logical name
            assertFalse(loader.requested.contains("approval"), "requested: " + loader.requested);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static WorkflowDefinition<ApprovalState, ApprovalContract> approvalDefinition() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<ApprovalContract> contract() {
                return ApprovalContract.class;
            }

            @Override
            public Class<ApprovalState> stateType() {
                return ApprovalState.class;
            }

            @Override
            public String definitionId() {
                return "approval-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<ApprovalState> wf) {
                wf.init(ApprovalStart.class, start -> new ApprovalState(start.id()))
                        .initialStep("review")
                        .task("review")
                        .assignToRole("reviewers")
                        .decision("approve", Approval.class)
                        .onDecision((state, decision) -> state)
                        .toStep("done")
                        .build()
                        .complete("done");
            }
        };
    }

    /** A loader that delegates to its parent and records the class names it is asked for. */
    private static final class RecordingLoader extends ClassLoader {

        final List<String> requested = new ArrayList<>();

        RecordingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            requested.add(name);
            return super.loadClass(name);
        }
    }

    private static void assertSingleDetail(WorkflowInvalidPayloadException rejection, String message, String type) {
        List<ViolationDetail> violations = rejection.violations();
        assertEquals(1, violations.size(), "exactly one detail expected, got " + violations);
        assertNull(violations.get(0).path(), "a whole-payload rejection locates nothing");
        assertEquals(message, violations.get(0).message());
        assertEquals(type, violations.get(0).type());
        assertEquals("invalid decision payload", rejection.getMessage());
    }

    private static void assertSingleDetail(
            WorkflowInvalidPayloadException rejection, String message, String type, String exceptionMessage) {
        List<ViolationDetail> violations = rejection.violations();
        assertEquals(1, violations.size(), "exactly one detail expected, got " + violations);
        assertNull(violations.get(0).path(), "a whole-payload rejection locates nothing");
        assertEquals(message, violations.get(0).message());
        assertEquals(type, violations.get(0).type());
        assertEquals(exceptionMessage, rejection.getMessage());
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

    /**
     * Start payload rule: mirrors the decision rule with the start message; a void start type
     * accepts only null, a non-void start type requires a payload that binds.
     */
    @Nested
    @DisplayName("Start payload rule")
    class StartPayloadRule {

        private static final String START_REJECTION = "invalid start payload";

        @Test
        @DisplayName("a payload for a void start type fails with the payload detail, for both void markers")
        void payloadForAVoidStartTypeFailsWithThePayloadDetail() {
            for (Class<?> voidType : List.of(void.class, Void.class)) {
                // Given: a void start type; When: a payload object is supplied
                WorkflowInvalidPayloadException rejection = assertThrows(
                        WorkflowInvalidPayloadException.class,
                        () -> WorkflowPayloads.validateAndCoerceStartPayload(new JsonObject().put("x", 1), voidType),
                        voidType.getName());

                // Then: one payload detail and the start message
                assertSingleDetail(
                        rejection,
                        "payload is not accepted for a start type without a payload",
                        "payload",
                        START_REJECTION);
            }
        }

        @Test
        @DisplayName("a null payload for a non-void start type fails with the payload-required detail")
        void nullPayloadForANonVoidStartTypeFailsWithThePayloadRequiredDetail() {
            // Given: a non-void start type; When: the payload is null
            WorkflowInvalidPayloadException rejection = assertThrows(
                    WorkflowInvalidPayloadException.class,
                    () -> WorkflowPayloads.validateAndCoerceStartPayload(null, StartPayload.class));

            // Then: one payload detail and the start message
            assertSingleDetail(rejection, "payload is required", "payload", START_REJECTION);
        }

        @Test
        @DisplayName("a binding failure is wrapped value-free with the decode cause")
        void bindingFailureIsWrappedValueFreeWithTheDecodeCause() {
            // Given: a payload whose requester is an object, so it cannot bind to StartPayload
            JsonObject payload = new JsonObject().put("requester", new JsonObject().put("n", SENTINEL));

            // When: it is validated against StartPayload
            WorkflowInvalidPayloadException rejection = assertThrows(
                    WorkflowInvalidPayloadException.class,
                    () -> WorkflowPayloads.validateAndCoerceStartPayload(payload, StartPayload.class));

            // Then: the frozen binding detail, the decode failure as cause, and no value or type name
            assertSingleDetail(rejection, "payload does not bind to the declared type", "binding", START_REJECTION);
            assertInstanceOf(DecodeException.class, rejection.getCause());
            String visible = rejection.getMessage() + rejection.violations();
            assertFalse(visible.contains(SENTINEL), "no payload value may appear in the message or details");
            assertFalse(visible.contains("StartPayload"), "the payload class name must not appear");
        }

        @Test
        @DisplayName("a null payload for a void start type returns null, for both void markers")
        void nullPayloadForAVoidStartTypeReturnsNull() {
            // Given: a void start type; When: the payload is null; Then: null comes back
            assertNull(WorkflowPayloads.validateAndCoerceStartPayload(null, void.class));
            assertNull(WorkflowPayloads.validateAndCoerceStartPayload(null, Void.class));
        }

        @Test
        @DisplayName("a valid payload is coerced to the start type")
        void validPayloadIsCoercedToTheStartType() {
            // Given: a payload object that binds to StartPayload
            JsonObject payload = new JsonObject().put("requester", "a");

            // When: it is validated; Then: the start type comes back with the bound value
            Object coerced = WorkflowPayloads.validateAndCoerceStartPayload(payload, StartPayload.class);
            assertEquals(new StartPayload("a"), assertInstanceOf(StartPayload.class, coerced));
        }
    }
}
