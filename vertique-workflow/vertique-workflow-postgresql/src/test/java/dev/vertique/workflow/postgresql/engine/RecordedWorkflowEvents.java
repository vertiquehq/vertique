// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A recorder of {@link IntentKind#WORKFLOW_EVENT} intents that keeps the events the engine reports, for the tests that
 * assert what an instance emitted.
 */
final class RecordedWorkflowEvents implements WorkflowSideEffectRecorder<SqlClient> {

    private final List<WorkflowEventIntent> events = new CopyOnWriteArrayList<>();

    @Override
    public IntentKind kind() {
        return IntentKind.WORKFLOW_EVENT;
    }

    @Override
    public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
        events.add((WorkflowEventIntent) intent.payload());
        return Future.succeededFuture(RecorderResult.empty());
    }

    /** Forgets the events recorded so far. */
    void clear() {
        events.clear();
    }

    /** The events of the instance of the given type, in the order they were reported. */
    List<WorkflowEventIntent> of(WorkflowInstanceId id, WorkflowEventType type) {
        return events.stream()
                .filter(event -> event.workflowId().equals(id) && event.eventType() == type)
                .toList();
    }
}
