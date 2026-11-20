// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that decision payload validation resolves a declared type name through the type-name index only: a name
 * the index does not hold is never loaded as a class, even when a class with that binary name is on the class path.
 */
class WorkflowPayloadsNoClassNameFallbackTest {

    /** A loadable payload class that no index in this test holds, except where a test indexes it explicitly. */
    record Unindexed(String note) {}

    @Test
    @DisplayName("the binary name of a loadable class the index does not hold fails with a definition exception")
    void binaryNameOfAnUnindexedClassIsNotLoaded() {
        // Given: an index that holds nothing, and a payload that would bind to Unindexed if the class were loaded
        DefaultWorkflowTypeNames empty = new DefaultWorkflowTypeNames();
        JsonObject payload = new JsonObject().put("note", "hello");

        // When: the payload is validated against the binary name of that loadable class
        // Then: the name is unknown, the class is not loaded, and the failure names the name as unresolved
        assertThatThrownBy(
                        () -> WorkflowPayloads.validateAndCoerceTaskPayload(payload, Unindexed.class.getName(), empty))
                .isExactlyInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("Decision payloadTypeName 'dev.vertique.workflow.engine.")
                .hasMessageContaining("cannot be resolved");
    }

    @Test
    @DisplayName("the same binary name resolves once the index holds the class")
    void binaryNameOfAnIndexedClassResolves() {
        // Given: an index that holds Unindexed
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        names.add(null, List.of(Unindexed.class));

        // When: the payload is validated against its binary name
        Object coerced = WorkflowPayloads.validateAndCoerceTaskPayload(
                new JsonObject().put("note", "hello"), Unindexed.class.getName(), names);

        // Then: it binds to the class
        assertThat(coerced).isEqualTo(new Unindexed("hello"));
    }

    @Test
    @DisplayName("a name that is neither indexed nor a class fails with a definition exception")
    void unresolvableNameFailsWithADefinitionException() {
        assertThatThrownBy(() -> WorkflowPayloads.validateAndCoerceTaskPayload(
                        null, "no.such.Payload", new DefaultWorkflowTypeNames()))
                .isExactlyInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("no.such.Payload");
    }
}
