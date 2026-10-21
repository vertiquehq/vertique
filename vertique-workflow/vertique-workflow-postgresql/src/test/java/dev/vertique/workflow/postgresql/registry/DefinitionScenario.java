// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the application graphs, pools and test rows of one scenario against a migrated database.
 *
 * <p>Graphs are started blocking from the test thread, so a scenario reads top to bottom. Everything the scenario
 * opened is closed in reverse order by {@link #close()}, and the rows it owns are removed again so that none reaches a
 * later start.
 */
final class DefinitionScenario implements AutoCloseable {

    /** One committed definition row read straight from the table, without the source text digest. */
    record Stored(
            String scopeKey,
            long version,
            String kind,
            String status,
            String planHash,
            String document,
            String documentFormat,
            String sourceType,
            String sourceUri,
            String sourceRevision,
            String sourceAuthor,
            OffsetDateTime loadedAt,
            OffsetDateTime createdAt,
            OffsetDateTime changedAt,
            UUID publishToken) {}

    @FunctionalInterface
    interface Cleanup {
        void run() throws Exception;
    }

    /** One attempt to start an application graph on its own pool; closing it is idempotent. */
    static final class Boot {
        private final Pool pool;
        private final AppComponent app;
        private final VertiqueApplicationHandle<AppComponent> handle;
        private final Throwable failure;
        private boolean closed;

        private Boot(Pool pool, AppComponent app, VertiqueApplicationHandle<AppComponent> handle, Throwable failure) {
            this.pool = pool;
            this.app = app;
            this.handle = handle;
            this.failure = failure;
        }

        AppComponent app() {
            return app;
        }

        /** The startup failure, or {@code null} when the graph started. */
        Throwable failure() {
            return failure;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (handle != null) await(handle.shutdown());
            } finally {
                await(pool.close());
            }
        }
    }

    private final Vertx vertx;
    private final PostgresContainer db;
    private final Deque<Cleanup> cleanups = new ArrayDeque<>();
    private Pool admin;

    DefinitionScenario(Vertx vertx, PostgresContainer db) {
        this.vertx = vertx;
        this.db = db;
    }

    void onClose(Cleanup cleanup) {
        cleanups.push(cleanup);
    }

    /** Starts a graph that must start, with startup observation disabled. */
    Boot node(WorkflowDefinitionSource source) {
        return node(source, new BootObservation("digest-unused", 1, "unused", false));
    }

    /** Starts a graph that must start, observed by the given boot observation. */
    Boot node(WorkflowDefinitionSource source, BootObservation observation) {
        Boot boot = attempt(source, observation);
        if (boot.failure() != null) {
            boot.close();
            throw new AssertionError("The application graph was expected to start", boot.failure());
        }
        return boot;
    }

    /** Starts a graph that must start and whose application contributes the given code-first definitions. */
    Boot node(
            WorkflowDefinitionSource source,
            BootObservation observation,
            List<WorkflowDefinition<?, ?>> codeDefinitions) {
        Boot boot = attempt(source, observation, codeDefinitions, null, 10);
        if (boot.failure() != null) {
            boot.close();
            throw new AssertionError("The application graph was expected to start", boot.failure());
        }
        return boot;
    }

    /** Starts a graph and reports its startup failure, if any; a graph that did start is closed with the scenario. */
    Boot attempt(WorkflowDefinitionSource source, BootObservation observation) {
        return attempt(source, observation, List.of(), null, 10);
    }

    /**
     * Starts a graph that contributes the given code-first definitions on a pool of the given size, optionally observed
     * by the SQL observer, and reports its startup failure, if any.
     */
    Boot attempt(
            WorkflowDefinitionSource source,
            BootObservation observation,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            WorkflowDefinitionSqlObserver observer,
            int poolSize) {
        return begin(source, observation, codeDefinitions, observer, poolSize).await();
    }

    /** A startup that has been requested and may still be waiting, for instance on a lock. */
    final class PendingBoot {
        private final Pool pool;
        private final AppComponent app;
        private final Future<VertiqueApplicationHandle<AppComponent>> starting;
        private Boot boot;

        private PendingBoot(Pool pool, AppComponent app, Future<VertiqueApplicationHandle<AppComponent>> starting) {
            this.pool = pool;
            this.app = app;
            this.starting = starting;
        }

        AppComponent app() {
            return app;
        }

        /** Waits for the startup to finish and returns its outcome. */
        Boot await() {
            if (boot == null) {
                Throwable failure = failureOf(starting);
                boot = new Boot(pool, app, failure == null ? starting.result() : null, failure);
            }
            return boot;
        }
    }

    /** Requests a startup like {@link #attempt} but returns before it has finished. */
    PendingBoot begin(
            WorkflowDefinitionSource source,
            BootObservation observation,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            WorkflowDefinitionSqlObserver observer,
            int poolSize) {
        Pool raw = ManagedDefinitionDatabase.openPool(vertx, db, poolSize);
        Pool pool = observer == null ? raw : observer.observe(raw);
        AppComponent app;
        try {
            app = ManagedDefinitionDatabase.appWithCode(
                    vertx, pool, new JsonObject(), observation, source, codeDefinitions);
        } catch (RuntimeException | Error failure) {
            await(pool.close());
            throw failure;
        }
        Future<VertiqueApplicationHandle<AppComponent>> starting =
                VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app);
        PendingBoot pending = new PendingBoot(pool, app, starting);
        onClose(() -> pending.await().close());
        return pending;
    }

    /** Counts the backends that hold or await the definition id's advisory lock in the given mode. */
    long advisoryCount(String definitionId, String mode, boolean granted) {
        long key = lockKey(definitionId);
        long high = (key >>> 32) & 0xFFFFFFFFL;
        long low = key & 0xFFFFFFFFL;
        return await(admin().preparedQuery("""
                                SELECT count(*) AS total FROM pg_locks
                                WHERE locktype = 'advisory' AND objsubid = 1
                                  AND classid::bigint = $1 AND objid::bigint = $2
                                  AND mode = $3 AND granted = $4
                                """).execute(Tuple.of(high, low, mode, granted)))
                .iterator()
                .next()
                .getLong("total");
    }

    /** Waits until exactly the expected number of locks of the mode are held or awaited. */
    void awaitAdvisory(String definitionId, String mode, boolean granted, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long seen = -1;
        while (System.nanoTime() < deadline) {
            seen = advisoryCount(definitionId, mode, granted);
            if (seen == expected) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected " + expected + (granted ? " granted " : " queued ") + mode + " locks on "
                + definitionId + " but saw " + seen);
    }

    private static long lockKey(String definitionId) {
        try {
            return java.nio.ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Returns the pool used for test-owned SQL. */
    Pool admin() {
        if (admin == null) {
            admin = ManagedDefinitionDatabase.openPool(vertx, db);
            Pool opened = admin;
            onClose(() -> await(opened.close()));
        }
        return admin;
    }

    /** Clears the ids' rows now and again on close, so none reaches a later start. */
    void ownRows(String... definitionIds) {
        deleteRows(definitionIds);
        onClose(() -> deleteRows(definitionIds));
    }

    private void deleteRows(String... definitionIds) {
        for (String definitionId : definitionIds) {
            await(admin().preparedQuery("DELETE FROM workflow_instances WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
            await(admin().preparedQuery("DELETE FROM workflow_definition_activations WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
            await(admin().preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
        }
    }

    /** Inserts a default-scope code row directly, with the code provenance and no document. */
    void seedCode(String definitionId, long version, String planHash, String status) {
        execute("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, source_type, loaded_at)
                VALUES ('default', $1, $2, 'CODE', 'example.Contract', 'example.State', $3, $4, 'code', now())
                """, Tuple.of(definitionId, version, planHash, status));
    }

    /**
     * Inserts a document row directly. Its digest is the SQL digest of its text, and a row seeded as {@code
     * PUBLISHING} carries a claim token.
     */
    void seedDocument(String scopeKey, String definitionId, long version, String planHash, String status, String text) {
        execute("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                     document, document_format, document_digest, source_type, loaded_at, publish_token)
                VALUES ($1, $2, $3, 'DOCUMENT', 'example.Contract', 'example.State', $4, $5, $6, 'YAML',
                        encode(sha256(convert_to($6::text, 'UTF8')), 'hex'), 'seed-docs', now(),
                        CASE WHEN $5 = 'PUBLISHING' THEN gen_random_uuid() ELSE NULL END)
                """, Tuple.of(scopeKey, definitionId, version, planHash, status, text));
    }

    /** Moves the row's status change time to the given age, measured by the database clock. */
    void ageStatus(DefinitionKey key, long ageSeconds) {
        execute("""
                UPDATE workflow_definitions
                SET status_changed_at = clock_timestamp() - ($4::bigint * interval '1 second')
                WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                """, Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), ageSeconds));
    }

    /** Moves a global document row into a claim that nobody completed, so only its age decides whether it is stale. */
    void claimWithoutCompleting(DefinitionKey key) {
        execute("""
                UPDATE workflow_definitions
                SET status = 'PUBLISHING', publish_token = gen_random_uuid()
                WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                """, Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion()));
    }

    /** Inserts an instance row pinned to the exact version and plan hash. */
    UUID insertInstance(String definitionId, long version, String planHash, String status) {
        UUID instanceId = UUID.randomUUID();
        execute("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, version, status, current_step_id, state_json)
                VALUES ($1, $2, $3, $4, 0, $5, 'start', '{}'::jsonb)
                """, Tuple.of(instanceId, definitionId, version, planHash, status));
        return instanceId;
    }

    /** Counts the instances of the definition id, whatever their version and status. */
    long instanceCount(String definitionId) {
        return await(admin().preparedQuery("SELECT count(*) AS total FROM workflow_instances WHERE definition_id = $1")
                        .execute(Tuple.of(definitionId)))
                .iterator()
                .next()
                .getLong("total");
    }

    /** The status of one instance row. */
    String instanceStatus(UUID instanceId) {
        return await(admin().preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(instanceId)))
                .iterator()
                .next()
                .getString("status");
    }

    /** Removes every row and instance of the definition ids now, so a later start does not meet them. */
    void removeRows(String... definitionIds) {
        deleteRows(definitionIds);
    }

    /** Counts the start deduplication rows of the definition id. */
    long dedupCount(String definitionId) {
        return await(admin().preparedQuery(
                                "SELECT count(*) AS total FROM workflow_dedup WHERE kind = 'start' AND dedup_namespace = $1")
                        .execute(Tuple.of(definitionId)))
                .iterator()
                .next()
                .getLong("total");
    }

    /** Counts the instances in a live status that are pinned to the exact version and plan hash. */
    long livePinCount(String definitionId, long version, String planHash) {
        return await(admin().preparedQuery("""
                                SELECT count(*) AS total FROM workflow_instances
                                WHERE definition_id = $1 AND definition_version = $2 AND plan_hash = $3
                                  AND status IN ('RUNNING', 'WAITING', 'COMPENSATING')
                                """).execute(Tuple.of(definitionId, version, planHash)))
                .iterator()
                .next()
                .getLong("total");
    }

    /** Executes one statement against the scenario database. */
    void execute(String sql, Tuple parameters) {
        await(admin().preparedQuery(sql).execute(parameters));
    }

    /** Returns the global row, or empty when there is none. */
    Optional<Stored> findStored(String definitionId, long version) {
        return findStored(WorkflowScopes.DEFAULT, definitionId, version);
    }

    /** Returns the row of the owner scope, or empty when there is none. */
    Optional<Stored> findStored(String scopeKey, String definitionId, long version) {
        List<Stored> rows = new ArrayList<>();
        for (Row row : await(admin().preparedQuery("""
                        SELECT scope_key, definition_version, kind, status, plan_hash, document, document_format,
                               source_type, source_uri, source_revision, source_author, loaded_at, created_at,
                               status_changed_at, publish_token
                        FROM workflow_definitions
                        WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                        """).execute(Tuple.of(scopeKey, definitionId, version)))) {
            rows.add(new Stored(
                    row.getString("scope_key"),
                    row.getLong("definition_version"),
                    row.getString("kind"),
                    row.getString("status"),
                    row.getString("plan_hash"),
                    row.getString("document"),
                    row.getString("document_format"),
                    row.getString("source_type"),
                    row.getString("source_uri"),
                    row.getString("source_revision"),
                    row.getString("source_author"),
                    row.getOffsetDateTime("loaded_at"),
                    row.getOffsetDateTime("created_at"),
                    row.getOffsetDateTime("status_changed_at"),
                    row.getUUID("publish_token")));
        }
        assertThat(rows)
                .as("rows of %s for %s v%s", scopeKey, definitionId, version)
                .hasSizeLessThanOrEqualTo(1);
        return rows.stream().findFirst();
    }

    /** Returns the one default-scope row. */
    Stored stored(String definitionId, long version) {
        return stored(WorkflowScopes.DEFAULT, definitionId, version);
    }

    /** Returns the one row of the owner. */
    Stored stored(String scopeKey, String definitionId, long version) {
        return findStored(scopeKey, definitionId, version)
                .orElseThrow(
                        () -> new AssertionError("No row of " + scopeKey + " for " + definitionId + " v" + version));
    }

    /** Counts every row of the definition id, across owners. */
    long rowCount(String definitionId) {
        return await(admin().preparedQuery(
                                "SELECT count(*) AS total FROM workflow_definitions WHERE definition_id = $1")
                        .execute(Tuple.of(definitionId)))
                .iterator()
                .next()
                .getLong("total");
    }

    @Override
    public void close() {
        AssertionError failure = null;
        while (!cleanups.isEmpty()) {
            try {
                cleanups.pop().run();
            } catch (Throwable cleanupFailure) {
                if (failure == null) failure = new AssertionError("Scenario cleanup failed", cleanupFailure);
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    /** The YAML workflow fixture, rewritten to the scenario's definition id, version and contract class. */
    static String documentText(String definitionId, long version, Class<?> contract) {
        String yaml = new String(ManagedDefinitionDatabase.yamlFixture(), StandardCharsets.UTF_8)
                .replace("definitionId: order-fulfillment-doc", "definitionId: " + definitionId)
                .replace("definitionVersion: 1", "definitionVersion: " + version)
                .replace(
                        "dev.vertique.workflow.definition.it.fixture.OrderFulfillmentDocumentWorkflowV1",
                        contract.getName());
        if (!yaml.contains("definitionId: " + definitionId + "\n")
                || !yaml.contains("definitionVersion: " + version + "\n")
                || !yaml.contains("contract: " + contract.getName() + "\n")) {
            throw new IllegalStateException("The workflow fixture did not take the scenario identity");
        }
        return yaml;
    }

    static SourceMetadata source(String label, Instant loadedAt) {
        return new SourceMetadata("digest-test", "memory:" + label, "revision-" + label, "test-suite", null, loadedAt);
    }

    /** The lowercase hexadecimal SHA-256 of the UTF-8 bytes of the text. */
    static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within ten seconds", e);
        }
    }

    /** The failure of the future, or {@code null} when it succeeded. */
    static Throwable failureOf(Future<?> future) {
        Future<Throwable> outcome =
                future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
        return await(outcome);
    }
}
