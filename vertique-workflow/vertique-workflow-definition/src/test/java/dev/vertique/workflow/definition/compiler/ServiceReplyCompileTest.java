// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.*;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.*;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ServiceReplyCompileTest {
    record State(String id) {}

    record Start(String id) {}

    record Reply(String id) {}

    @WorkflowContract
    interface Contract {}

    @Test
    void documentAndCodeGiveTheSamePlan() {
        var lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(
                        Set.of(b -> b.register(new NamedPayloadMapper<>("payload", State.class, s -> s.id())))),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(
                        new NamedStartStateMapper<>("init", Start.class, State.class, s -> new State(s.id()))))),
                new DefaultStateReducerRegistry(
                        Set.of(b -> b.register(new NamedStateReducer<>("reducer", State.class, (s, r) -> s)))),
                new DefaultStateMutatorRegistry(
                        Set.of(b -> b.register(new NamedStateMutator<>("mutator", State.class, s -> s)))),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));
        var mapper = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapper.jsonMapper()).thenReturn(new ObjectMapper());
        var profile = new CelExpressionProfile();
        var compiler = new WorkflowDefinitionCompiler(
                lookup,
                profile,
                new DecisionRouteCompiler(profile, new DefaultNamedConditionRegistry(Set.of()), mapper));
        for (boolean later : new boolean[] {false, true}) {
            for (boolean failureReducer : new boolean[] {false, true}) {
                String yaml = """
                definitionId: wf
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: init
                initialStep: svc
                steps:
                  - id: svc
                    type: service
                    target: inventory.reserve
                    payloadMapper: payload
                    compensation: undo
                    reply:
                      payloadType: %s
                      stateReducer: reducer
                      next: success
                      onFailure:
                %s        next: failure
                %s  - id: success
                    type: complete
                  - id: failure
                    type: complete
                  - id: timed-out
                    type: complete
                  - id: undo
                    type: compensation
                    forwardStep: svc
                    target: inventory.release
                    payloadMapper: payload
                """.formatted(
                                State.class.getName(),
                                Contract.class.getName(),
                                Start.class.getName(),
                                Reply.class.getName(),
                                failureReducer ? "        stateReducer: reducer\n" : "",
                                (later ? "      completesLater: true\n" : "")
                                        + "      timeout:\n        after: PT10M\n        onTimeoutMutator: mutator\n        next: timed-out\n");
                var doc = ProductionParserFixture.parser()
                        .parse(yaml.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML);
                var fromDocument = new WorkflowBuilder<State>();
                compiler.emit(fromDocument, State.class, Contract.class, doc);
                var code = new WorkflowBuilder<State>();
                code.init(Start.class, s -> new State(s.id())).initialStep("svc");
                var call = code.call("svc", "inventory.reserve", s -> s.id());
                call.compensateWith("undo");
                if (later) call.completesLater();
                var replyStage = call.onReply(Reply.class, (s, r) -> s).toStepOnReply("success");
                var failureStage = failureReducer ? replyStage.onFailure((s, f) -> s) : replyStage.onFailure();
                failureStage
                        .toStepOnFailure("failure")
                        .timeoutAfter(Duration.ofMinutes(10))
                        .onTimeout(s -> s)
                        .toStepOnTimeout("timed-out");
                code.complete("success")
                        .complete("failure")
                        .complete("timed-out")
                        .compensate("undo", "svc", "inventory.release", s -> s.id());
                var a = fromDocument.build("wf", 1L, State.class.getName());
                var b = code.build("wf", 1L, State.class.getName());
                assertThat(a.planHash()).isEqualTo(b.planHash());
                assertThat(a.nodes()).isEqualTo(b.nodes());
            }
        }
    }
}
