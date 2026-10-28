// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.exception.WorkflowConfigurationException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DeclaredSignalNames;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles the code and the packaged documents with the durable registry after migrations and before BOOTSTRAP
 * verticle deployment.
 *
 * <p>The step runs these phases in order, each definition id under its slot of the shared id queue:
 *
 * <ol>
 *   <li>It snapshots the code definitions that the application registered while the registry was constructed in the
 *       {@link WorkflowCodeDefinitionCatalog}, and collects the packaged static documents.
 *   <li>It records every packaged document and every code definition in the table as available. Drift, a
 *       conflicting kind, or a stored code definition that the deployed code no longer provides fails startup.
 *       A packaged document is compiled and built once, so one that does not build fails startup.
 *       A version that is inserted here for the first time and does not opt out of activation on deploy also
 *       becomes the default scope's current version, in the same transaction as its insert, when it is higher than
 *       the current one. A version that is already recorded is never activated by a restart.
 *       A recorded version keeps the type names its plan hash was computed with: the plan is rebuilt under the
 *       stored name binding when that gives the stored hash, so a class that gained a logical name since does not
 *       change the hash. A recorded name that no longer resolves fails startup, except for a retired version.
 *   <li>It checks that every live code row is still provided by the deployed code.
 *   <li>It builds every live default-scope document into the plan cache when
 *       {@code workflow.definitions.buildDefaultScopeDocumentsAtStartup} is set, so that one that does not build fails
 *       startup; otherwise the plan of a document is built from its row on first use. No row of another scope is
 *       read.
 *   <li>It resets publication attempts that went stale, for every definition id that holds a publication attempt.
 *   <li>It seals the registry: registering a definition is refused from then on.
 * </ol>
 *
 * <p>A node with {@code workflow.executor.enabled=false} is recording-only: it holds and runs no workflow code, so it
 * records nothing, checks no code row, builds no plan and resets no attempt; it only seals the registry. A node that
 * runs the executor and has no {@link WorkflowPlanSchemaProducer} bound (the {@code vertique-workflow-schema} module)
 * fails the step before any write.
 *
 * <p>The step keeps no copy of the table: the registry holds the code, the plan cache holds the plans in use, and a
 * node reads the rows it needs. It reads the rows of the default scope and the rows being published, so none of its
 * reads grows with the rows of other scopes.
 */
@Singleton
final class WorkflowDefinitionRegistryBootstrapStep implements ApplicationStartupStep {
    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionRegistryBootstrapStep.class);
    private static final Set<DefinitionStatus> LIVE = Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED);
    private static final Comparator<WorkflowDefinitionRecord> LISTING_ORDER = Comparator.comparing(
                    (WorkflowDefinitionRecord record) -> record.key().definitionId())
            .thenComparing(record -> record.key().scopeKey())
            .thenComparingLong(record -> record.key().definitionVersion());

    private final WorkflowDefinitionRegistryStore store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final WorkflowCodeDefinitionCatalog catalog;
    private final WorkflowTypeNames typeNames;
    private final Optional<WorkflowPlanSchemaProducer> schemaProducer;
    private final Provider<PlanCache> planCache;
    private final boolean buildDefaultScopeDocuments;
    private final boolean executorNode;

    @Inject
    WorkflowDefinitionRegistryBootstrapStep(
            WorkflowDefinitionRegistryStore store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            WorkflowCodeDefinitionCatalog catalog,
            WorkflowTypeNames typeNames,
            Optional<WorkflowPlanSchemaProducer> schemaProducer,
            Provider<PlanCache> planCache,
            Optional<WorkflowDefinitionsConfig> config,
            Optional<WorkflowExecutorConfig> executorConfig) {
        this.executorNode = executorConfig.map(WorkflowExecutorConfig::enabled).orElse(true);
        this.schemaProducer = Objects.requireNonNull(schemaProducer, "schemaProducer");
        this.planCache = planCache;
        this.buildDefaultScopeDocuments = config.map(WorkflowDefinitionsConfig::buildDefaultScopeDocumentsAtStartup)
                .orElse(false);
        this.typeNames = typeNames;
        this.store = Objects.requireNonNull(store, "store");
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
        this.catalog = catalog;
    }

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.BOOTSTRAP;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE / 2;
    }

    @Override
    public Future<Void> start() {
        if (executorNode && schemaProducer.isEmpty()) {
            return Future.failedFuture(new WorkflowConfigurationException(
                    "A node that runs the workflow executor (workflow.executor.enabled=true) needs the"
                            + " vertique-workflow-schema module, which binds the producer of the schemas that definition"
                            + " rows store; add the module, or set workflow.executor.enabled=false for a node that only"
                            + " records"));
        }
        if (!executorNode) {
            // A recording-only node holds no workflow code: it writes nothing, checks no code row and builds no plan.
            return work(() -> seal(registry.get()));
        }
        // Registry construction invokes static contributors; keep their parsing off the event loop.
        return work(this::prepare).compose(prepared -> {
            WorkflowRegistry local = prepared.registry();
            return reconcileOrigins(prepared.origins())
                    .compose(ignored -> reconcileCode(prepared.code()))
                    .compose(ignored -> listBounded())
                    .compose(reconciled -> requireCode(reconciled)
                            .compose(ignored -> buildLiveDocuments(reconciled))
                            .compose(ignored -> resetStale(reconciled)))
                    .map(ignored -> seal(local));
        });
    }

    /** Snapshots the code definitions and collects the packaged documents. It runs on a worker thread. */
    private Prepared prepare() {
        WorkflowRegistry local = registry.get();
        List<DocumentOrigin> origins = loaders.stream()
                .flatMap(loader -> loader.documentOrigins().stream())
                .filter(origin -> WorkflowScopes.DEFAULT.equals(origin.key().scopeKey()))
                .sorted(Comparator.comparing(
                                (DocumentOrigin origin) -> origin.key().definitionId())
                        .thenComparingLong(origin -> origin.key().definitionVersion()))
                .toList();
        return new Prepared(local, origins, catalog.capture(local));
    }

    /**
     * The rows this step reads: those of the default scope, which include every code row and every packaged document,
     * and the rows of any scope that are being published, which only the reset of stale attempts concerns. Both reads
     * are bounded by the default scope or by a status, not by the number of scopes.
     */
    private Future<List<WorkflowDefinitionRecord>> listBounded() {
        return store.list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                .compose(defaults -> store.list(new DefinitionFilter(null, null, Set.of(DefinitionStatus.PUBLISHING)))
                        .map(publishing -> mergedByKey(defaults, publishing)));
    }

    /** The rows of both lists in listing order, one per key; a row of {@code later} replaces one of {@code first}. */
    private static List<WorkflowDefinitionRecord> mergedByKey(
            List<WorkflowDefinitionRecord> first, List<WorkflowDefinitionRecord> later) {
        Map<DefinitionKey, WorkflowDefinitionRecord> merged = new LinkedHashMap<>();
        first.forEach(row -> merged.put(row.key(), row));
        later.forEach(row -> merged.put(row.key(), row));
        return merged.values().stream().sorted(LISTING_ORDER).toList();
    }

    private static List<DefinitionKey> publishing(List<WorkflowDefinitionRecord> rows) {
        return rows.stream()
                .filter(row -> row.status() == DefinitionStatus.PUBLISHING)
                .map(WorkflowDefinitionRecord::key)
                .toList();
    }

    /** Ends the registration window; the registry of another kind has none. */
    private static Void seal(WorkflowRegistry local) {
        if (local instanceof DefaultWorkflowRegistry sealable) sealable.seal();
        return null;
    }

    /**
     * Records every packaged document, so that a stored row of the same key is compared with it. A document that
     * does not opt out is activated in the default scope when this records it for the first time.
     */
    private Future<Void> reconcileOrigins(List<DocumentOrigin> origins) {
        Future<Void> chain = Future.succeededFuture();
        for (DocumentOrigin origin : origins) {
            chain = chain.compose(ignored -> queue.submit(origin.key().definitionId(), () -> {
                WorkflowDefinitionRecord source = new WorkflowDefinitionRecord(
                        origin.key(),
                        DefinitionKind.DOCUMENT,
                        origin.contractName(),
                        origin.stateTypeName(),
                        origin.planHash(),
                        DefinitionStatus.AVAILABLE,
                        origin.provenance(),
                        origin.document().documentFormat(),
                        origin.provenance().loadedAt(),
                        origin.provenance().loadedAt(),
                        origin.nameBinding(),
                        null);
                return store.find(origin.key()).compose(previous -> work(() -> bindDocument(origin, source, previous))
                        .compose(bound -> store.upsertDocument(bound, origin.document(), origin.activateOnDeploy())
                                .compose(outcome -> reconcileResult(bound, previous, outcome))
                                .compose(done -> fillSchemas(bound, previous))));
            }));
        }
        return chain;
    }

    /**
     * Compiles a packaged document and builds its plan, which applies every plan rule and validator to it, and gives
     * the record the type names its stored plan hash used and the signal names and start schema of the plan. It runs
     * on a worker thread.
     */
    private WorkflowDefinitionRecord bindDocument(
            DocumentOrigin origin, WorkflowDefinitionRecord source, Optional<WorkflowDefinitionRecord> previous) {
        PersistedDefinitionLoader loader = DefinitionPublication.loaderFor(loaders, source);
        WorkflowDefinition<?, ?> definition = loader.load(source, origin.document());
        WorkflowDefinitionRecord bound = source;
        if (previous.isPresent() && storedBindingApplies(source, previous.orElseThrow())) {
            WorkflowDefinitionRecord stored = previous.orElseThrow();
            requireBoundNamesResolve(stored);
            if (!stored.planHash().equals(source.planHash())
                    && stored.planHash().equals(DefinitionPublication.planHash(loader, definition, stored)))
                bound = withStoredBinding(source, stored);
        }
        RuntimeWorkflow plan =
                DefinitionPublication.build(registry.get(), bound.key(), definition, bound.nameBinding());
        return withSchemas(bound, plan);
    }

    /**
     * Records every code definition that the deployed code provides. A definition that does not opt out is activated
     * in the default scope when this records it for the first time.
     */
    private Future<Void> reconcileCode(List<CodeDefinition> code) {
        Instant startedAt = Instant.now();
        Future<Void> chain = Future.succeededFuture();
        for (CodeDefinition definition : code) {
            chain = chain.compose(ignored -> queue.submit(definition.definitionId(), () -> {
                DefinitionKey key;
                try {
                    key = new DefinitionKey(WorkflowScopes.DEFAULT, definition.definitionId(), definition.version());
                } catch (IllegalArgumentException invalid) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "Code definition " + printable(definition.definitionId()) + " version "
                                    + definition.version() + " cannot be recorded as a durable definition because it"
                                    + " violates the definition key rules: " + invalid.getMessage(),
                            invalid));
                }
                WorkflowDefinitionRecord source = new WorkflowDefinitionRecord(
                        key,
                        DefinitionKind.CODE,
                        definition.contractName(),
                        definition.stateTypeName(),
                        definition.planHash(),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance(
                                "code",
                                definition.definition() == null
                                        ? null
                                        : definition.definition().getClass().getName(),
                                null,
                                null,
                                null,
                                startedAt),
                        null,
                        startedAt,
                        startedAt,
                        definition.nameBinding(),
                        null);
                return store.find(key).compose(previous -> {
                    WorkflowDefinitionRecord bound = withCodeSchemas(applyStoredBinding(source, previous));
                    return store.upsertCode(
                                    bound,
                                    definition.definition() == null
                                            || definition.definition().activateOnDeploy())
                            .compose(outcome -> reconcileResult(bound, previous, outcome))
                            .compose(done -> fillSchemas(bound, previous));
                });
            }));
        }
        return chain;
    }

    /** Whether the stored row of a version holds a name binding that can apply to the record about to be written. */
    private static boolean storedBindingApplies(WorkflowDefinitionRecord source, WorkflowDefinitionRecord stored) {
        return stored.kind() == source.kind()
                && stored.status() != DefinitionStatus.RETIRED
                && !stored.nameBinding().isEmpty();
    }

    private static WorkflowDefinitionRecord withStoredBinding(
            WorkflowDefinitionRecord source, WorkflowDefinitionRecord stored) {
        return new WorkflowDefinitionRecord(
                source.key(),
                source.kind(),
                source.contractName(),
                source.stateTypeName(),
                stored.planHash(),
                source.status(),
                source.provenance(),
                source.documentFormat(),
                source.createdAt(),
                source.statusChangedAt(),
                stored.nameBinding(),
                null);
    }

    /**
     * Applies the name binding stored on the row of a code version to the plan this start registered. A version
     * recorded with a binding keeps the plan hash it was recorded with when a class has gained a logical name
     * since: the registered plan is rebuilt under the bound names, and the record to reconcile carries the stored
     * hash and binding, so an unchanged step graph reconciles as unchanged. A changed step graph does not rebuild
     * to the stored hash, keeps the plan registered under current names, and reconciles as a changed hash. A
     * retired row is not served, so its binding is neither resolved nor applied.
     *
     * @return the record to reconcile; {@code source} itself when no stored binding applies
     * @throws WorkflowUnknownTypeNameException when the binding names a class that no longer resolves
     */
    private WorkflowDefinitionRecord applyStoredBinding(
            WorkflowDefinitionRecord source, Optional<WorkflowDefinitionRecord> previous) {
        if (previous.isEmpty() || !storedBindingApplies(source, previous.orElseThrow())) return source;
        WorkflowDefinitionRecord stored = previous.orElseThrow();
        requireBoundNamesResolve(stored);
        if (stored.planHash().equals(source.planHash())) return source;
        DefinitionKey key = source.key();
        if (!(registry.get() instanceof DefaultWorkflowRegistry framework)) return source;
        Optional<RuntimeWorkflow> adopted = framework.adoptNameBinding(key, stored.nameBinding(), stored.planHash());
        if (adopted.isEmpty()) return source;
        catalog.rebound(key.definitionId(), key.definitionVersion(), stored.planHash());
        return withStoredBinding(source, stored);
    }

    /**
     * Gives the record the signal names and the start schema of the plan this node registered under its key, so the
     * store writes them with the plan hash. Without a producer, or without a registered plan, the record is returned
     * as it is and the columns stay null.
     */
    private WorkflowDefinitionRecord withCodeSchemas(WorkflowDefinitionRecord record) {
        if (schemaProducer.isEmpty() || record.signalNames() != null) return record;
        Optional<RuntimeWorkflow> plan = registry.get().find(record.key());
        if (plan.isEmpty()) return record;
        return withSchemas(record, plan.get());
    }

    private WorkflowDefinitionRecord withSchemas(WorkflowDefinitionRecord record, RuntimeWorkflow plan) {
        if (schemaProducer.isEmpty()) return record;
        return new WorkflowDefinitionRecord(
                record.key(),
                record.kind(),
                record.contractName(),
                record.stateTypeName(),
                record.planHash(),
                record.status(),
                record.provenance(),
                record.documentFormat(),
                record.createdAt(),
                record.statusChangedAt(),
                record.nameBinding(),
                record.failure(),
                DeclaredSignalNames.of(plan),
                schemaProducer.orElseThrow().startSchema(plan));
    }

    /**
     * Fills the columns of a row that was recorded before they were stored, when this node holds the plan whose hash
     * the row holds. A row that was just inserted or rehashed already carries them.
     */
    private Future<Void> fillSchemas(WorkflowDefinitionRecord reconciled, Optional<WorkflowDefinitionRecord> previous) {
        if (previous.isEmpty() || previous.get().signalNames() != null || reconciled.signalNames() == null)
            return Future.succeededFuture();
        if (!previous.get().planHash().equals(reconciled.planHash())) return Future.succeededFuture();
        return store.fillSignalSchemas(
                reconciled.key(), reconciled.planHash(), reconciled.signalNames(), reconciled.startSchema());
    }

    private void requireBoundNamesResolve(WorkflowDefinitionRecord row) {
        DefinitionPublication.requireBoundNamesResolve(typeNames, row);
    }

    private Future<Void> reconcileResult(
            WorkflowDefinitionRecord source, Optional<WorkflowDefinitionRecord> previous, ReconcileOutcome outcome) {
        DefinitionKey key = source.key();
        boolean code = source.kind() == DefinitionKind.CODE;
        String subject = code ? "code" : "static DOCUMENT";
        switch (outcome) {
            case INSERTED, UNCHANGED -> {
                return Future.succeededFuture();
            }
            case REHASHED -> {
                log.warn(
                        "The plan hash of code definition {} changed from {} to {}; no instance is pinned to this"
                                + " exact version, so the stored hash was replaced",
                        key,
                        previous.map(WorkflowDefinitionRecord::planHash).orElse("(unknown)"),
                        source.planHash());
                return Future.succeededFuture();
            }
            case KIND_CONFLICT -> {
                return Future.failedFuture(failure(
                        Reason.INVALID_TRANSITION,
                        key,
                        subject + " reconciliation " + outcome + ": the stored row is "
                                + previous.map(row -> row.kind() + " from " + described(row.provenance()))
                                        .orElse("of another kind")
                                + ", but this start provides " + source.kind() + " from "
                                + described(source.provenance())
                                + "; a definition id and version belongs either to code or to a document"));
            }
            case DRIFT -> {
                if (code)
                    return Future.failedFuture(failure(
                            Reason.DRIFT,
                            key,
                            "code reconciliation " + outcome + ": stored hash "
                                    + previous.map(WorkflowDefinitionRecord::planHash)
                                            .orElse("(unknown)")
                                    + " differs from deployed code hash " + source.planHash()
                                    + ", and instances pinned to this exact version are still running, waiting or"
                                    + " compensating; stored "
                                    + previous.map(row -> described(row.provenance()))
                                            .orElse("source unavailable")
                                    + ", deployed " + described(source.provenance())
                                    + ". Keep the code of this version unchanged until those instances finish, or"
                                    + " bump the version."));
                if (previous.filter(row -> row.planHash().equals(source.planHash()))
                        .isPresent())
                    return Future.failedFuture(failure(
                            Reason.DRIFT,
                            key,
                            "static DOCUMENT reconciliation " + outcome + ": the text digest of the packaged document"
                                    + " differs from the stored text under an equal plan hash " + source.planHash()
                                    + "; stored from "
                                    + described(previous.orElseThrow().provenance()) + ", packaged from "
                                    + described(source.provenance())
                                    + ". Bump the version of the changed document; it is not enough to retire the old row, because a stored row of"
                                    + " the same id and version blocks startup in every status."));
                String stored = previous.map(
                                row -> "stored hash " + row.planHash() + " from " + described(row.provenance()))
                        .orElse("stored source unavailable");
                return Future.failedFuture(failure(
                        Reason.DRIFT,
                        key,
                        "static DOCUMENT reconciliation " + outcome + ": " + stored + "; static hash "
                                + source.planHash() + " from " + described(source.provenance())));
            }
        }
        throw new IllegalStateException("Unhandled reconcile outcome " + outcome);
    }

    /**
     * A stored code row that is still live must still be provided by the deployed code. Retired and
     * candidate code rows may outlive their code.
     */
    private Future<Void> requireCode(List<WorkflowDefinitionRecord> rows) {
        for (WorkflowDefinitionRecord row : rows) {
            if (row.kind() != DefinitionKind.CODE || !LIVE.contains(row.status())) continue;
            if (catalog.find(row.key().definitionId(), row.key().definitionVersion())
                    .isEmpty())
                return Future.failedFuture(failure(
                        Reason.CODE_UNAVAILABLE,
                        row.key(),
                        "the stored " + row.status() + " CODE definition (hash " + row.planHash() + ", "
                                + described(row.provenance()) + ") is not provided by the deployed code any more;"
                                + " restore the code, or retire the definition before removing its code"));
        }
        return Future.succeededFuture();
    }

    /**
     * Builds every live default-scope document into the plan cache when the configuration asks for it, by the build a
     * first use runs, so that one that does not build fails the start. A document row that is not live any more keeps
     * no failure mark of an earlier startup. A row of another scope is not read; such a row is here only because it is
     * being published.
     */
    private Future<Void> buildLiveDocuments(List<WorkflowDefinitionRecord> rows) {
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord row : rows) {
            if (row.kind() != DefinitionKind.DOCUMENT
                    || !WorkflowScopes.DEFAULT.equals(row.key().scopeKey())) continue;
            DefinitionKey key = row.key();
            if (!LIVE.contains(row.status())) {
                if (row.failure() != null)
                    chain = chain.compose(
                            ignored -> queue.submit(key.definitionId(), () -> store.recordFailure(key, null)));
                continue;
            }
            if (!buildDefaultScopeDocuments) continue;
            chain = chain.compose(ignored -> queue.submit(
                    key.definitionId(), () -> planCache.get().load(key).<Void>mapEmpty()));
        }
        return chain;
    }

    /**
     * Resets the publication attempts that went stale, under the id lock, for every definition id that
     * the table holds a publication attempt for, whether or not code or a loader knows the id.
     */
    private Future<Void> resetStale(List<WorkflowDefinitionRecord> committed) {
        TreeSet<String> ids = committed.stream()
                .filter(row -> row.status() == DefinitionStatus.PUBLISHING)
                .map(row -> row.key().definitionId())
                .collect(Collectors.toCollection(TreeSet::new));
        Future<Void> chain = Future.succeededFuture();
        for (String id : ids) chain = chain.compose(ignored -> queue.submit(id, () -> store.resetStalePublishing(id)));
        return chain;
    }

    /** The key in a form that is safe to log: bounded and without control characters. */
    private static String printable(DefinitionKey key) {
        return "scope '" + EchoedNames.bounded(key.scopeKey()) + "' id '" + EchoedNames.bounded(key.definitionId())
                + "' version " + key.definitionVersion();
    }

    private static <T> Future<T> work(Supplier<T> action) {
        var context = Vertx.currentContext();
        if (context != null) return context.executeBlocking(action::get, false);
        try {
            return Future.succeededFuture(action.get());
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    /** Renders a source record for a message, with every non-printable character of its labels escaped. */
    private static String described(Object provenance) {
        return printable(String.valueOf(provenance));
    }

    /** Renders text for a message with every non-printable character escaped, so that it cannot forge log lines. */
    private static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            boolean visible = type != Character.CONTROL
                    && type != Character.FORMAT
                    && type != Character.SURROGATE
                    && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR
                    && type != Character.PRIVATE_USE
                    && type != Character.UNASSIGNED;
            if (visible) {
                out.appendCodePoint(codePoint);
            } else {
                for (char unit : Character.toChars(codePoint)) out.append(String.format("\\u%04X", (int) unit));
            }
        });
        return out.toString();
    }

    private static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String detail) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + detail);
    }

    private record Prepared(WorkflowRegistry registry, List<DocumentOrigin> origins, List<CodeDefinition> code) {}
}
