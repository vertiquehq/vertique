// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes a {@link WorkflowSideEffectIntent} to exactly one registered {@link WorkflowSideEffectRecorder}
 * by intent kind. Built from the {@code @WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>>}
 * Dagger multibinding.
 *
 * <p>Construction throws {@link IllegalStateException} when two recorders claim the same
 * {@link IntentKind}. {@link #route(WorkflowSideEffectIntent, SqlClient)} behaves as follows:
 * <ul>
 *   <li>If a recorder is registered for the intent's kind → delegates to the recorder.</li>
 *   <li>If no recorder is registered but the kind is in the {@code optionalKinds} set → returns
 *       a succeeded future with {@link RecorderResult#empty()} (silent no-op).</li>
 *   <li>If no recorder is registered and the kind is not optional → fails the returned future
 *       with an {@link IllegalStateException} (rolls back the workflow transaction).</li>
 * </ul>
 * */
@Singleton
final class RecorderRouter {

    private final Map<IntentKind, WorkflowSideEffectRecorder<SqlClient>> byKind;
    private final Set<IntentKind> optionalKinds;

    /**
     * Constructs a {@code RecorderRouter} from the given set of recorders and optional kinds.
     *
     * @param recorders      the set of recorders contributed via the {@code @WorkflowRecorders}
     *                       Dagger multibinding; must not contain two recorders for the same
     *                       {@link IntentKind}
     * @param optionalKinds  the set of intent kinds that are treated as optional — if no recorder
     *                       is registered for an optional kind, routing succeeds silently with an
     *                       empty result
     * @throws IllegalStateException if two recorders declare the same {@link IntentKind}
     */
    @Inject
    RecorderRouter(
            @WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            @OptionalIntentKinds Set<IntentKind> optionalKinds) {
        Map<IntentKind, WorkflowSideEffectRecorder<SqlClient>> map = new EnumMap<>(IntentKind.class);
        for (WorkflowSideEffectRecorder<SqlClient> recorder : recorders) {
            WorkflowSideEffectRecorder<SqlClient> prior = map.put(recorder.kind(), recorder);
            if (prior != null) {
                throw new IllegalStateException("Duplicate WorkflowSideEffectRecorder for kind "
                        + recorder.kind()
                        + ": "
                        + prior
                        + " and "
                        + recorder);
            }
        }
        this.byKind = Map.copyOf(map);
        this.optionalKinds = Set.copyOf(optionalKinds);
    }

    /**
     * Routes {@code intent} to the recorder registered for its {@link IntentKind}.
     *
     * <p>If no recorder is registered for the intent's kind but the kind is declared optional
     * (via the constructor's {@code optionalKinds} set), returns a succeeded future with
     * {@link RecorderResult#empty()} instead of failing.
     *
     * <p>Returns a failed {@link Future} when no recorder is registered for the intent's kind and
     * the kind is not optional; the caller is expected to roll back the enclosing transaction.
     *
     * @param intent the side-effect intent to record
     * @param tx the active SQL transaction; passed through to the recorder
     * @return a {@link Future} that completes with the {@link RecorderResult} when the intent has
     *     been durably recorded, or a failed future if no recorder handles the intent's kind
     */
    Future<RecorderResult> route(WorkflowSideEffectIntent intent, SqlClient tx) {
        WorkflowSideEffectRecorder<SqlClient> recorder = byKind.get(intent.kind());
        if (recorder != null) {
            return recorder.record(intent, tx);
        }
        if (optionalKinds.contains(intent.kind())) {
            return Future.succeededFuture(RecorderResult.empty());
        }
        return Future.failedFuture(new IllegalStateException("No WorkflowSideEffectRecorder registered for kind "
                + intent.kind()
                + "; check application Dagger composition."));
    }
}
