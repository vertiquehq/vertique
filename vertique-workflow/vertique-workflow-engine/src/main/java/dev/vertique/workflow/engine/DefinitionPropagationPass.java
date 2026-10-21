// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One reconciliation of this node's registry with the committed definition table.
 *
 * <p>A pass lists the table's metadata once and then applies it in two ordered rounds, each row under its slot of
 * the shared {@link DefinitionApplyQueue} and each re-read inside the slot before anything is done to it. The round
 * a row takes follows its re-read status: a row whose status moved to the other round meanwhile is left untouched
 * for the next pass, and a row that vanished is forgotten.
 *
 * <ol>
 *   <li>The removal round handles the retired rows, removing only a runtime that is attributed to the row's own
 *       owner, and hands candidate and retired documents to their loader as metadata. A retired row whose runtime
 *       is unattributed has nothing of its owner to remove. Activating rows are left alone.
 *   <li>The restore round handles the live rows. A runtime that the owner already holds is verified against the
 *       row; a missing one is restored from the stored document through its loader, or from the deployed code
 *       through the {@link WorkflowCodeDefinitionCatalog}, hash-verified, and attributed to its owner. A
 *       deprecated row is marked after it is restored; a runtime that cannot be marked is removed again.
 * </ol>
 *
 * <p>Because every removal precedes every restoration, the order of the listing does not matter. The loader is
 * handed a row before the node view records it, so a hand-off that fails is attempted again by the next pass. A
 * row that is already settled on this node is skipped without a slot or a read; anything else takes the full path.
 * A pass never changes a durable row, never records a code hash and never publishes code that the deployed code
 * does not provide. A row that cannot be applied, or that does not finish within twice the poll period, is logged
 * with its key and the typed reason, without any source text, counted in the node view and retried by the next
 * pass; the other rows still apply. A store error while re-reading a row fails the pass.
 *
 * <p>All diagnostics use the logger of {@link WorkflowDefinitionPropagationPoller}.
 */
@Singleton
final class DefinitionPropagationPass {
    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionPropagationPoller.class.getName());
    private static final Set<DefinitionStatus> LIVE =
            Set.of(DefinitionStatus.ACTIVE, DefinitionStatus.SUPERSEDED, DefinitionStatus.DEPRECATED);
    private static final Set<DefinitionStatus> HOLDING = Set.of(
            DefinitionStatus.ACTIVE,
            DefinitionStatus.SUPERSEDED,
            DefinitionStatus.DEPRECATED,
            DefinitionStatus.ACTIVATING);

    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final ManagedRegistrations registrations;
    private final WorkflowCodeDefinitionCatalog catalog;
    private final DefaultWorkflowDefinitionRegistryView view;
    private final Vertx vertx;
    private final long rowDeadlineMs;
    /** The status and change instant at which a row that is not live was last settled, by key. */
    private final Map<DefinitionKey, Settled> settledRows = new ConcurrentHashMap<>();
    /** The typed reason of the latest failure of a row, by key. */
    private final Map<DefinitionKey, String> reasons = new ConcurrentHashMap<>();

    @Inject
    DefinitionPropagationPass(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog,
            DefaultWorkflowDefinitionRegistryView view,
            Vertx vertx,
            Optional<WorkflowDefinitionsConfig> config) {
        this.vertx = vertx;
        long seconds = config.orElseGet(WorkflowDefinitionsConfig::defaults).maxStalenessSeconds();
        long deadlineMs;
        try {
            deadlineMs = Math.multiplyExact(Math.multiplyExact(seconds, 2L), 1000L);
        } catch (ArithmeticException overflow) {
            deadlineMs = Long.MAX_VALUE;
        }
        this.rowDeadlineMs = deadlineMs;
        this.store = store;
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
        this.registrations = registrations;
        this.catalog = catalog;
        this.view = view;
    }

    /**
     * Runs one pass. The returned future fails only when the table cannot be read; a row that cannot be applied
     * is counted in the view instead.
     */
    Future<Void> run() {
        if (store.isEmpty()) return Future.succeededFuture();
        return store.orElseThrow().list(new DefinitionFilter(null, null, null)).compose(rows -> {
            Set<DefinitionKey> listed = new HashSet<>();
            for (WorkflowDefinitionRecord row : rows) listed.add(row.key());
            Future<Void> chain = Future.succeededFuture();
            for (WorkflowDefinitionRecord row : rows)
                if (!LIVE.contains(row.status())) chain = chain.compose(ignored -> apply(row, true));
            for (WorkflowDefinitionRecord row : rows)
                if (LIVE.contains(row.status())) chain = chain.compose(ignored -> apply(row, false));
            return chain.map(ignored -> {
                view.retainFailures(listed);
                settledRows.keySet().retainAll(listed);
                reasons.keySet().retainAll(listed);
                return (Void) null;
            });
        });
    }

    /**
     * One round for one listed row: skips it when it is settled, otherwise re-reads it under its id slot, applies it
     * within the row deadline, and turns a failure of the row into a counted one.
     */
    private Future<Void> apply(WorkflowDefinitionRecord listedRow, boolean removal) {
        DefinitionKey key = listedRow.key();
        if (settled(listedRow)) return Future.succeededFuture();
        Future<Boolean> applied = queue.submit(
                key.definitionId(),
                () -> safely(() -> store.orElseThrow()
                        .find(key)
                        .recover(error -> Future.failedFuture(fatal(error) ? error : new TableUnreadable(error)))
                        .compose(found -> {
                            if (found.isEmpty()) {
                                view.removed(key);
                                settledRows.remove(key);
                                reasons.remove(key);
                                return Future.succeededFuture(false);
                            }
                            return removal ? removeRound(found.get(), listedRow.status()) : restoreRound(found.get());
                        })));
        // The read of the row is not part of the row's own failure: a table that cannot be read fails the pass.
        return withDeadline(key, applied)
                .map(handled -> {
                    if (handled) reasons.remove(key);
                    return (Void) null;
                })
                .recover(error -> {
                    if (fatal(error)) return Future.failedFuture(error);
                    if (error instanceof TableUnreadable unreadable) return Future.failedFuture(unreadable.getCause());
                    fail(key, error);
                    return Future.succeededFuture();
                });
    }

    /**
     * Whether the listed row is already settled on this node, so that neither a slot nor a read is needed for it.
     * A wrong answer only defers the row to the next pass, so the check does not take the slot.
     */
    private boolean settled(WorkflowDefinitionRecord row) {
        DefinitionKey key = row.key();
        try {
            WorkflowRegistry local = registry.get();
            RuntimeWorkflow held = DefinitionPublication.held(local, key.definitionId(), key.definitionVersion());
            ManagedRegistrations.Standing standing =
                    registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
            boolean own = standing == ManagedRegistrations.Standing.OWN;
            if (!LIVE.contains(row.status())) {
                Settled memo = settledRows.get(key);
                return memo != null
                        && memo.status() == row.status()
                        && memo.changedAt().equals(row.statusChangedAt())
                        && !own;
            }
            return own
                    && held.plan().planHash().equals(row.planHash())
                    && view.recorded(key)
                            .filter(known -> known.status() == row.status()
                                    && known.statusChangedAt().equals(row.statusChangedAt()))
                            .isPresent()
                    && local.isDeprecated(key.definitionId(), key.definitionVersion())
                            == (row.status() == DefinitionStatus.DEPRECATED)
                    && !view.failing(key);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Bounds the row by the row deadline; the slot of the row stays held until its action completes. */
    private Future<Boolean> withDeadline(DefinitionKey key, Future<Boolean> applied) {
        Promise<Boolean> bounded = Promise.promise();
        long timer = vertx.setTimer(rowDeadlineMs, ignored -> bounded.tryFail(new RowTimeout(rowDeadlineMs)));
        applied.onComplete(outcome -> {
            vertx.cancelTimer(timer);
            if (outcome.succeeded()) bounded.tryComplete(outcome.result());
            else bounded.tryFail(outcome.cause());
        });
        return bounded.future();
    }

    /** Runs the action and turns anything it throws into a failed future, so that the slot is always released. */
    private static <T> Future<T> safely(Supplier<Future<T>> action) {
        try {
            return action.get();
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    /** Errors that end the process's ability to go on; every other throwable is contained per row. */
    private static boolean fatal(Throwable error) {
        return error instanceof OutOfMemoryError
                || "java.lang.ThreadDeath".equals(error.getClass().getName())
                || (error instanceof VirtualMachineError && !(error instanceof StackOverflowError));
    }

    /** The store could not answer a read of the table. */
    private static final class TableUnreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TableUnreadable(Throwable cause) {
            super(cause);
        }
    }

    /** The row did not finish within its deadline. */
    private static final class RowTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final long deadlineMs;

        RowTimeout(long deadlineMs) {
            super("not applied within " + deadlineMs + " ms");
            this.deadlineMs = deadlineMs;
        }
    }

    private record Settled(DefinitionStatus status, Instant changedAt) {}

    /**
     * The removal round for a row listed as not live. It acts only on a row that was re-read as retired or as a
     * candidate; a row that moved to a live status meanwhile is left for the next pass.
     *
     * @return whether the row was handled
     */
    private Future<Boolean> removeRound(WorkflowDefinitionRecord record, DefinitionStatus listed) {
        DefinitionKey key = record.key();
        switch (record.status()) {
            case RETIRED -> {
                WorkflowRegistry local = registry.get();
                RuntimeWorkflow held = DefinitionPublication.held(local, key.definitionId(), key.definitionVersion());
                ManagedRegistrations.Standing standing =
                        registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
                boolean removed = false;
                if (standing == ManagedRegistrations.Standing.OWN) {
                    local.unregister(key.definitionId(), key.definitionVersion());
                    registrations.clear(key.definitionId(), key.definitionVersion(), held);
                    removed = true;
                }
                view.removed(key);
                handOffMetadata(record);
                if (removed) log.info("Removed definition {} from this node: it is retired", key);
            }
            case CANDIDATE -> {
                view.settled(key);
                handOffMetadata(record);
            }
            case ACTIVATING -> {
                if (listed != DefinitionStatus.ACTIVATING) return Future.succeededFuture(false);
                view.settled(key);
            }
            default -> {
                return Future.succeededFuture(false);
            }
        }
        settledRows.put(key, new Settled(record.status(), record.statusChangedAt()));
        return Future.succeededFuture(true);
    }

    /**
     * The restore round for a row listed as live. It acts only on a row that was re-read as live.
     *
     * @return whether the row was handled
     */
    private Future<Boolean> restoreRound(WorkflowDefinitionRecord record) {
        DefinitionKey key = record.key();
        if (!LIVE.contains(record.status())) return Future.succeededFuture(false);
        WorkflowRegistry local = registry.get();
        RuntimeWorkflow held = DefinitionPublication.held(local, key.definitionId(), key.definitionVersion());
        ManagedRegistrations.Standing standing =
                registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
        Future<Void> restored;
        switch (standing) {
            case UNATTRIBUTED ->
                throw DefinitionPublication.failure(
                        Reason.INVALID_TRANSITION,
                        key,
                        "the registered runtime was not published by the durable lifecycle for this definition, so"
                                + " the committed state cannot be applied to it");
            case OWN -> {
                applyHeld(local, record, held);
                restored = Future.succeededFuture();
            }
            case OTHER_OWNER -> restored = releaseStale(local, record, held).compose(ignored -> publish(local, record));
            default -> restored = publish(local, record);
        }
        return restored.map(ignored -> {
            settledRows.remove(key);
            return true;
        });
    }

    /**
     * Verifies the runtime the owner holds against the row, and brings its deprecation mark, the loader and the
     * view up to date. The loader is handed the row before the view records it, so a hand-off that fails is
     * attempted again by the next pass.
     */
    private void applyHeld(WorkflowRegistry local, WorkflowDefinitionRecord record, RuntimeWorkflow held) {
        DefinitionKey key = record.key();
        if (record.status() == DefinitionStatus.DEPRECATED
                && !local.isDeprecated(key.definitionId(), key.definitionVersion()))
            local.markDeprecated(key.definitionId(), key.definitionVersion());
        String registered = held.plan().planHash();
        if (!record.planHash().equals(registered))
            throw DefinitionPublication.failure(
                    Reason.DRIFT,
                    key,
                    "stored hash " + record.planHash() + " differs from registered hash " + registered);
        boolean news = view.recorded(key)
                .filter(known ->
                        known.status() == record.status() && known.planHash().equals(record.planHash()))
                .isEmpty();
        if (news && record.kind() == DefinitionKind.DOCUMENT)
            for (PersistedDefinitionLoader loader : loaders) {
                if (!loader.supports(record.documentFormat())) continue;
                Optional<WorkflowDefinition<?, ?>> definition =
                        local.registeredDefinition(key.definitionId(), key.definitionVersion());
                if (definition.isPresent()) loader.onRestored(record, definition.get());
                else loader.onMetadata(record);
            }
        view.applied(record);
        if (news) log.info("Applied definition {} on this node as {}", key, record.status());
    }

    /**
     * Frees the slot held by the runtime of another owner, but only when that owner has no live row any more.
     * Otherwise the row fails as an owner conflict and is retried.
     */
    private Future<Void> releaseStale(WorkflowRegistry local, WorkflowDefinitionRecord record, RuntimeWorkflow held) {
        DefinitionKey key = record.key();
        ManagedRegistrations.Owner owner = registrations
                .ownerOf(key.definitionId(), key.definitionVersion(), held)
                .orElseThrow();
        DefinitionKey otherKey = new DefinitionKey(owner.scopeKey(), key.definitionId(), key.definitionVersion());
        return store.orElseThrow()
                .find(otherKey)
                .recover(error -> Future.failedFuture(fatal(error) ? error : new TableUnreadable(error)))
                .map(found -> {
                    if (found.filter(other -> HOLDING.contains(other.status())).isPresent())
                        throw DefinitionPublication.failure(
                                Reason.OWNER_CONFLICT,
                                key,
                                "the registered runtime belongs to another owner that still holds this definition id"
                                        + " and version");
                    local.unregister(key.definitionId(), key.definitionVersion());
                    registrations.clear(key.definitionId(), key.definitionVersion(), held);
                    view.removed(otherKey);
                    return (Void) null;
                });
    }

    private Future<Void> publish(WorkflowRegistry local, WorkflowDefinitionRecord record) {
        DefinitionKey key = record.key();
        Future<Void> published = record.kind() == DefinitionKind.CODE
                ? work(() -> {
                    CodeDefinition code = DefinitionPublication.verifiedCode(catalog, record);
                    publishAndMark(local, record, code.definition());
                    return (Void) null;
                })
                : publishDocument(local, record);
        return published.map(ignored -> {
            view.applied(record);
            log.info("Applied definition {} on this node as {}", key, record.status());
            return (Void) null;
        });
    }

    private Future<Void> publishDocument(WorkflowRegistry local, WorkflowDefinitionRecord record) {
        DefinitionKey key = record.key();
        PersistedDefinitionLoader loader = DefinitionPublication.loaderFor(loaders, record);
        return store.orElseThrow()
                .findDocument(key)
                .compose(document -> work(() -> {
                    WorkflowDefinitionDocument source = document.orElseThrow(() ->
                            DefinitionPublication.failure(Reason.LOADER_MISSING, key, "DOCUMENT source is missing"));
                    WorkflowDefinition<?, ?> definition;
                    String hash;
                    try {
                        definition = loader.load(record, source);
                        hash = loader.planHash(definition);
                    } catch (RuntimeException failure) {
                        throw new WorkflowDefinitionException(
                                "Cannot restore DOCUMENT " + key + " from stored source", failure);
                    }
                    if (!key.definitionId().equals(definition.definitionId())
                            || key.definitionVersion() != definition.definitionVersion()
                            || !record.planHash().equals(hash))
                        throw DefinitionPublication.failure(
                                Reason.DRIFT,
                                key,
                                "stored hash " + record.planHash() + " differs from compiled hash " + hash);
                    publishAndMark(local, record, definition);
                    loader.onRestored(record, definition);
                    return (Void) null;
                }));
    }

    /**
     * Publishes the definition for the row's owner and marks it deprecated when the row is. A runtime that could
     * not be marked is removed and unattributed again, so that a deprecated version is never served unmarked.
     */
    private void publishAndMark(
            WorkflowRegistry local, WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {
        DefinitionKey key = record.key();
        RuntimeWorkflow published = DefinitionPublication.publish(local, registrations, key, definition);
        if (record.status() != DefinitionStatus.DEPRECATED) return;
        try {
            local.markDeprecated(key.definitionId(), key.definitionVersion());
        } catch (RuntimeException failure) {
            try {
                local.unregister(key.definitionId(), key.definitionVersion());
                registrations.clear(key.definitionId(), key.definitionVersion(), published);
            } catch (RuntimeException compensation) {
                if (compensation != failure) failure.addSuppressed(compensation);
            }
            throw failure;
        }
    }

    /** Hands a candidate or retired document to the loaders that support its format. */
    private void handOffMetadata(WorkflowDefinitionRecord record) {
        if (record.kind() != DefinitionKind.DOCUMENT) return;
        List<PersistedDefinitionLoader> matches = loaders.stream()
                .filter(loader -> loader.supports(record.documentFormat()))
                .toList();
        for (PersistedDefinitionLoader loader : matches) loader.onMetadata(record);
    }

    /**
     * Counts the row as failing and logs its key and the typed reason. The first failure of a row, and every change
     * of its reason, is a warning; the same failure on a later pass is logged at debug level only. The message of a
     * lifecycle failure is written by the engine and carries no source text; any other failure is logged by its
     * type alone, because a parser failure can quote the text it parsed.
     */
    private void fail(DefinitionKey key, Throwable error) {
        settledRows.remove(key);
        String reason = error instanceof WorkflowDefinitionLifecycleException lifecycle
                ? lifecycle.reason().name()
                : error instanceof RowTimeout ? "TIMEOUT" : error.getClass().getSimpleName();
        boolean repeated = view.failing(key) && reason.equals(reasons.get(key));
        reasons.put(key, reason);
        view.failed(key);
        String detail = error instanceof WorkflowDefinitionLifecycleException || error instanceof RowTimeout
                ? error.getMessage()
                : error.getClass().getSimpleName();
        if (repeated) {
            log.debug("Definition {} still could not be applied on this node: {} ({})", key, reason, detail);
            return;
        }
        log.warn(
                "Definition {} could not be applied on this node: {} ({}); it is counted as failing and retried on"
                        + " the next pass",
                key,
                reason,
                detail);
    }

    private static <T> Future<T> work(Supplier<T> action) {
        var context = Vertx.currentContext();
        if (context != null) return context.executeBlocking(action::get, false);
        try {
            return Future.succeededFuture(action.get());
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }
}
