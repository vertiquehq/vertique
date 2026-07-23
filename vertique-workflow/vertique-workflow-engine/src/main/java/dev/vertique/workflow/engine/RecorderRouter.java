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
 *
 * <p>The constructor also accepts a {@link WorkflowReminderComposeValidator} as a required
 * dependency whose constructor performs a startup check (reminders-require-events). This follows
 * the forced-construction pattern used by other compose validators: Dagger builds the validator
 * whenever {@code RecorderRouter} is first needed (i.e., when {@code WorkflowEngine} or any
 * consumer that references it is instantiated). The validator is otherwise unused in this class.
 */
@Singleton
final class RecorderRouter {

    private final Map<IntentKind, WorkflowSideEffectRecorder<SqlClient>> byKind;
    private final Set<IntentKind> optionalKinds;

    /**
     * Constructs a {@code RecorderRouter} from the given set of recorders and optional kinds.
     *
     * <p>The {@code complianceCheck} parameter is unused after construction. It exists purely so
     * Dagger constructs {@link WorkflowReminderComposeValidator} (which performs startup checks at
     * construction time) before {@code RecorderRouter} is created. This mirrors the pattern used by
     * {@link dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder} and
     * {@link dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder}.
     *
     * @param recorders      the set of recorders contributed via the {@code @WorkflowRecorders}
     *                       Dagger multibinding; must not contain two recorders for the same
     *                       {@link IntentKind}
     * @param optionalKinds  the set of intent kinds that are treated as optional — if no recorder
     *                       is registered for an optional kind, routing succeeds silently with an
     *                       empty result
     * @param complianceCheck the reminder-compose validator; injected for its construction
     *                        side-effect only (the constructor runs the reminders-require-events
     *                        check)
     * @throws IllegalStateException if two recorders declare the same {@link IntentKind}
     */
    @Inject
    RecorderRouter(
            @WorkflowRecorders Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            @OptionalIntentKinds Set<IntentKind> optionalKinds,
            WorkflowReminderComposeValidator complianceCheck) {
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
        // complianceCheck is intentionally unused after construction; injection here forces the
        // validator's checks to run during Dagger graph instantiation, not opt-in at app boot.
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
