// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

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
 */
@Singleton
final class RecorderRouter {

    private final Map<IntentKind, WorkflowSideEffectRecorder<SqlClient>> byKind;
    private final Set<IntentKind> optionalKinds;

    /** The command each segment transaction executes, while it executes it; keyed by the transaction handle. */
    private final Map<SqlClient, SegmentCommand> executing = Collections.synchronizedMap(new WeakHashMap<>());

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
        return route(intent, executing.get(tx), tx);
    }

    /**
     * Runs the work of a segment, during which every intent routed on the segment's transaction is attributed to the
     * command the segment executes. Segments overlap on one context, so the command is keyed by the transaction handle
     * and not by the context. A nested call, such as the application of a parked signal inside another command's
     * segment, attributes its intents to its own command and restores the outer command when its work ends.
     *
     * @param command the command the segment executes
     * @param tx      the segment's transaction
     * @param work    the segment's work
     * @param <T>     the type the work yields
     * @return the future of the work
     */
    <T> Future<T> executing(SegmentCommand command, SqlClient tx, Supplier<Future<T>> work) {
        SegmentCommand outer = executing.put(tx, command);
        Future<T> result;
        try {
            result = work.get();
        } catch (RuntimeException e) {
            restore(tx, outer);
            throw e;
        }
        return result.eventually(() -> {
            restore(tx, outer);
            return Future.succeededFuture();
        });
    }

    /** Puts back the command that was executing on the transaction before a nested one, or none. */
    private void restore(SqlClient tx, SegmentCommand outer) {
        if (outer == null) {
            executing.remove(tx);
        } else {
            executing.put(tx, outer);
        }
    }

    /**
     * Routes an intent a segment writes for the command it executes: every application-set header whose name starts
     * with {@code x-workflow-}, in any letter case, is removed, and the command's actor is added as attribution
     * headers. A {@link IntentKind#SERVICE} dispatch, which stays inside the deployment's own services, gets the full
     * set ({@code x-workflow-actor-kind}, {@code -actor-id}, {@code -actor-reason}, {@code -on-behalf-of} and
     * {@code -attribution}; absent values are omitted). Any other intent, such as a workflow event that reaches brokers
     * and webhooks, gets only the actor kind and the attribution status, so no subject identifier leaves the
     * deployment. The headers are data for audit and logs only: they are unauthenticated, and nothing reads them as an
     * identity. Without a command, the application-set workflow headers are removed and none are added.
     *
     * @param intent  the side-effect intent to record
     * @param command the command the segment executes, or {@code null}
     * @param tx      the active SQL transaction; passed through to the recorder
     * @return the result of the recorder
     */
    Future<RecorderResult> route(WorkflowSideEffectIntent intent, @Nullable SegmentCommand command, SqlClient tx) {
        WorkflowSideEffectIntent attributed = attributed(intent, command);
        WorkflowSideEffectRecorder<SqlClient> recorder = byKind.get(attributed.kind());
        if (recorder != null) {
            return recorder.record(attributed, tx);
        }
        if (optionalKinds.contains(intent.kind())) {
            return Future.succeededFuture(RecorderResult.empty());
        }
        return Future.failedFuture(new IllegalStateException("No WorkflowSideEffectRecorder registered for kind "
                + intent.kind()
                + "; check application Dagger composition."));
    }

    /** The prefix of the header names the framework owns. */
    private static final String PREFIX = "x-workflow-";

    private static WorkflowSideEffectIntent attributed(
            WorkflowSideEffectIntent intent, @Nullable SegmentCommand command) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : intent.headers().entrySet()) {
            if (!header.getKey().toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
                headers.put(header.getKey(), header.getValue());
            }
        }
        if (command != null) {
            CommandActor actor = command.actor() == null ? CommandActor.none() : command.actor();
            headers.put(PREFIX + "actor-kind", actor.kind().name());
            if (intent.kind() == IntentKind.SERVICE) {
                putIfPresent(headers, PREFIX + "actor-id", actor.id());
                putIfPresent(headers, PREFIX + "actor-reason", actor.reason());
                putIfPresent(headers, PREFIX + "on-behalf-of", actor.onBehalfOf());
            }
            headers.put(PREFIX + "attribution", actor.attribution().name());
        }
        return headers.equals(intent.headers())
                ? intent
                : new WorkflowSideEffectIntent(
                        intent.kind(), intent.targetId(), intent.payload(), headers, intent.correlation());
    }

    private static void putIfPresent(Map<String, String> headers, String name, @Nullable String value) {
        if (value != null) {
            headers.put(name, value);
        }
    }
}
