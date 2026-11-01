// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the correlation-key declaration on a signal wait: {@code correlateBy(...)} on every
 * {@code waitForSignal} chain records a key callback id on the {@link WaitSignalNode}, the plan hash
 * moves only when a key is declared, and the six-argument node constructor leaves the key absent.
 */
class WaitSignalCorrelationKeyTest {

    record State(String paymentReference) {}

    record Cmd(String reference) {}

    record Payload(String data) {}

    private static final String WAIT_STEP = "wait-payment";
    private static final String SIGNAL = "payment.captured";
    private static final Instant DEADLINE = Instant.parse("2030-12-31T23:59:59Z");

    /** Finishes one wait chain after {@code toStepOnSignal} and closes the plan with its terminal steps. */
    @FunctionalInterface
    private interface Chain {
        WorkflowBuilder<State> finish(WorkflowBuilder<State>.SignalWaitWithNextStep next);
    }

    /** One built definition: the builder that holds its callbacks and the plan it produced. */
    private record Built(WorkflowBuilder<State> builder, WorkflowPlan plan) {

        WaitSignalNode waitNode() {
            return plan.nodes().stream()
                    .filter(WaitSignalNode.class::isInstance)
                    .map(WaitSignalNode.class::cast)
                    .filter(n -> n.stepId().equals(WAIT_STEP))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /** The four ways a wait can end its chain: no timeout, relative, absolute, and state-derived with a mutator. */
    private static Map<String, Chain> chains() {
        Map<String, Chain> chains = new LinkedHashMap<>();
        chains.put("no timeout", next -> next.build().complete("ok"));
        chains.put("timeoutAfter", next -> next.timeoutAfter(Duration.ofHours(1))
                .toStepOnTimeout("late")
                .complete("ok")
                .complete("late"));
        chains.put("timeoutAt", next -> next.timeoutAt(DEADLINE)
                .toStepOnTimeout("late")
                .complete("ok")
                .complete("late"));
        chains.put("timeoutFromState with mutator", next -> next.timeoutFromState(s -> DEADLINE)
                .onTimeout(s -> new State("timed-out"))
                .toStepOnTimeout("late")
                .complete("ok")
                .complete("late"));
        return chains;
    }

    /** Builds one definition whose wait ends in {@code chain}; the wait declares a key only when {@code keyed}. */
    private static Built waitWith(boolean keyed, Chain chain) {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(Cmd.class, cmd -> new State(cmd.reference())).initialStep("start");
        wf.dispatch("start", "svc", s -> new Object(), WAIT_STEP);
        var declaration = wf.waitForSignal(WAIT_STEP, SIGNAL, Payload.class);
        if (keyed) {
            declaration = declaration.correlateBy(State::paymentReference);
        }
        WorkflowBuilder<State> done =
                chain.finish(declaration.onSignal((s, p) -> new State(p.data())).toStepOnSignal("ok"));
        return new Built(done, done.build("def", 1L));
    }

    /** The same node, rebuilt through the six-argument constructor, so it can only carry no key. */
    private static WaitSignalNode withoutKey(WaitSignalNode node) {
        return new WaitSignalNode(
                node.stepId(),
                node.signalName(),
                node.payloadTypeName(),
                node.stateUpdaterCallbackId(),
                node.nextStepId(),
                node.timeout());
    }

    private static WorkflowPlan replaceWait(WorkflowPlan plan, WaitSignalNode replacement) {
        List<WorkflowNode> nodes = plan.nodes().stream()
                .map(n -> n.stepId().equals(replacement.stepId()) ? (WorkflowNode) replacement : n)
                .toList();
        return new WorkflowPlan(
                plan.definitionId(),
                plan.definitionVersion(),
                plan.planHash(),
                plan.stateTypeName(),
                plan.initialStepId(),
                nodes,
                plan.subjectResolverCallbackId());
    }

    @Test
    @DisplayName("every wait chain records the declared key callback, and a wait without one has none")
    void everyWaitChainCarriesTheDeclaredKey() {
        chains().forEach((name, chain) -> {
            // Given: a wait on this chain that declares correlateBy(State::paymentReference), and the same wait without
            // it
            Built keyed = waitWith(true, chain);
            Built unkeyed = waitWith(false, chain);

            // When: each definition is built and its node and callbacks are read back
            WaitSignalNode node = keyed.waitNode();

            // Then: the node names the wait's correlationKey callback, which resolves to the state's reference
            assertThat(node.correlationKeyCallbackId())
                    .as("%s: key callback id", name)
                    .isEqualTo(new CallbackId(WAIT_STEP + ".correlationKey"));
            assertThat(keyed.builder()
                            .callbackRegistry()
                            .<State>correlationKeyResolver(node.correlationKeyCallbackId())
                            .apply(new State("PAY-7")))
                    .as("%s: resolved key", name)
                    .isEqualTo("PAY-7");

            // And: the signal, next step and timeout branch are as without the key
            assertThat(withoutKey(node)).as("%s: node without its key", name).isEqualTo(unkeyed.waitNode());
            assertThat(unkeyed.waitNode().correlationKeyCallbackId())
                    .as("%s: undeclared key", name)
                    .isNull();
        });
    }

    @Test
    @DisplayName("a declared key changes the plan hash deterministically, and a plan without one hashes as before")
    void keyChangesThePlanHashOnlyWhenDeclared() {
        chains().forEach((name, chain) -> {
            // Given: two definitions identical except that one declares a key, and the keyed one built twice
            Built keyed = waitWith(true, chain);
            Built keyedAgain = waitWith(true, chain);
            Built unkeyed = waitWith(false, chain);

            // When: their plan hashes are computed
            String keyedHash = keyed.plan().planHash();
            String unkeyedHash = unkeyed.plan().planHash();

            // Then: the keyed hash differs from the unkeyed one and is stable across two builds
            assertThat(keyedHash).as("%s: keyed vs unkeyed", name).isNotEqualTo(unkeyedHash);
            assertThat(keyedAgain.plan().planHash()).as("%s: keyed twice", name).isEqualTo(keyedHash);

            // And: the unkeyed hash equals the hash of the same plan with its wait built by the six-argument
            // constructor
            WorkflowPlan sixArgument = replaceWait(unkeyed.plan(), withoutKey(unkeyed.waitNode()));
            assertThat(WorkflowBuilder.computePlanHashForTesting(sixArgument))
                    .as("%s: unkeyed vs six-argument node", name)
                    .isEqualTo(unkeyedHash);
        });
    }

    @Test
    @DisplayName("the six-argument WaitSignalNode constructor leaves the key absent and keeps every other component")
    void sixArgumentConstructorLeavesTheKeyAbsent() {
        // Given: the six components a pre-existing call site passes
        CallbackId updater = new CallbackId("wait.updater");
        WaitSignalNode.TimeoutBranch timeout = new WaitSignalNode.TimeoutBranch(
                new TimerSpec.After(Duration.ofMinutes(5)), new CallbackId("wait.timeoutMutator"), "late");

        // When: the six-argument constructor is called
        WaitSignalNode node = new WaitSignalNode("wait", "payment.captured", "payload-type", updater, "ok", timeout);

        // Then: the key callback id is null and every other component is as passed
        assertThat(node.correlationKeyCallbackId()).isNull();
        assertThat(node.stepId()).isEqualTo("wait");
        assertThat(node.signalName()).isEqualTo("payment.captured");
        assertThat(node.payloadTypeName()).isEqualTo("payload-type");
        assertThat(node.stateUpdaterCallbackId()).isSameAs(updater);
        assertThat(node.nextStepId()).isEqualTo("ok");
        assertThat(node.timeout()).isSameAs(timeout);
    }
}
