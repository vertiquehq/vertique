// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.time.Instant;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Registry that stores the executable Java functions registered by {@link WorkflowBuilder} and
 * looks them up by {@link CallbackId}.
 *
 * <p>Functions are not stored in the {@link dev.vertique.workflow.plan.WorkflowPlan} because they
 * are not serializable. The plan holds only {@code CallbackId} references; the engine retrieves
 * the actual function from this registry at execution time.
 *
 * <p>Implementations are expected to be populated at definition-registration time and then
 * read-only during execution.
 *
 * @see WorkflowBuilder
 */
public interface WorkflowCallbackRegistry {

    /**
     * Retrieves the payload-factory function registered under the given callback id.
     *
     * <p>Payload factories have type {@code Function<S, Object>} where {@code S} is the workflow
     * state type. The engine casts the result to the expected type at dispatch time.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered payload-factory function; never null
     * @throws IllegalArgumentException if no function is registered for the given id
     */
    <S> Function<S, Object> payloadFactory(CallbackId id);

    /**
     * Retrieves the state-updater function registered under the given callback id.
     *
     * <p>State updaters have type {@code BiFunction<S, Object, S>} where {@code S} is the workflow
     * state type and the second parameter is the typed signal payload. The engine casts the payload
     * to the declared type before calling the updater.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered state-updater function; never null
     * @throws IllegalArgumentException if no function is registered for the given id
     */
    <S> BiFunction<S, Object, S> stateUpdater(CallbackId id);

    /**
     * Retrieves the decision-resolver function registered under the given callback id.
     *
     * <p>Decision resolvers have type {@code Function<S, String>} where {@code S} is the workflow
     * state type. The function returns the step id of the next node to execute.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered decision-resolver function; never null
     * @throws IllegalArgumentException if no function is registered for the given id
     */
    <S> Function<S, String> decisionResolver(CallbackId id);

    /**
     * Retrieves the fail-message-factory function registered under the given callback id.
     *
     * <p>Fail message factories have type {@code Function<S, String>} where {@code S} is the
     * workflow state type. The function produces a human-readable error message from the current
     * state.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered fail-message-factory function; never null
     * @throws IllegalArgumentException if no function is registered for the given id
     */
    <S> Function<S, String> failMessageFactory(CallbackId id);

    /**
     * Retrieves the timer-resolver function registered under the given callback id.
     *
     * <p>Timer resolvers have type {@code Function<S, Instant>} where {@code S} is the workflow
     * state type. The function computes the absolute fire time for a
     * {@link dev.vertique.workflow.plan.TimerSpec.FromState} spec by inspecting the current state.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}. Cycle-1
     * workflow definitions that do not register any timer resolvers need not override this method;
     * the engine only calls it when processing a {@code FromState} timer spec.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered timer-resolver function; never null
     * @throws UnsupportedOperationException if no timer resolver is registered for the given id
     */
    default <S> Function<S, Instant> timerResolver(CallbackId id) {
        throw new UnsupportedOperationException("timerResolver not registered for " + id);
    }

    /**
     * Retrieves the signal-applicator function registered under the given callback id.
     *
     * <p>Signal applicators have type {@code BiFunction<S, P, S>} where {@code S} is the workflow
     * state type and {@code P} is the signal payload type. The function merges an incoming signal
     * payload into the current workflow state, mirroring the role of the {@link #stateUpdater}
     * but registered separately to allow the DSL to distinguish between regular state updaters
     * and signal-applicators used in timeout-branch disambiguation.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     *
     * @param <S> the workflow state type
     * @param <P> the signal payload type
     * @param id the callback id to look up
     * @return the registered signal-applicator function; never null
     * @throws UnsupportedOperationException if no signal applicator is registered for the given id
     */
    default <S, P> BiFunction<S, P, S> signalApplicator(CallbackId id) {
        throw new UnsupportedOperationException("signalApplicator not registered for " + id);
    }

    /**
     * Retrieves the state-mutator function registered under the given callback id.
     *
     * <p>State mutators have type {@code Function<S, S>} where {@code S} is the workflow state
     * type. The function transforms the current state without any external input — used by timeout
     * branches (via {@link dev.vertique.workflow.plan.WaitSignalNode.TimeoutBranch#onTimeoutMutatorCallbackId()})
     * to apply any necessary state changes when the timeout path is taken.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered state-mutator function; never null
     * @throws UnsupportedOperationException if no state mutator is registered for the given id
     */
    default <S> Function<S, S> stateMutator(CallbackId id) {
        throw new UnsupportedOperationException("stateMutator not registered for " + id);
    }

    /**
     * Retrieves the decision-applicator function registered under the given callback id.
     *
     * <p>Decision applicators have type {@code BiFunction<S, P, S>} where {@code S} is the
     * workflow state type and {@code P} is the typed decision payload. The function merges an
     * incoming task-decision payload into the current workflow state, advancing the workflow to
     * the next step.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}. Workflow
     * definitions that do not register any human-task steps need not override this method; the
     * engine only calls it when processing a {@link dev.vertique.workflow.plan.HumanTaskNode}
     * completion.
     *
     * @param <S> the workflow state type
     * @param <P> the decision payload type
     * @param id the callback id to look up
     * @return the registered decision-applicator function; never null
     * @throws UnsupportedOperationException if no decision applicator is registered for the given
     *     id
     */
    default <S, P> BiFunction<S, P, S> decisionApplicator(CallbackId id) {
        throw new UnsupportedOperationException("decisionApplicator not supported by this registry");
    }

    /**
     * Retrieves the task-assignment-resolver function registered under the given callback id.
     *
     * <p>Assignment resolvers have type {@code Function<S, TaskAssignment>} where {@code S} is the
     * workflow state type. The function computes the initial
     * {@link dev.vertique.workflow.tasks.TaskAssignment} from the current workflow state at
     * task-creation time, used for
     * {@link dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.UserFromState},
     * {@link dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.RoleFromState}, and
     * {@link dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.QueueFromState} variants.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}. Workflow
     * definitions that use only literal assignment specs need not override this method.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered assignment-resolver function; never null
     * @throws UnsupportedOperationException if no assignment resolver is registered for the given
     *     id
     */
    default <S> Function<S, TaskAssignment> taskAssignmentResolver(CallbackId id) {
        throw new UnsupportedOperationException("taskAssignmentResolver not supported by this registry");
    }

    /**
     * Retrieves the subject-resolver function registered under the given callback id.
     *
     * <p>Subject resolvers have type {@code Function<S, WorkflowSubjectRef>} where {@code S} is
     * the workflow state type. The function derives the {@link WorkflowSubjectRef} from the
     * workflow's initial state at instance-creation time, used when the caller's
     * {@link dev.vertique.workflow.ops.StartCommand} does not supply a subject ref directly.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}. Workflow
     * definitions that do not configure a subject resolver (i.e., all cycle-1 through cycle-4
     * plans) need not override this method; the engine only calls it when
     * {@link dev.vertique.workflow.plan.WorkflowPlan#subjectResolverCallbackId()} is non-null.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered subject-resolver function; never null
     * @throws UnsupportedOperationException if no subject resolver is registered for the given id
     */
    default <S> Function<S, WorkflowSubjectRef> subjectResolver(CallbackId id) {
        throw new UnsupportedOperationException("subjectResolver not registered for " + id);
    }

    /**
     * Retrieves the branch-result reducer registered under the given callback id (PRD-WF-002).
     *
     * <p>Reducers have type {@code BiFunction<S, Map<String, BranchResult>, S>}. The fan-in
     * machinery passes the current workflow state and a map of per-branch results keyed by
     * {@code branchId} to compute the new state once the join policy has been satisfied.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}. Workflow
     * definitions that do not declare any {@link dev.vertique.workflow.plan.JoinNode} need not
     * override this method.
     *
     * @param <S> the workflow state type
     * @param id the callback id to look up
     * @return the registered reducer; never null
     * @throws UnsupportedOperationException if no reducer is registered for the given id
     */
    default <S> BiFunction<S, Map<String, BranchResult>, S> branchResultReducer(CallbackId id) {
        throw new UnsupportedOperationException("branchResultReducer not registered for " + id);
    }
}
