// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.RecordTimePayloadValidator;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the start path checks a payload against a stored start schema through the bounded record-time
 * validator: the depth bound is applied before the schema is consulted, and a schema with a non-local reference is
 * refused before anything is compiled or resolved.
 */
class StartPayloadValidationTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "order", 2);
    private static final String VALUE = "private-start-marker";

    @Test
    @DisplayName("the start path refuses too-deep payloads and non-local refs without compiling the schema")
    void startPathRefusesTooDeepPayloadsAndNonLocalRefsWithoutCompiling() {
        // Given a permissive stored schema, a stored schema with a nested remote $ref, payloads nested 64 and 65
        // levels, and compilers that count their calls or fail the test when they are reached
        JsonObject permissive = new JsonObject("{\"type\":\"object\"}");
        JsonObject remote = new JsonObject(
                "{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"https://example.test/s.json\"}}}");
        AtomicInteger compilations = new AtomicInteger();
        var counting = new StartPayloadValidation(
                new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024), schema -> {
                    compilations.incrementAndGet();
                    return payload -> List.of();
                }));
        var resolving = new StartPayloadValidation(
                new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024), schema -> {
                    throw new AssertionError("a non-local $ref must be refused before compilation");
                }));
        JsonObject atDepthLimit = nested(64);
        JsonObject overDepthLimit = nested(65);

        // When each payload is validated against the permissive schema, and a valid payload against the remote one
        // Then 64 levels pass, 65 levels are refused naming the depth limit, the remote schema is refused naming the
        // definition and the reason, and nothing was compiled for either refusal
        assertAll(
                "start payload validation",
                () -> assertDoesNotThrow(
                        () -> counting.validate(atDepthLimit, permissive, KEY), "64 levels of nesting are accepted"),
                () -> assertThat(compilations)
                        .as("compilations after the accepted payload")
                        .hasValue(1),
                () -> {
                    WorkflowInvalidPayloadException refusal = assertThrows(
                            WorkflowInvalidPayloadException.class,
                            () -> counting.validate(overDepthLimit, permissive, KEY),
                            "65 levels of nesting are refused");
                    assertThat(refusal.getMessage()).contains("depth", "64").doesNotContain(VALUE);
                    assertThat(refusal.violations().toString()).doesNotContain(VALUE);
                },
                () -> assertThat(compilations)
                        .as("compilations after the refused payload")
                        .hasValue(1),
                () -> {
                    WorkflowInvalidPayloadException refusal = assertThrows(
                            WorkflowInvalidPayloadException.class,
                            () -> resolving.validate(new JsonObject().put("child", 1), remote, KEY),
                            "a remote $ref is refused");
                    assertThat(refusal.getMessage()).contains(KEY.definitionId(), "non-local $ref");
                });
    }

    /** Wraps a scalar iteratively; every object contributes exactly one depth level. */
    private static JsonObject nested(int depth) {
        Object payload = VALUE;
        for (int level = 0; level < depth; level++) {
            payload = new JsonObject().put("child", payload);
        }
        return (JsonObject) payload;
    }
}
