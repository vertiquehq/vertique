// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.workflow.exception.WorkflowConflictException;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
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
}
