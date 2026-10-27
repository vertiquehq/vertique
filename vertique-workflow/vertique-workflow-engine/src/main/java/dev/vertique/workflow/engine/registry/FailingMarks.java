// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: this node's evaluation of which versions of a scope other than the default one are failing, and the write
 * of that evaluation to the version's row.
 *
 * <p>The evaluation is the node's own build of the version's plan: a name binding that no class resolves builds no plan,
 * and a state type that no longer extends the default scope's builds a plan that is flagged. The outcome is kept with
 * the plan in the {@linkplain PlanCache plan cache} as an {@link Evaluation}, so the node reads it from the same entry
 * the executor runs the plan from. A version of the default scope is never failing and carries no mark.
 *
 * <p>When the outcome differs from the mark the row stored when the plan was built, {@link #write} writes or clears the
 * mark in a statement of its own, outside any transaction of the caller. The write is best effort: a failure is logged
 * at warning level and dropped, the stored mark stays what it was, and the next operation that finds the outcome
 * different writes again. The stored mark only feeds listings; it never decides whether a command runs.
 */
public final class FailingMarks {

    private static final Logger log = LoggerFactory.getLogger(FailingMarks.class);

    private final WorkflowDefinitionRegistryStore store;
    private final PlanCache planCache;

    /**
     * Creates the marks over the store and the cache whose entries carry the evaluations.
     *
     * @param store     the durable definition rows the marks are written to
     * @param planCache the cache of the plans the node built
     */
    FailingMarks(WorkflowDefinitionRegistryStore store, PlanCache planCache) {
        this.store = Objects.requireNonNull(store, "store");
        this.planCache = Objects.requireNonNull(planCache, "planCache");
    }

    /**
     * This node's evaluation of the version, read from the plan the cache holds for it.
     *
     * @param key the scope, definition id and version
     * @return the failure, or empty when the version is not failing or this node holds no plan of it
     */
    public Optional<DefinitionFailure> find(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return planCache.evaluation(key).flatMap(Evaluation::failure);
    }

    /**
     * Writes the evaluation to the version's row when it differs from the mark the row is known to store. The future
     * never fails.
     *
     * @param key        the scope, definition id and version the evaluation belongs to
     * @param evaluation the evaluation
     * @return a future that completes when the statement has ended, or at once when nothing is to be written
     */
    public Future<Void> write(DefinitionKey key, Evaluation evaluation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(evaluation, "evaluation");
        if (WorkflowScopes.DEFAULT.equals(key.scopeKey()) || !evaluation.differs()) {
            return Future.succeededFuture();
        }
        // one write in flight per evaluation: a concurrent operation that sees the same difference leaves it to that
        // one
        if (!evaluation.writing.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        DefinitionFailure target = evaluation.failure().orElse(null);
        Future<Void> written;
        try {
            written = Objects.requireNonNull(store.recordFailure(key, target), "recordFailure returned null");
        } catch (RuntimeException failure) {
            written = Future.failedFuture(failure);
        }
        return written.transform(result -> {
            if (result.succeeded()) {
                evaluation.stored = target == null ? null : target.rule();
            } else {
                log.warn(
                        "The failing mark of definition scope '{}' id '{}' version {} could not be written: {}",
                        EchoedNames.bounded(key.scopeKey()),
                        EchoedNames.bounded(key.definitionId()),
                        key.definitionVersion(),
                        result.cause().getClass().getName());
                log.debug("Mark write failure", result.cause());
            }
            evaluation.writing.set(false);
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Writes the evaluation of a row to its mark when the row's stored mark differs from it. The future may fail
     * with the store's failure.
     *
     * @param record    the row as the caller read it
     * @param evaluated the failure the caller evaluated, or {@code null} when the row does not conflict
     * @return a future that completes when the mark is as evaluated
     */
    public Future<Void> record(WorkflowDefinitionRecord record, @Nullable DefinitionFailure evaluated) {
        Objects.requireNonNull(record, "record");
        DefinitionKey key = record.key();
        if (WorkflowScopes.DEFAULT.equals(key.scopeKey())) {
            return Future.succeededFuture();
        }
        DefinitionFailure stored = record.failure();
        boolean unchanged = evaluated == null ? stored == null : stored != null && stored.rule() == evaluated.rule();
        return unchanged ? Future.succeededFuture() : store.recordFailure(key, evaluated);
    }

    /**
     * What a node's build of one version says: the failure the version is evaluated with, the default scope's state
     * type name it was compared with, and the mark the row is known to store.
     *
     * <p>An evaluation is shared by every operation that uses the plan, and it is replaced in place when the default
     * scope's state type name changes, so the stored mark it tracks survives the change.
     */
    public static final class Evaluation {

        private record Basis(
                @Nullable DefinitionFailure failure,
                @Nullable String defaultStateTypeName) {}

        private volatile Basis basis;
        private volatile @Nullable Rule stored;
        private final AtomicBoolean writing = new AtomicBoolean();

        Evaluation(
                @Nullable DefinitionFailure failure,
                @Nullable String defaultStateTypeName,
                @Nullable DefinitionFailure stored) {
            this.basis = new Basis(failure, defaultStateTypeName);
            this.stored = stored == null ? null : stored.rule();
        }

        /**
         * The failure the version is evaluated with.
         *
         * @return the failure, or empty when the version is not failing
         */
        public Optional<DefinitionFailure> failure() {
            return Optional.ofNullable(basis.failure());
        }

        /** The default scope's state type name the evaluation was made against, or {@code null} when it had none. */
        @Nullable
        String defaultStateTypeName() {
            return basis.defaultStateTypeName();
        }

        /** Replaces the outcome with one evaluated against another default state type name. */
        void reevaluate(@Nullable DefinitionFailure failure, @Nullable String defaultStateTypeName) {
            this.basis = new Basis(failure, defaultStateTypeName);
        }

        /** Whether the outcome differs from the mark the row is known to store. */
        boolean differs() {
            DefinitionFailure failure = basis.failure();
            return (failure == null ? null : failure.rule()) != stored;
        }
    }

    /**
     * A name binding of a version of a scope other than the default one that no class resolves: no plan is built, and
     * the failure is the evaluation of the version.
     */
    public static final class UnresolvedBindingException extends WorkflowUnknownTypeNameException {

        private static final long serialVersionUID = 1L;

        private final transient DefinitionKey key;
        private final transient Evaluation evaluation;

        UnresolvedBindingException(
                WorkflowUnknownTypeNameException unresolved, DefinitionKey key, Evaluation evaluation) {
            super(unresolved.getMessage());
            initCause(unresolved);
            this.key = key;
            this.evaluation = evaluation;
        }

        /**
         * The version whose name binding does not resolve.
         *
         * @return the key
         */
        public DefinitionKey key() {
            return key;
        }

        /**
         * The evaluation of the version: failing with {@link Rule#NAME_BINDING_UNRESOLVED}.
         *
         * @return the evaluation
         */
        public Evaluation evaluation() {
            return evaluation;
        }
    }
}
