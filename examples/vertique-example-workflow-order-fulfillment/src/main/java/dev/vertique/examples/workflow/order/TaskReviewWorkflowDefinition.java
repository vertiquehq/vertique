// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.state.TaskReviewState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Workflow definition used by the cycle-3 human-task integration tests.
 *
 * <p>Registers a single definition that waits for a compliance-role human task with two decisions:
 * {@code "approve"} and {@code "reject"}. Both decisions capture a {@link String} payload and
 * encode the outcome into the workflow state, then advance to a terminal
 * {@link dev.vertique.workflow.plan.CompleteNode}.
 *
 * <h2>Definition</h2>
 * <ul>
 *   <li>Definition id: {@code "task-review"}</li>
 *   <li>Definition version: {@code 1}</li>
 *   <li>Initial step: {@code "review"} — a {@link dev.vertique.workflow.plan.HumanTaskNode}
 *       assigned to role {@code "compliance"}.</li>
 *   <li>Decision {@code "approve"}: state becomes {@code "approved:<payload>"}, advances to
 *       {@code "done"}.</li>
 *   <li>Decision {@code "reject"}: state becomes {@code "rejected:<payload>"}, advances to
 *       {@code "declined"}.</li>
 *   <li>{@code "done"} and {@code "declined"}: terminal
 *       {@link dev.vertique.workflow.plan.CompleteNode} steps.</li>
 * </ul>
 */
@Singleton
public final class TaskReviewWorkflowDefinition
        implements WorkflowDefinition<TaskReviewState, TaskReviewWorkflowDefinition.TaskReviewContract> {

    /** Start command / payload for the task-review workflow. */
    public record TaskReviewStart(String id) {}

    /**
     * Marker contract interface for the {@code "task-review"} workflow definition.
     *
     * <p>The registry requires each definition to declare a unique contract class. This interface
     * serves that requirement without exposing typed proxy methods — the task-review IT drives the
     * workflow via {@link dev.vertique.workflow.ops.WorkflowOperations} and
     * {@link dev.vertique.workflow.tasks.TaskService} directly.
     */
    public interface TaskReviewContract {}

    /**
     * Creates a new {@code TaskReviewWorkflowDefinition}.
     */
    @Inject
    public TaskReviewWorkflowDefinition() {}

    @Override
    public Class<TaskReviewContract> contract() {
        return TaskReviewContract.class;
    }

    @Override
    public Class<TaskReviewState> stateType() {
        return TaskReviewState.class;
    }

    @Override
    public String definitionId() {
        return "task-review";
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<TaskReviewState> wf) {
        wf.init(TaskReviewStart.class, cmd -> new TaskReviewState(null))
                .initialStep("review")
                .task("review")
                .assignToRole("compliance")
                .decision("approve", String.class)
                .onDecision((s, payload) -> new TaskReviewState("approved:" + payload))
                .toStep("done")
                .decision("reject", String.class)
                .onDecision((s, payload) -> new TaskReviewState("rejected:" + payload))
                .toStep("declined")
                .build()
                .complete("done")
                .complete("declined");
    }
}
