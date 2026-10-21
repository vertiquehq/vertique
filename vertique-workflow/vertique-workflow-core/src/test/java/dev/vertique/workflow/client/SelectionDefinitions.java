// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;

/**
 * Definition classes for the selection contract fixtures, so a test can create their clients from a
 * definition class.
 *
 * <p>Both definitions share one plan shape: {@link SelectionStartPayload} starts it and
 * {@link SelectionSignalPayload} drives the {@code "order.confirmed"} and {@code "order.shipped"}
 * signal nodes, matching the method shapes of {@link SelectionWorkflow} and
 * {@link FallbackSelectionWorkflow}.
 */
final class SelectionDefinitions {

    private SelectionDefinitions() {}

    /** State record shared by both definitions. */
    record State(String id) {}

    /**
     * Shared plan and identity for the selection definitions.
     *
     * @param <C> the contract type the definition belongs to
     */
    abstract static class Base<C> implements WorkflowDefinition<State, C> {

        private final Class<C> contract;
        private final String definitionId;

        Base(Class<C> contract, String definitionId) {
            this.contract = contract;
            this.definitionId = definitionId;
        }

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
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(SelectionStartPayload.class, p -> new State(p.orderId()))
                    .initialStep("charge")
                    .dispatch("charge", "payment-svc", s -> new Object(), "wait-confirm")
                    .waitFor(
                            "wait-confirm",
                            "order.confirmed",
                            SelectionSignalPayload.class,
                            (s, p) -> s,
                            "wait-shipped")
                    .waitFor("wait-shipped", "order.shipped", SelectionSignalPayload.class, (s, p) -> s, "done")
                    .complete("done");
        }
    }

    /** Definition of {@link SelectionWorkflow}, whose generated proxy stand-in is on the test classpath. */
    static final class Selection extends Base<SelectionWorkflow> {
        Selection() {
            super(SelectionWorkflow.class, "selection-saga");
        }
    }

    /** Definition of {@link FallbackSelectionWorkflow}, which has no generated proxy. */
    static final class Fallback extends Base<FallbackSelectionWorkflow> {
        Fallback() {
            super(FallbackSelectionWorkflow.class, "selection-saga-fallback");
        }
    }
}
