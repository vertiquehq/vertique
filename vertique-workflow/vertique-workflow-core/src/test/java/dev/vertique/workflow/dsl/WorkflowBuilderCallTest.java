// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowBuilderCallTest {
    record Flow(String value) {}

    record Reply(String value) {}

    @WorkflowContract
    interface Contract {}

    private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

    private static WorkflowBuilder<Flow> builder() {
        var wf = new WorkflowBuilder<Flow>();
        wf.init(String.class, Flow::new).initialStep("reserve");
        return wf;
    }

    private static WorkflowBuilder<Flow>.SignalWaitWithNextStep call(WorkflowBuilder<Flow> wf, boolean later) {
        var call = wf.call("reserve", "inventory.reserve", s -> s.value());
        if (later) call.completesLater();
        return call.onReply(Reply.class, (s, r) -> new Flow(r.value()))
                .toStepOnReply("ok")
                .onFailure()
                .toStepOnFailure("failed");
    }

    @Test
    void callCompilesToADispatchFollowedByAReplyWait() {
        var wf = builder();
        wf.call("reserve", "inventory.reserve", s -> s.value())
                .compensateWith("undo")
                .onReply(Reply.class, (s, r) -> new Flow(r.value()))
                .toStepOnReply("ok")
                .onFailure((s, f) -> new Flow(f.reason().name()))
                .toStepOnFailure("failed")
                .build()
                .complete("ok")
                .complete("failed");
        var plan = wf.build("test", 1);
        var dispatch = (ServiceDispatchNode) plan.nodes().get(0);
        var wait = (WaitSignalNode) plan.nodes().get(1);
        assertThat(dispatch.stepId()).isEqualTo("reserve");
        assertThat(dispatch.nextStepId()).isEqualTo("reserve.reply");
        assertThat(dispatch.compensationStepId()).isEqualTo("undo");
        assertThat(wait.stepId()).isEqualTo("reserve.reply");
        assertThat(wait.signalName()).isEqualTo("vertique.reply.ok.reserve");
        assertThat(wait.reply().callStepId()).isEqualTo("reserve");
        assertThat(wait.reply().failureSignalName()).isEqualTo("vertique.reply.failed.reserve");
        assertThat(wait.reply().failureUpdaterCallbackId()).isNotNull();
        assertThat(wait.reply().failureNextStepId()).isEqualTo("failed");
        assertThat(wf.signalPayloadTypes()).containsEntry("vertique.reply.ok.reserve", Reply.class);
    }

    static Stream<Function<WorkflowBuilder<Flow>.SignalWaitWithNextStep, WorkflowBuilder<Flow>>> timeoutChains() {
        return Stream.of(
                n -> n.timeoutAfter(Duration.ofMinutes(1)).toStepOnTimeout("late"),
                n -> n.timeoutAfter(Duration.ofMinutes(1)).onTimeout(s -> s).toStepOnTimeout("late"),
                n -> n.timeoutAt(DEADLINE).toStepOnTimeout("late"),
                n -> n.timeoutAt(DEADLINE).onTimeout(s -> s).toStepOnTimeout("late"),
                n -> n.timeoutFromState(s -> DEADLINE).toStepOnTimeout("late"),
                n -> n.timeoutFromState(s -> DEADLINE).onTimeout(s -> s).toStepOnTimeout("late"));
    }

    @ParameterizedTest
    @MethodSource("timeoutChains")
    void timeoutStagesEmitTheReplyWait(
            Function<WorkflowBuilder<Flow>.SignalWaitWithNextStep, WorkflowBuilder<Flow>> finish) {
        var wf = builder();
        finish.apply(call(wf, true)).complete("ok").complete("failed").complete("late");
        var wait = (WaitSignalNode) wf.build("test", 1).nodes().get(1);
        assertThat(wait.timeout()).isNotNull();
        assertThat(wait.reply()).isNotNull();
        assertThat(wait.reply().completesLater()).isTrue();
        assertThat(wait.reply().failureUpdaterCallbackId()).isNull();
    }

    @Test
    void declaredStepNamedLikeAReplyWaitIsRefused() {
        var first = builder();
        first.complete("reserve.reply");
        assertThatThrownBy(() -> call(first, false))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("reserve.reply");
        var second = builder();
        call(second, false).build();
        assertThatThrownBy(() -> second.complete("reserve.reply"))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("reserve.reply");
    }

    @Test
    void callWithoutAFailureRouteIsRefusedNamingTheStep() {
        var wf = builder();
        wf.call("reserve", "inventory.reserve", s -> s.value())
                .onReply(Reply.class, (s, r) -> s)
                .toStepOnReply("ok");
        wf.complete("ok");
        assertThatThrownBy(() -> wf.build("test", 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("reserve")
                .hasMessageContaining("failure");
    }

    @Test
    void completesLaterWithoutATimeoutIsRefusedNamingTheStep() {
        WorkflowDefinition<Flow, Contract> def = new WorkflowDefinition<>() {
            public Class<Contract> contract() {
                return Contract.class;
            }

            public Class<Flow> stateType() {
                return Flow.class;
            }

            public String definitionId() {
                return "test";
            }

            public long definitionVersion() {
                return 1;
            }

            public void define(WorkflowBuilder<Flow> wf) {
                wf.init(String.class, Flow::new).initialStep("reserve");
                call(wf, true).build().complete("ok").complete("failed");
            }
        };
        var validator = new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        assertThatThrownBy(() -> new DefaultWorkflowRegistry(validator).register(def))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("reserve");
    }
}
