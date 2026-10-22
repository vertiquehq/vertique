// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the class-name fallback of decision payload validation: a declared name that the index does not hold is
 * resolved as a class name without running the class's static initializer, and a name that resolves nowhere fails
 * with a definition exception.
 */
class WorkflowPayloadsClassInitializationTest {

    private static final AtomicBoolean ONE_ARGUMENT_PROBE_INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean EMPTY_INDEX_PROBE_INITIALIZED = new AtomicBoolean();

    /** A class that records when its static initializer runs. Nothing else in this test touches it. */
    static final class OneArgumentProbe {
        static {
            ONE_ARGUMENT_PROBE_INITIALIZED.set(true);
        }
    }

    /** A class that records when its static initializer runs. Nothing else in this test touches it. */
    static final class EmptyIndexProbe {
        static {
            EMPTY_INDEX_PROBE_INITIALIZED.set(true);
        }
    }

    @Test
    @DisplayName("the class-name lookup without an index does not initialize the class it resolves")
    void classNameLookupWithoutAnIndexDoesNotInitializeTheClass() {
        // Given: a class whose static initializer has not run
        assertThat(ONE_ARGUMENT_PROBE_INITIALIZED).isFalse();

        // When: a decision payload without a value is validated against its binary name
        // Then: the class resolves, so the missing payload is the failure, and the class is still not initialized
        assertThatThrownBy(() -> WorkflowPayloads.validateAndCoerceTaskPayload(null, OneArgumentProbe.class.getName()))
                .isInstanceOf(WorkflowInvalidPayloadException.class);
        assertThat(ONE_ARGUMENT_PROBE_INITIALIZED).isFalse();
    }

    @Test
    @DisplayName("the class-name fallback behind an index that lacks the name does not initialize the class")
    void classNameFallbackBehindAnEmptyIndexDoesNotInitializeTheClass() {
        // Given: an index that holds nothing and a class whose static initializer has not run
        assertThat(EMPTY_INDEX_PROBE_INITIALIZED).isFalse();

        // When: a decision payload without a value is validated against the binary name
        // Then: the class resolves through the fallback, so the missing payload is the failure, and it is not
        // initialized
        assertThatThrownBy(() -> WorkflowPayloads.validateAndCoerceTaskPayload(
                        null, EmptyIndexProbe.class.getName(), new DefaultWorkflowTypeNames()))
                .isInstanceOf(WorkflowInvalidPayloadException.class);
        assertThat(EMPTY_INDEX_PROBE_INITIALIZED).isFalse();
    }

    @Test
    @DisplayName("a name that neither the index nor the class loader resolves fails with a definition exception")
    void unresolvableNameFailsWithADefinitionException() {
        assertThatThrownBy(() -> WorkflowPayloads.validateAndCoerceTaskPayload(
                        null, "no.such.Payload", new DefaultWorkflowTypeNames()))
                .isExactlyInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("no.such.Payload");
    }
}
