// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Workflow definitions built through the DSL, and plans built by hand for the shapes the DSL cannot express (an
 * unresolvable payload type name).
 */
final class Definitions {

    /** The contract every DSL definition in this suite is registered under; one definition per registry. */
    interface Primary {}

    /** A second contract, for tests that register two definitions in one registry. */
    interface Secondary {}

    record State(String id) {}

    private Definitions() {}

    /**
     * A definition with one human task {@code review} and one decision {@code note} of {@code decisionType}.
     *
     * @param decisionType the decision payload type
     * @return the definition, id {@code first}, version 1
     */
    static WorkflowDefinition<State, Primary> withDecision(Class<?> decisionType) {
        return withDecision("first", 1L, decisionType);
    }

    /**
     * A definition without a start payload, with one human task {@code review} and one decision {@code note} of
     * {@code decisionType}.
     *
     * @param id the definition id
     * @param version the definition version
     * @param decisionType the decision payload type
     * @return the definition
     */
    static WorkflowDefinition<State, Primary> withDecision(String id, long version, Class<?> decisionType) {
        return build(Primary.class, id, version, Void.class, Map.of("note", decisionType));
    }

    /**
     * A definition with one human task {@code review} that declares the given decisions in iteration order.
     *
     * @param id the definition id
     * @param version the definition version
     * @param decisions decision name to payload type
     * @return the definition
     */
    static WorkflowDefinition<State, Primary> withDecisions(String id, long version, Map<String, Class<?>> decisions) {
        return build(Primary.class, id, version, Void.class, decisions);
    }

    /**
     * A definition without human tasks whose start payload type is {@code startType}.
     *
     * @param id the definition id
     * @param version the definition version
     * @param startType the start payload type
     * @return the definition
     */
    static WorkflowDefinition<State, Primary> withStart(String id, long version, Class<?> startType) {
        return build(Primary.class, id, version, startType, Map.of());
    }

    /**
     * A definition with the given start payload type and one human task {@code review} with decision {@code note}.
     *
     * @param id the definition id
     * @param version the definition version
     * @param startType the start payload type
     * @param decisionType the decision payload type
     * @return the definition
     */
    static WorkflowDefinition<State, Primary> withStartAndDecision(
            String id, long version, Class<?> startType, Class<?> decisionType) {
        return build(Primary.class, id, version, startType, Map.of("note", decisionType));
    }

    /**
     * A definition with the given start payload type and one human task {@code review} with the given decisions.
     *
     * @param id the definition id
     * @param version the definition version
     * @param startType the start payload type
     * @param decisions decision name to payload type, in declaration order
     * @return the definition
     */
    static WorkflowDefinition<State, Primary> withStartAndDecisions(
            String id, long version, Class<?> startType, Map<String, Class<?>> decisions) {
        return build(Primary.class, id, version, startType, decisions);
    }

    /**
     * Same as {@link #withDecision(String, long, Class)} under the {@link Secondary} contract, so it can share a
     * registry with a {@link Primary} definition.
     *
     * @param id the definition id
     * @param version the definition version
     * @param decisionType the decision payload type
     * @return the definition
     */
    static WorkflowDefinition<State, Secondary> withDecisionUnderSecondContract(
            String id, long version, Class<?> decisionType) {
        return build(Secondary.class, id, version, Void.class, Map.of("note", decisionType));
    }

    /**
     * Builds the definition's plan the way the registry does, without registering it.
     *
     * @param definition the definition
     * @return the candidate a registered validator would receive
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static RegisteredWorkflowDefinition candidate(WorkflowDefinition<State, ?> definition) {
        WorkflowBuilder builder = new WorkflowBuilder<State>();
        definition.define(builder);
        WorkflowPlan plan = builder.build(
                definition.definitionId(),
                definition.definitionVersion(),
                definition.stateType().getName());
        return new RegisteredWorkflowDefinition(plan, builder.startPayloadType());
    }

    /**
     * A candidate built by hand from tasks, followed by a terminal {@code done} step.
     *
     * @param id the definition id
     * @param version the definition version
     * @param startType the start payload type
     * @param tasks the human task nodes in plan order
     * @return the candidate
     */
    static RegisteredWorkflowDefinition handBuilt(String id, long version, Class<?> startType, HumanTaskNode... tasks) {
        List<WorkflowNode> nodes = new ArrayList<>(List.of(tasks));
        nodes.add(new CompleteNode("done"));
        String initial = tasks.length == 0 ? "done" : tasks[0].stepId();
        WorkflowPlan plan = new WorkflowPlan(id, version, "hash", State.class.getName(), initial, nodes, null);
        return new RegisteredWorkflowDefinition(plan, startType);
    }

    /**
     * A human task node whose decisions name payload types by string, as a persisted plan does.
     *
     * @param stepId the step id
     * @param decisions pairs of decision name and payload type name
     * @return the node
     */
    static HumanTaskNode task(String stepId, String... decisions) {
        List<HumanTaskNode.TaskDecision> list = new ArrayList<>();
        for (int index = 0; index < decisions.length; index += 2) {
            list.add(new HumanTaskNode.TaskDecision(
                    decisions[index], decisions[index + 1], new CallbackId(stepId + "." + decisions[index]), "done"));
        }
        return new HumanTaskNode(
                stepId, new HumanTaskNode.AssignmentSpec.Role("reviewers"), list, null, null, null, null, false);
    }

    private static <C> WorkflowDefinition<State, C> build(
            Class<C> contract, String id, long version, Class<?> startType, Map<String, Class<?>> decisions) {
        return definition(contract, id, version, wf -> {
            if (decisions.isEmpty()) {
                initWith(wf, startType, "done");
                wf.complete("done");
                return;
            }
            initWith(wf, startType, "review");
            WorkflowBuilder<State>.TaskBuilder task = wf.task("review").assignToRole("reviewers");
            for (Map.Entry<String, Class<?>> decision : decisions.entrySet()) {
                task = addDecision(task, decision.getKey(), decision.getValue());
            }
            task.build().complete("done");
        });
    }

    private static <P> WorkflowBuilder<State>.TaskBuilder addDecision(
            WorkflowBuilder<State>.TaskBuilder task, String name, Class<P> type) {
        return task.decision(name, type).onDecision((state, payload) -> state).toStep("done");
    }

    private static <P> void initWith(WorkflowBuilder<State> wf, Class<P> startType, String initialStep) {
        wf.init(startType, start -> new State("start")).initialStep(initialStep);
    }

    private static <C> WorkflowDefinition<State, C> definition(
            Class<C> contract, String id, long version, Consumer<WorkflowBuilder<State>> body) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return id;
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                body.accept(wf);
            }
        };
    }
}
