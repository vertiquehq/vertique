// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * The one workload definition: start, one service dispatch, wait for a signal, one timer, complete.
 *
 * <p>Plan: {@code work (dispatch) → wait-callback (signal) → pause (timer) → done}.
 *
 * <p>An instance therefore records three commands, in order: its start, the callback signal and the timer firing.
 * The start segment records the dispatch and reaches the wait in one transaction, and the dispatch is the only
 * outbox row the instance creates.
 */
@Singleton
public final class LoadDefinition implements WorkflowDefinition<LoadState, LoadWorkflow> {

    /** The id the definition is registered under. */
    public static final String DEFINITION_ID = "load";

    /** The stable target id of the dispatch: the load service and its single operation. */
    public static final String DISPATCH_TARGET = "load.work";

    private final long timerDelayMs;

    /**
     * Creates the definition.
     *
     * @param config the harness settings; the timer step lasts {@link LoadConfig#timerDelayMs()}
     */
    @Inject
    public LoadDefinition(LoadConfig config) {
        this.timerDelayMs = config.timerDelayMs();
    }

    @Override
    public Class<LoadWorkflow> contract() {
        return LoadWorkflow.class;
    }

    @Override
    public Class<LoadState> stateType() {
        return LoadState.class;
    }

    @Override
    public String definitionId() {
        return DEFINITION_ID;
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<LoadState> wf) {
        wf.init(LoadStart.class, cmd -> new LoadState(cmd.key()))
                .initialStep("work")
                .dispatch("work", DISPATCH_TARGET, state -> new LoadWork(state.key()), "wait-callback")
                .waitForSignal("wait-callback", LoadCallback.SIGNAL_NAME, LoadCallback.class)
                .onSignal((state, signal) -> state)
                .toStepOnSignal("pause")
                .build()
                .timer("pause", Duration.ofMillis(timerDelayMs))
                .toStep("done")
                .complete("done");
    }
}
