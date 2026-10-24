// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-plan validator that refuses a workflow plan declaring task reminders when the application's
 * Dagger graph has no event recorder.
 *
 * <h2>What is validated</h2>
 *
 * <p>The validator walks the {@link HumanTaskNode} nodes of the candidate plan. If any node
 * declares a non-null {@code reminders()} spec, at least one registered
 * {@link WorkflowSideEffectRecorder} must declare
 * {@link IntentKind#WORKFLOW_EVENT} as its {@link WorkflowSideEffectRecorder#kind()}. Without
 * a {@code WORKFLOW_EVENT} recorder, reminder fires would produce only {@code TASK_REMINDER_FIRED}
 * history entries and never notify downstream consumers — a silent misconfiguration.
 *
 * <p>If the check fails, {@link #validate(RegisteredWorkflowDefinition)} throws
 * {@link IllegalStateException} naming each offending (definitionId, version, stepId) triple of
 * that plan.
 *
 * <h2>When validation runs</h2>
 *
 * <p>The workflow registry runs every contributed {@link WorkflowDefinitionValidator} once for each
 * plan it builds, for code definitions at startup and for definitions registered later alike. A
 * code plan that fails the check aborts application startup; a plan registered at runtime that
 * fails it is refused and never published. The validator holds no registry reference.
 */
@Singleton
public final class WorkflowReminderComposeValidator implements WorkflowDefinitionValidator {

    private final Set<WorkflowSideEffectRecorder<SqlClient>> recorders;

    /**
     * Creates the validator over the application's registered recorders.
     *
     * @param recorders the set of registered side-effect recorders; checked for a
     *                  {@link IntentKind#WORKFLOW_EVENT} recorder
     */
    @Inject
    public WorkflowReminderComposeValidator(@WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>> recorders) {
        this.recorders = Set.copyOf(recorders);
    }

    /**
     * Validates that a candidate plan with task reminders has a {@link IntentKind#WORKFLOW_EVENT}
     * recorder registered.
     *
     * @param definition the candidate definition whose plan is checked
     * @throws IllegalStateException if the plan has a {@link HumanTaskNode} with a non-null
     *                               reminder spec but no {@code WORKFLOW_EVENT} recorder is
     *                               registered
     */
    @Override
    public void validate(RegisteredWorkflowDefinition definition) {
        List<String> offenders = collectReminderOffenders(definition);
        if (offenders.isEmpty()) {
            return;
        }
        boolean hasEventRecorder = recorders.stream().anyMatch(r -> r.kind() == IntentKind.WORKFLOW_EVENT);
        if (hasEventRecorder) {
            return;
        }
        String offenderList = offenders.stream().collect(Collectors.joining(", "));
        throw new IllegalStateException("One or more registered workflow plans declare task reminders but no "
                + IntentKind.WORKFLOW_EVENT.name()
                + " WorkflowSideEffectRecorder is registered. "
                + "Reminders emit TASK_REMINDER events via the WORKFLOW_EVENT recorder; without it, "
                + "reminder fires produce only history entries and never notify downstream consumers. "
                + "Add WorkflowEventsModule (vertique-workflow-events) to your application's Dagger "
                + "component, or remove reminders from the offending steps. "
                + "Offending (definitionId vN stepId): "
                + offenderList);
    }

    /**
     * Collects a human-readable offender string for each {@link HumanTaskNode} of the plan with
     * non-null reminders.
     *
     * @param definition the candidate definition
     * @return strings in the form {@code "(defId vN stepId)"}; empty when no reminders are declared
     */
    private static List<String> collectReminderOffenders(RegisteredWorkflowDefinition definition) {
        List<String> offenders = new ArrayList<>();
        String defId = definition.plan().definitionId();
        long version = definition.plan().definitionVersion();
        for (WorkflowNode node : definition.plan().nodes()) {
            if (node instanceof HumanTaskNode htn && htn.reminders() != null) {
                offenders.add("(" + defId + " v" + version + " " + htn.stepId() + ")");
            }
        }
        return offenders;
    }
}
