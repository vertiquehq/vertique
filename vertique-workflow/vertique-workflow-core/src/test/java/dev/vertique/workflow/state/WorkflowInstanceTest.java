// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the {@link WorkflowInstance#metadata()} field invariants introduced by PRD-WF-007
 * (durable context completion):
 *
 * <ul>
 *   <li>{@code metadata} is {@code null}-preserving — unlike {@link BranchToken#metadata()}, it is
 *       not normalised to {@link DurableMetadata#empty()} (C1: {@code null} means "no captured
 *       context", a semantically distinct state).
 *   <li>Every {@code with*} updater passes {@code metadata} through unchanged (immutability by
 *       omission — no {@code withMetadata} updater exists).
 * </ul>
 */
@DisplayName("WorkflowInstance.metadata field invariants")
class WorkflowInstanceTest {

    private static final WorkflowInstanceId ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-07-07T12:00:00Z");

    /** Every {@code with*} updater must pass a non-null {@code metadata} through unchanged. */
    @ParameterizedTest(name = "{0} preserves metadata")
    @MethodSource("withUpdaters")
    @DisplayName("every with* updater preserves non-null metadata")
    void withUpdaterPreservesMetadata(
            String updaterName, java.util.function.Function<WorkflowInstance, WorkflowInstance> updater) {
        DurableMetadata metadata = DurableMetadata.of("tenant", new JsonObject().put("id", "T1"));
        WorkflowInstance before = buildInstance(metadata);
        WorkflowInstance after = updater.apply(before);
        assertEquals(metadata, after.metadata(), updaterName + " must preserve metadata unchanged");
    }

    @Test
    @DisplayName("null metadata is preserved as null (not normalised to empty)")
    void nullMetadataIsPreservedAsNull() {
        WorkflowInstance instance = buildInstance(null);
        assertEquals(null, instance.metadata(), "null metadata must remain null, not DurableMetadata.empty()");
    }

    @Test
    @DisplayName("withStatus preserves null metadata")
    void withStatusPreservesNullMetadata() {
        WorkflowInstance before = buildInstance(null);
        WorkflowInstance after = before.withStatus(WorkflowStatus.COMPLETED);
        assertEquals(null, after.metadata(), "withStatus must not introduce metadata where none existed");
    }

    // --- Helpers ---

    /** Supplies each of the 7 {@code with*} updaters paired with a display name for the parameterized test. */
    static Stream<Arguments> withUpdaters() {
        return Stream.of(
                Arguments.of("withVersion", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withVersion(i.version() + 1)),
                Arguments.of("withStatus", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withStatus(WorkflowStatus.COMPLETED)),
                Arguments.of("withCurrentStepId", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withCurrentStepId("next-step")),
                Arguments.of("withWait(3-arg)", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withWait(WaitType.SIGNAL, "sig", UUID.randomUUID())),
                Arguments.of("withWait(2-arg)", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withWait(WaitType.TIMER, "timer-key")),
                Arguments.of("withState", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withState("{\"updated\":true}")),
                Arguments.of("withError", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withError("SOME_ERROR", "boom")),
                Arguments.of("withUpdatedAt", (java.util.function.Function<WorkflowInstance, WorkflowInstance>)
                        (WorkflowInstance i) -> i.withUpdatedAt(NOW.plusSeconds(60))));
    }

    /**
     * Builds a minimal {@link WorkflowInstance} with fixed base fields and the supplied metadata.
     *
     * @param metadata the durable metadata (may be {@code null})
     * @return a constructed workflow instance
     */
    private static WorkflowInstance buildInstance(DurableMetadata metadata) {
        return new WorkflowInstance(
                ID,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                metadata);
    }
}
