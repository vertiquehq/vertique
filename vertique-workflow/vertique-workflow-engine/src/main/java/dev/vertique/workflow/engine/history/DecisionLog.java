// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.workflow.engine.spi.DecisionEntry;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.history.WorkflowHistoryRedactor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * INTERNAL: writes the decision log of an instance: one {@link WorkflowEntryType#DECISION} entry for each engine
 * decision, with an RFC 6902 patch of the state change, and {@link WorkflowEntryType#STATE_BASELINE} entries that hold
 * the full state.
 *
 * <p>Every entry is written in the transaction of the segment, which holds the instance's execution lock, so a
 * rolled-back savepoint takes its entries with it. Applying the patches after the latest full-state entry, in order,
 * reproduces the state the instance stores, except at the pointers a bound {@link WorkflowHistoryRedactor} names.
 *
 * <ul>
 *   <li><b>Size.</b> The canonical JSON of the redacted states before and after a decision is measured first. When
 *       either is over {@value #SIZE_BOUND_BYTES} bytes the diff is not asked for: the decision entry has no patch and
 *       a baseline entry with the full state follows it. A patch that would be over the bound is replaced the same
 *       way. A decision that left the state unchanged logs an empty patch whatever the size.</li>
 *   <li><b>Redaction.</b> The values at the redactor's pointers for the instance's definition key are replaced by
 *       {@value HistoryRedaction#REDACTED} in the states a patch is diffed from, in every full state, and in the copy
 *       of a command's payload. The redactor is asked once per definition key in a segment. A redactor that throws, or
 *       a pointer that is not valid, fails the segment as a transient failure; nothing is logged unredacted.</li>
 *   <li><b>Command.</b> Inside {@link #segment} the entries belong to the command the segment executes, and inside
 *       {@link #forCommand} to the command given; outside both they belong to none.</li>
 * </ul>
 */
@Singleton
public final class DecisionLog {

    /** The most bytes of canonical JSON a state or a stored patch may have. */
    public static final int SIZE_BOUND_BYTES = 64 * 1024;

    /** The longest outcome a decision entry stores. */
    public static final int MAX_OUTCOME_LENGTH = 40;

    private static final String BASELINE_OUTCOME = "baseline";

    /**
     * One engine decision.
     *
     * @param instanceId    the instance
     * @param commandId     the command the decision belongs to, or {@code null} when it belongs to none
     * @param stepId        the step the decision was taken at
     * @param outcome       what the decision came to, at most {@value #MAX_OUTCOME_LENGTH} characters
     * @param key           the definition version the instance is pinned to
     * @param planHash      the plan hash of the instance
     * @param versionBefore the state version the decision started from
     * @param versionAfter  the state version it led to; equal to {@code versionBefore} when the state did not change
     * @param executorId    the node id of the executor, or {@code null} to take it from the command claim
     */
    public record Decision(
            WorkflowInstanceId instanceId,
            @Nullable Long commandId,
            String stepId,
            String outcome,
            DefinitionKey key,
            String planHash,
            long versionBefore,
            long versionAfter,
            @Nullable String executorId) {}

    /** The command a transaction is logging for, and the pointers asked of the redactor in the segment. */
    private static final class Scope {
        final SegmentCommand command;
        final DefinitionKey key;
        final Map<DefinitionKey, Set<String>> pointers;

        Scope(SegmentCommand command, DefinitionKey key, Map<DefinitionKey, Set<String>> pointers) {
            this.command = command;
            this.key = key;
            this.pointers = pointers;
        }
    }

    private final JsonPatchDiff diff;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final Optional<WorkflowHistoryRedactor> redactor;
    private final Clock clock;

    /** The scopes of the transactions that are logging for a command, innermost last, keyed by handle identity. */
    private final Map<SqlClient, Deque<Scope>> scopes = Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Creates the log.
     *
     * @param diff     the diff that computes patches
     * @param history  the history repository the entries are appended to
     * @param redactor the history redactor, when the application binds one
     * @param clock    the clock that timestamps entries
     */
    @Inject
    public DecisionLog(
            JsonPatchDiff diff,
            WorkflowHistoryRepository<SqlClient> history,
            Optional<WorkflowHistoryRedactor> redactor,
            Clock clock) {
        this.diff = Objects.requireNonNull(diff, "diff");
        this.history = Objects.requireNonNull(history, "history");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // --- Scope of a segment ---

    /**
     * Runs the work of a segment: the entries it logs belong to the command, and the redactor is asked once for each
     * definition key it logs under.
     *
     * @param tx      the segment's transaction handle
     * @param command the command the segment executes
     * @param key     the definition version the command is pinned to
     * @param work    the segment's work
     * @param <T>     the result type of the work
     * @return the future of the work
     */
    public <T> Future<T> segment(SqlClient tx, SegmentCommand command, DefinitionKey key, Supplier<Future<T>> work) {
        return scoped(tx, new Scope(command, key, new HashMap<>()), work);
    }

    /**
     * Runs work whose entries belong to another command than the segment's, such as a parked signal that the segment
     * applies. The redactor's answers of the segment are kept.
     *
     * @param tx      the segment's transaction handle
     * @param command the command the work is for
     * @param work    the work
     * @param <T>     the result type of the work
     * @return the future of the work
     */
    public <T> Future<T> forCommand(SqlClient tx, SegmentCommand command, Supplier<Future<T>> work) {
        Deque<Scope> stack = scopes.get(tx);
        Scope outer = stack == null ? null : stack.peekLast();
        if (outer == null) {
            return work.get();
        }
        return scoped(tx, new Scope(command, outer.key, outer.pointers), work);
    }

    private <T> Future<T> scoped(SqlClient tx, Scope scope, Supplier<Future<T>> work) {
        scopes.computeIfAbsent(tx, ignored -> new ArrayDeque<>()).addLast(scope);
        Future<T> running;
        try {
            running = work.get();
        } catch (RuntimeException e) {
            leave(tx);
            throw e;
        }
        return running.eventually(() -> {
            leave(tx);
            return Future.succeededFuture();
        });
    }

    private void leave(SqlClient tx) {
        scopes.computeIfPresent(tx, (handle, stack) -> {
            stack.pollLast();
            return stack.isEmpty() ? null : stack;
        });
    }

    private @Nullable Scope scopeOf(SqlClient tx) {
        Deque<Scope> stack = scopes.get(tx);
        return stack == null ? null : stack.peekLast();
    }

    /**
     * The command the transaction is logging for.
     *
     * @param tx the transaction handle
     * @return the command, or {@code null} outside a segment
     */
    public @Nullable SegmentCommand commandOf(SqlClient tx) {
        Scope scope = scopeOf(tx);
        return scope == null ? null : scope.command;
    }

    // --- Pointers and redaction ---

    /**
     * The pointers the redactor names for a definition version, asked once per version in a segment.
     *
     * @param key the definition version
     * @param tx  the transaction handle
     * @return the pointers; empty when none is bound or the redactor names none
     * @throws WorkflowPersistenceException (retryable) if the redactor throws
     */
    public Set<String> pointers(DefinitionKey key, SqlClient tx) {
        if (redactor.isEmpty()) {
            return Set.of();
        }
        Scope scope = scopeOf(tx);
        Map<DefinitionKey, Set<String>> cache = scope == null ? null : scope.pointers;
        Set<String> known = cache == null ? null : cache.get(key);
        if (known != null) {
            return known;
        }
        Set<String> asked;
        try {
            Set<String> named = redactor.get().redactedPointers(key);
            asked = named == null ? Set.of() : Set.copyOf(named);
        } catch (RuntimeException e) {
            throw new WorkflowPersistenceException("The history redactor failed; nothing was logged", e, true);
        }
        if (cache != null) {
            cache.put(key, asked);
        }
        return asked;
    }

    /**
     * Redacts a payload for logging.
     *
     * @param key     the definition version whose pointers apply
     * @param payload the payload, as Vert.x JSON or as the typed object it was bound to, or {@code null}
     * @param tx      the transaction handle
     * @return the payload itself when there is nothing to redact, otherwise a JSON copy with the values at the
     *     pointers replaced
     * @throws WorkflowPersistenceException (retryable) if the redactor throws or names an invalid pointer
     */
    public @Nullable Object redactedCopy(DefinitionKey key, @Nullable Object payload, SqlClient tx) {
        if (payload == null) {
            return null;
        }
        Set<String> pointers = pointers(key, tx);
        if (pointers.isEmpty()) {
            return payload;
        }
        Object json = payload instanceof JsonObject || payload instanceof JsonArray
                ? payload
                : io.vertx.core.json.Json.decodeValue(io.vertx.core.json.Json.encode(payload));
        return redact(json, pointers);
    }

    /**
     * Removes, from a reason that is about to be written to history, every value found at a redacted pointer of the
     * command's payload or of the state, rendered as text.
     *
     * @param key         the definition version whose pointers apply
     * @param reason      the sanitized reason, or {@code null}
     * @param payloadJson the command's payload, or {@code null}
     * @param stateJson   the state before the decision, or {@code null}
     * @param tx          the transaction handle
     * @return the reason with the values replaced
     * @throws WorkflowPersistenceException (retryable) if the redactor throws or names an invalid pointer
     */
    public @Nullable String redactReason(
            DefinitionKey key,
            @Nullable String reason,
            @Nullable String payloadJson,
            @Nullable String stateJson,
            SqlClient tx) {
        if (reason == null || redactor.isEmpty()) {
            return reason;
        }
        Set<String> pointers = pointers(key, tx);
        if (pointers.isEmpty()) {
            return reason;
        }
        String result = reason;
        try {
            for (String source : new String[] {payloadJson, stateJson}) {
                if (source != null) {
                    result = HistoryRedaction.redactText(
                            result, HistoryRedaction.valuesAt(ExactJson.read(source), pointers));
                }
            }
        } catch (IllegalArgumentException e) {
            throw invalidPointers(e);
        }
        return result;
    }

    /**
     * Redacts a reason about the command the transaction executes, using that command's payload.
     *
     * @param reason the sanitized reason, or {@code null}
     * @param tx     the transaction handle
     * @return the reason with the values of the command's payload at the redacted pointers replaced
     */
    public @Nullable String redactReason(@Nullable String reason, SqlClient tx) {
        Scope scope = scopeOf(tx);
        return scope == null ? reason : redactReason(scope.key, reason, scope.command.payloadJson(), null, tx);
    }

    private static Object redact(Object json, Set<String> pointers) {
        try {
            return HistoryRedaction.applyToValue(json, pointers);
        } catch (IllegalArgumentException e) {
            throw invalidPointers(e);
        }
    }

    private static WorkflowPersistenceException invalidPointers(IllegalArgumentException cause) {
        return new WorkflowPersistenceException(
                "The history redactor named an invalid pointer; nothing was logged", cause, true);
    }

    // --- Entries ---

    /**
     * Logs a decision taken on an instance, with the patch of its state change, from the instances before and after it.
     * The decision belongs to the command of the transaction's scope.
     *
     * @param before   the instance before the decision
     * @param after    the instance after it
     * @param stepId   the step the decision was taken at
     * @param outcome  what the decision came to; cut to {@value #MAX_OUTCOME_LENGTH} characters
     * @param tx       the segment's transaction handle
     * @return a future that completes when the entries are appended
     */
    public Future<Void> record(
            WorkflowInstance before, WorkflowInstance after, String stepId, String outcome, SqlClient tx) {
        boolean sameText = Objects.equals(before.stateJson(), after.stateJson());
        ObjectNode beforeState = parseState(before.stateJson());
        ObjectNode afterState = sameText ? beforeState : parseState(after.stateJson());
        boolean changed = !sameText && !beforeState.equals(afterState);
        SegmentCommand command = commandOf(tx);
        Decision decision = new Decision(
                after.id(),
                command == null ? null : command.commandId(),
                stepId,
                bounded(outcome),
                keyOf(after),
                after.planHash(),
                before.version(),
                changed ? after.version() : before.version(),
                null);
        return log(decision, beforeState, afterState, tx);
    }

    /**
     * Logs a decision with the patch of its state change. See the class description for the size rule and the
     * redaction.
     *
     * @param decision the decision
     * @param before   the canonical state before the decision
     * @param after    the canonical state after it
     * @param tx       the segment's transaction handle
     * @return a future that completes when the entries are appended
     */
    public Future<Void> log(Decision decision, ObjectNode before, ObjectNode after, SqlClient tx) {
        Set<String> pointers;
        try {
            pointers = pointers(decision.key(), tx);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return log(decision, before, after, pointers, pointers, false, tx);
    }

    /**
     * Logs a migration: its own decision entry first, diffed from the redacted states before and after, then the full
     * state after it as a baseline. The pointers of the source version cover the state before, those of the target
     * version the state after; both cover the patch.
     *
     * @param decision the decision, under the target version's key
     * @param source   the source version
     * @param before   the canonical state before the migration
     * @param after    the canonical state after it
     * @param tx       the segment's transaction handle
     * @return a future that completes when the entries are appended
     */
    public Future<Void> logMigration(
            Decision decision, DefinitionKey source, ObjectNode before, ObjectNode after, SqlClient tx) {
        Set<String> sourcePointers;
        Set<String> targetPointers;
        try {
            sourcePointers = pointers(source, tx);
            targetPointers = pointers(decision.key(), tx);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Set<String> both = new HashSet<>(sourcePointers);
        both.addAll(targetPointers);
        return log(decision, before, after, both, targetPointers, true, tx);
    }

    private Future<Void> log(
            Decision decision,
            ObjectNode before,
            ObjectNode after,
            Set<String> patchPointers,
            Set<String> baselinePointers,
            boolean alwaysBaseline,
            SqlClient tx) {
        ObjectNode redactedBefore;
        ObjectNode redactedAfter;
        ObjectNode baselineState;
        try {
            if (!alwaysBaseline && before.equals(after)) {
                return append(entry(decision, WorkflowEntryType.DECISION, "[]", null), tx);
            }
            redactedBefore = redactState(before, patchPointers);
            redactedAfter = redactState(after, patchPointers);
            baselineState =
                    baselinePointers.equals(patchPointers) ? redactedAfter : redactState(after, baselinePointers);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        String patch = null;
        if (bytes(redactedBefore.toString()) <= SIZE_BOUND_BYTES
                && bytes(redactedAfter.toString()) <= SIZE_BOUND_BYTES) {
            ArrayNode computed = diff.diff(redactedBefore, redactedAfter);
            String encoded = computed.toString();
            if (bytes(encoded) <= SIZE_BOUND_BYTES) {
                patch = encoded;
            }
        }
        Future<Void> decisionEntry = append(entry(decision, WorkflowEntryType.DECISION, patch, null), tx);
        if (patch != null && !alwaysBaseline) {
            return decisionEntry;
        }
        return decisionEntry.compose(
                done -> append(entry(decision, WorkflowEntryType.STATE_BASELINE, null, baselineState.toString()), tx));
    }

    /**
     * Logs the full state of an instance: the first segment of a start, a migrated instance, or an instance that
     * existed before the log.
     *
     * @param decision the decision the baseline belongs to
     * @param state    the canonical state
     * @param tx       the segment's transaction handle
     * @return a future that completes when the entry is appended
     */
    public Future<Void> baseline(Decision decision, ObjectNode state, SqlClient tx) {
        ObjectNode redacted;
        try {
            redacted = redactState(state, pointers(decision.key(), tx));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return append(entry(decision, WorkflowEntryType.STATE_BASELINE, null, redacted.toString()), tx);
    }

    /**
     * Logs the full state of an instance as it is, for the first segment of a start or an instance that existed before
     * the log. The baseline belongs to the command of the transaction's scope.
     *
     * @param inst the instance
     * @param tx   the segment's transaction handle
     * @return a future that completes when the entry is appended
     */
    public Future<Void> baseline(WorkflowInstance inst, SqlClient tx) {
        SegmentCommand command = commandOf(tx);
        Decision decision = new Decision(
                inst.id(),
                command == null ? null : command.commandId(),
                inst.currentStepId() == null ? "start" : inst.currentStepId(),
                BASELINE_OUTCOME,
                keyOf(inst),
                inst.planHash(),
                inst.version(),
                inst.version(),
                null);
        return baseline(decision, parseState(inst.stateJson()), tx);
    }

    private ObjectNode redactState(ObjectNode state, Set<String> pointers) {
        try {
            return HistoryRedaction.apply(state, pointers);
        } catch (IllegalArgumentException e) {
            throw invalidPointers(e);
        }
    }

    private DecisionEntry entry(
            Decision decision, WorkflowEntryType type, @Nullable String patch, @Nullable String state) {
        DefinitionKey key = decision.key();
        return new DecisionEntry(
                decision.instanceId(),
                type,
                decision.commandId(),
                decision.stepId(),
                type == WorkflowEntryType.STATE_BASELINE ? BASELINE_OUTCOME : decision.outcome(),
                key.scopeKey(),
                key.definitionId(),
                key.definitionVersion(),
                decision.planHash(),
                decision.versionBefore(),
                decision.versionAfter(),
                decision.executorId(),
                patch,
                state,
                clock.instant());
    }

    private Future<Void> append(DecisionEntry entry, SqlClient tx) {
        return history.appendDecision(entry, tx);
    }

    private static DefinitionKey keyOf(WorkflowInstance inst) {
        return new DefinitionKey(inst.definitionScopeKey(), inst.definitionId(), inst.definitionVersion());
    }

    /**
     * Reads the persisted state text once, as a tree that keeps every number exactly: the equality test, the size
     * check, the diff and the baseline of a decision all use this tree.
     *
     * @param stateJson the state's JSON text, or {@code null} for an empty state
     * @return the state
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    public static ObjectNode parseState(@Nullable String stateJson) {
        return ExactJson.readObject(stateJson);
    }

    private static String bounded(String outcome) {
        return outcome.length() <= MAX_OUTCOME_LENGTH ? outcome : outcome.substring(0, MAX_OUTCOME_LENGTH);
    }

    private static int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }
}
