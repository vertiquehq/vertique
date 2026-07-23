// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
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
 * Startup validator that asserts the application's Dagger graph is correctly composed when any
 * registered workflow plan declares task reminders.
 *
 * <h2>What is validated</h2>
 *
 * <p>For each registered plan, the validator walks all {@link HumanTaskNode} nodes. If any node
 * declares a non-null {@code reminders()} spec, at least one registered
 * {@link WorkflowSideEffectRecorder} must declare
 * {@link IntentKind#WORKFLOW_EVENT} as its {@link WorkflowSideEffectRecorder#kind()}. Without
 * a {@code WORKFLOW_EVENT} recorder, reminder fires would produce only {@code TASK_REMINDER_FIRED}
 * history entries and never notify downstream consumers — a silent misconfiguration.
 *
 * <p>If the check fails, the constructor throws {@link IllegalStateException} naming each
 * offending (definitionId, version, stepId) triple. This aborts application startup before any
 * workflow can run.
 *
 * <h2>How validation runs (forced-construction pattern)</h2>
 *
 * <p>The validator is enforced through the {@code RecorderRouter} constructor:
 * {@link RecorderRouter} takes this validator as a required
 * dependency, so Dagger must construct the validator before the router. Because
 * {@code RecorderRouter} is consumed inside {@code WorkflowEngine}, requesting
 * {@code WorkflowOperations}, {@code WorkflowEngine}, or {@code RecorderRouter} from the
 * application component forces the entire chain — including this validator — to be built. This
 * means validation runs at Dagger graph construction time for any production boot path that
 * requests the workflow engine (which is all of them). Plain {@code DaggerAppComponent.create()}
 * alone does not trigger it because Dagger is lazy, but production boot paths always request one
 * of those accessors at startup, which is sufficient for fail-fast semantics.
 *
 * <p>If any check fails the constructor throws {@link IllegalStateException} with a message
 * identifying the offending definitions, versions, and steps, aborting startup.
 *
 * <p>This validator implements {@link ComposeValidator} so the framework can materialize it in
 * the {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase,
 * forcing its construction-time checks without any app code referencing it directly.
 */
@Singleton
public final class WorkflowReminderComposeValidator implements ComposeValidator {

    /**
     * Validates that all registered workflow plans with task reminders have a
     * {@link IntentKind#WORKFLOW_EVENT} recorder registered.
     *
     * @param registry  the workflow registry; used to walk every registered plan
     * @param recorders the set of registered side-effect recorders; checked for a
     *                  {@link IntentKind#WORKFLOW_EVENT} recorder
     * @throws IllegalStateException if any registered plan has a {@link HumanTaskNode} with a
     *                               non-null reminder spec but no {@code WORKFLOW_EVENT} recorder
     *                               is registered
     */
    @Inject
    public WorkflowReminderComposeValidator(
            WorkflowRegistry registry, @WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>> recorders) {
        validateRemindersRequireEventRecorder(registry, recorders);
    }

    // --- Validation logic ---

    /**
     * Walks all registered plans; collects (definitionId, version, stepId) triples for any
     * {@link HumanTaskNode} that declares reminders. If any are found and no recorder declares
     * {@link IntentKind#WORKFLOW_EVENT}, throws with the full offender list.
     *
     * @param registry  the workflow registry to walk
     * @param recorders all registered recorders
     * @throws IllegalStateException if reminders are declared but no event recorder is registered
     */
    private static void validateRemindersRequireEventRecorder(
            WorkflowRegistry registry, Set<WorkflowSideEffectRecorder<SqlClient>> recorders) {
        List<String> offenders = collectReminderOffenders(registry);
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
     * Collects a list of human-readable offender strings for each {@link HumanTaskNode} with
     * non-null reminders across all registered plans.
     *
     * @param registry the workflow registry to walk
     * @return a list of strings in the form {@code "defId vN stepId"}; empty when no reminders
     *         are declared
     */
    private static List<String> collectReminderOffenders(WorkflowRegistry registry) {
        List<String> offenders = new ArrayList<>();
        for (RuntimeWorkflow rw : registry.allRegistered()) {
            String defId = rw.plan().definitionId();
            long version = rw.plan().definitionVersion();
            for (WorkflowNode node : rw.plan().nodes()) {
                if (node instanceof HumanTaskNode htn && htn.reminders() != null) {
                    offenders.add("(" + defId + " v" + version + " " + htn.stepId() + ")");
                }
            }
        }
        return offenders;
    }
}
