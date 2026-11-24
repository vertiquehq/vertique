// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.engine.testsupport.EmptySignalWaits;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a refusal repeats a caller-supplied name as supplied: a signal name or a caller
 * contract name that carries control characters or is long appears whole in the message, exactly like
 * an ordinary name. Cleaning untrusted text is the job of the boundary that accepts it.
 *
 * <p>The refusals tested here are decided before any repository, timer store or transaction is
 * touched, so the handler is built without them.
 */
class EchoAsSuppliedTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final String DEFINITION_ID = "echo-def";
    private static final String PINNED_CONTRACT = "approval";
    private static final String PLAN_HASH = "plan-hash";
    private static final int GRINNING_FACE = 0x1F600;

    /** A name with a line break, a control character and 100 supplementary code points after them. */
    private static final String HOSTILE_NAME = "forged" + "\r\n" + "LOG-LINE" + (char) 7
            + Character.toString(GRINNING_FACE).repeat(100);

    // --- Fixtures ---

    private static RuntimeWorkflow pinnedWorkflow() {
        WaitSignalNode wait = new WaitSignalNode(
                "wait-signal", "approve", Object.class.getName(), new CallbackId("state-updater"), "complete", null);
        List<WorkflowNode> nodes = List.of(wait, new CompleteNode("complete"));
        WorkflowPlan plan =
                new WorkflowPlan(DEFINITION_ID, 3L, PLAN_HASH, Object.class.getName(), wait.stepId(), nodes, null);
        return RuntimeWorkflow.of(
                plan,
                PINNED_CONTRACT,
                Object.class,
                Object.class,
                payload -> Map.of(),
                Map.of(),
                mock(WorkflowCallbackRegistry.class));
    }

    private static WorkflowInstance waitingInstance() {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                3L,
                PLAN_HASH,
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "wait-signal",
                WaitType.SIGNAL,
                "approve",
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                DurableMetadata.empty());
    }

    private static BranchToken tokenOnCompletedStep(WorkflowInstance instance) {
        return new BranchToken(
                UUID.randomUUID(),
                instance.id(),
                "fork",
                "branch-a",
                "complete",
                BranchStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                NOW,
                NOW,
                DurableMetadata.empty());
    }

    /** A handler whose collaborators the tested refusals never reach. */
    private static SignalHandler handlerResolving(RuntimeWorkflow pinned) {
        WorkflowRegistry registry = mock(WorkflowRegistry.class);
        when(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, 3L)))
                .thenReturn(pinned);
        return new SignalHandler(
                registry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new EmptyCommandRepository(),
                WorkflowContextBinder.noop(),
                null,
                null,
                null,
                new SignalWaits(
                        EmptySignalWaits.create(),
                        new EmptyCommandRepository(),
                        mock(WorkflowHistoryRecorder.class),
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Throwable failureOf(Future<?> future) {
        assertThat(future.failed()).as("the operation fails").isTrue();
        return future.cause();
    }

    /** Asserts the message repeats the name exactly as supplied, with no cut marker after it. */
    private static void assertEchoedAsSupplied(String message) {
        assertThat(message).as("message").isNotNull().contains(HOSTILE_NAME).doesNotContain(HOSTILE_NAME + "...");
    }

    // --- Tests ---

    @Test
    @DisplayName("a signal the pinned plan never waits for is refused with its name repeated as supplied")
    void unawaitedSignalNameIsRepeatedAsSupplied() {
        // Given: an instance pinned to a plan that waits for approve only
        SignalHandler handler = handlerResolving(pinnedWorkflow());
        WorkflowInstance instance = waitingInstance();

        // When: a signal with a hostile name is delivered
        Throwable failure = failureOf(handler.doApplySignal(
                instance, new SignalDeliveryContext(HOSTILE_NAME, "payload", "dedup-key"), mock(SqlClient.class)));

        // Then: the refusal is the signal rejection naming the instance and the pinned version
        assertThat(failure)
                .isInstanceOf(WorkflowSignalRejectedException.class)
                .hasMessageContaining(instance.id().value().toString())
                .hasMessageContaining("'" + DEFINITION_ID + "' v3");
        // and: it repeats the hostile name as supplied, in both places it names it
        assertEchoedAsSupplied(failure.getMessage());
    }

    @Test
    @DisplayName("an ordinary signal name is repeated whole in the refusal")
    void ordinarySignalNameIsRepeatedWhole() {
        // Given: an instance pinned to a plan that waits for approve only
        SignalHandler handler = handlerResolving(pinnedWorkflow());

        // When: a signal with an ordinary name that the plan never waits for is delivered
        Throwable failure = failureOf(handler.doApplySignal(
                waitingInstance(), new SignalDeliveryContext("escalate", "payload", "dedup-key"), null));

        // Then: the refusal names the signal and the pinned version
        assertThat(failure)
                .isInstanceOf(WorkflowSignalRejectedException.class)
                .hasMessageContaining("signal 'escalate'")
                .hasMessageContaining("'" + DEFINITION_ID + "' v3");
    }

    @Test
    @DisplayName("a branch signal refused for a step that waits for no signal repeats its name as supplied")
    void branchSignalNameIsRepeatedAsSupplied() {
        // Given: a branch token whose current step is not a signal wait
        SignalHandler handler = handlerResolving(pinnedWorkflow());
        WorkflowInstance instance = waitingInstance();

        // When: a branch signal with a hostile name is delivered
        Throwable failure = failureOf(handler.applyBranchSignal(
                instance, tokenOnCompletedStep(instance), pinnedWorkflow(), HOSTILE_NAME, "payload", null, null));

        // Then: it is refused as a definition error with the hostile name as supplied
        assertThat(failure).isInstanceOf(WorkflowDefinitionException.class);
        assertEchoedAsSupplied(failure.getMessage());
    }

    @Test
    @DisplayName("a caller contract name is repeated as supplied, and the pinned ids stay typed")
    void callerContractNameIsRepeatedAsSupplied() {
        // Given: an instance pinned to a definition of the approval contract
        RuntimeWorkflow pinned = pinnedWorkflow();
        WorkflowInstance instance = waitingInstance();

        // When: a caller names a hostile contract
        WorkflowDefinitionContractMismatchException refusal =
                ContractMismatch.refusal(instance, pinned, HOSTILE_NAME, "signal");

        // Then: the refusal repeats the caller's name as supplied
        assertThat(refusal).isNotNull();
        assertEchoedAsSupplied(refusal.getMessage());
        // and: it still names the pinned version and contract, and carries them as typed ids
        assertThat(refusal.getMessage())
                .contains("'" + DEFINITION_ID + "' v3")
                .contains("contract '" + PINNED_CONTRACT + "'");
        assertThat(refusal.definitionKey().definitionId()).isEqualTo(DEFINITION_ID);
        assertThat(refusal.definitionKey().definitionVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("an ordinary caller contract name is repeated whole, and the pinned contract is not a mismatch")
    void ordinaryCallerContractNameIsRepeatedWhole() {
        // Given: an instance pinned to a definition of the approval contract
        RuntimeWorkflow pinned = pinnedWorkflow();
        WorkflowInstance instance = waitingInstance();

        // When: a caller of another contract, one of the same contract and one naming none are checked
        WorkflowDefinitionContractMismatchException foreign =
                ContractMismatch.refusal(instance, pinned, "other", "query");
        WorkflowDefinitionContractMismatchException same =
                ContractMismatch.refusal(instance, pinned, PINNED_CONTRACT, "query");
        WorkflowDefinitionContractMismatchException none = ContractMismatch.refusal(instance, pinned, null, "query");

        // Then: only the foreign caller is refused, naming both contracts and the operation
        assertThat(foreign)
                .isNotNull()
                .hasMessageContaining("contract 'approval'")
                .hasMessageContaining("contract 'other'")
                .hasMessageContaining("cannot query");
        assertThat(same).isNull();
        assertThat(none).isNull();
    }
}
