// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
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
 * Verifies that a refusal never repeats a caller-supplied name raw: a signal name or a caller
 * contract name that carries control characters or is thousands of code points long is cut and
 * sanitized in the message, while ordinary names and the typed accessors stay intact.
 *
 * <p>The refusals tested here are decided before any repository, timer store or transaction is
 * touched, so the handler is built without them.
 */
class BoundedEchoTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final String DEFINITION_ID = "bounded-echo-def";
    private static final String PINNED_CONTRACT = "approval";
    private static final String PLAN_HASH = "plan-hash";
    private static final int GRINNING_FACE = 0x1F600;
    private static final int ECHO_CODE_POINTS = 64;

    /** A name with a line break, a control character and 10000 supplementary code points after them. */
    private static final String HOSTILE_NAME = "forged" + "\r\n" + "LOG-LINE" + (char) 7
            + Character.toString(GRINNING_FACE).repeat(10_000);

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
                null);
    }

    private static Throwable failureOf(Future<?> future) {
        assertThat(future.failed()).as("the operation fails").isTrue();
        return future.cause();
    }

    /** Asserts the message is short, free of control characters and unpaired surrogates, and cut at 64 code points. */
    private static void assertBoundedAndSanitized(String message) {
        assertThat(message).as("message").isNotNull().hasSizeLessThan(1_000);
        assertThat(message.codePoints().noneMatch(Character::isISOControl))
                .as("no control character in the message")
                .isTrue();
        assertThat(message.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
                .as("no unpaired surrogate in the message")
                .isTrue();
        assertThat(message).doesNotContain(Character.toString(GRINNING_FACE).repeat(ECHO_CODE_POINTS + 1));
        // The name stays recognizable by its start; control characters are shown as question marks.
        assertThat(message).contains("forged??LOG-LINE?");
    }

    // --- Tests ---

    @Test
    @DisplayName("a signal the pinned plan never waits for is refused with its name cut and sanitized")
    void unawaitedSignalNameIsCutAndSanitized() {
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
        // and: it repeats the hostile name only cut and sanitized, in both places it names it
        assertBoundedAndSanitized(failure.getMessage());
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
    @DisplayName("a branch signal refused for a step that waits for no signal repeats its name cut and sanitized")
    void branchSignalNameIsCutAndSanitized() {
        // Given: a branch token whose current step is not a signal wait
        SignalHandler handler = handlerResolving(pinnedWorkflow());
        WorkflowInstance instance = waitingInstance();

        // When: a branch signal with a hostile name is delivered
        Throwable failure = failureOf(handler.applyBranchSignal(
                instance, tokenOnCompletedStep(instance), pinnedWorkflow(), HOSTILE_NAME, "payload", null, null));

        // Then: it is refused as a definition error with the hostile name cut and sanitized
        assertThat(failure).isInstanceOf(WorkflowDefinitionException.class);
        assertBoundedAndSanitized(failure.getMessage());
    }

    @Test
    @DisplayName("a caller contract name is repeated cut and sanitized, and the pinned ids stay typed")
    void callerContractNameIsCutAndSanitized() {
        // Given: an instance pinned to a definition of the approval contract
        RuntimeWorkflow pinned = pinnedWorkflow();
        WorkflowInstance instance = waitingInstance();

        // When: a caller names a hostile contract
        WorkflowDefinitionContractMismatchException refusal =
                ContractMismatch.refusal(instance, pinned, HOSTILE_NAME, "signal");

        // Then: the refusal repeats the caller's name only cut and sanitized
        assertThat(refusal).isNotNull();
        assertThat(refusal.getMessage()).hasSizeLessThan(1_000);
        assertThat(refusal.getMessage().codePoints().noneMatch(Character::isISOControl))
                .as("no control character in the message")
                .isTrue();
        assertThat(refusal.getMessage().codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
                .as("no unpaired surrogate in the message")
                .isTrue();
        assertThat(refusal.getMessage())
                .contains("forged??LOG-LINE?")
                .doesNotContain(Character.toString(GRINNING_FACE).repeat(ECHO_CODE_POINTS + 1));
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
