// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.util.UUIDV7;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.function.Function;

/**
 * Package-private collaborator that keeps the open wait records of the signal waits that declare a computed
 * correlation key.
 *
 * <p>A record is written in the transaction of the segment that moves the instance into the wait, so a segment that
 * rolls back leaves none. A wait that declares no key writes nothing. No message, log line or failure of this class
 * repeats a key.
 */
@Singleton
final class SignalWaits {

    /** The fork step and branch of a wait on the main path, which the record's columns hold as empty strings. */
    private static final String MAIN_PATH = "";

    private final WorkflowSignalWaitRepository<SqlClient> waits;
    private final Clock clock;

    /**
     * Creates the collaborator.
     *
     * @param waits the repository the records are written to
     * @param clock the clock that stamps a record
     */
    @Inject
    SignalWaits(WorkflowSignalWaitRepository<SqlClient> waits, Clock clock) {
        this.waits = waits;
        this.clock = clock;
    }

    /**
     * Writes the open record of an instance that enters a signal wait on the main path, when the wait declares a key.
     *
     * <p>The key is computed from the state as it stands on entry to the wait. The record carries the instance's start
     * scope, the contract name of the plan the instance is pinned to and the instance's definition id, under a new
     * version 7 wait id. A record left at the same address is replaced.
     *
     * @param instance the instance entering the wait, with the state it enters with
     * @param node the wait
     * @param plan the plan the instance is pinned to
     * @param tx the segment's transaction
     * @return a {@link Future} that completes when the record is written, or at once when the wait declares no key
     */
    Future<Void> open(WorkflowInstance instance, WaitSignalNode node, RuntimeWorkflow plan, SqlClient tx) {
        if (node.correlationKeyCallbackId() == null) {
            return Future.succeededFuture();
        }
        String key;
        try {
            key = keyOf(instance, node, plan);
        } catch (WorkflowDefinitionException refused) {
            return Future.failedFuture(refused);
        }
        return waits.write(
                new SignalWaitRecord(
                        UUIDV7.randomUUID(),
                        instance.id(),
                        node.stepId(),
                        MAIN_PATH,
                        MAIN_PATH,
                        instance.scopeKey(),
                        plan.contractName(),
                        instance.definitionId(),
                        node.signalName(),
                        key,
                        clock.instant()),
                tx);
    }

    /** Computes the key of the wait through the node's callback; a failure names the step and never the key. */
    private static String keyOf(WorkflowInstance instance, WaitSignalNode node, RuntimeWorkflow plan) {
        String key;
        try {
            Object state = Json.decodeValue(Buffer.buffer(instance.stateJson()), plan.stateType());
            @SuppressWarnings("unchecked")
            Function<Object, String> resolver = (Function<Object, String>)
                    (Function<?, ?>) plan.callbacks().correlationKeyResolver(node.correlationKeyCallbackId());
            key = resolver.apply(state);
        } catch (RuntimeException failed) {
            throw new WorkflowDefinitionException("correlationKey resolver failed for step '" + node.stepId() + "': "
                    + failed.getClass().getName());
        }
        if (key == null) {
            throw new WorkflowDefinitionException(
                    "correlationKey resolver returned no key for step '" + node.stepId() + "'");
        }
        return key;
    }
}
