// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.query.WorkflowInstanceQueryService;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgWorkflowInstanceQueryService} against a real PostgreSQL instance.
 *
 * <p>Verifies the cycle-5 public query surface:
 * <ul>
 *   <li>{@code list(...)} narrows by subject ref (type+id+version), subject type only, definition
 *       id, and respects the {@code includeArchived} flag (cycle-4 retention behavior preserved
 *       through the new public surface).</li>
 *   <li>{@code getById(...)} returns a populated {@link Optional} for known ids and an empty
 *       {@link Optional} for unknown ids.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowInstanceQueryServiceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_query_service")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowInstanceQueryService service;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        PgWorkflowInstanceRepository repository = new PgWorkflowInstanceRepository(pool, new PgDbExceptionMapper());
        service = new PgWorkflowInstanceQueryService(repository);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    private static WorkflowInstance instanceWithSubject(WorkflowInstanceId id, WorkflowSubjectRef subjectRef) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                subjectRef,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
    }

    /** Inserts a workflow instance with the given subject ref. */
    private Future<Void> insert(WorkflowInstance inst) {
        PgWorkflowInstanceRepository repository = new PgWorkflowInstanceRepository(pool, new PgDbExceptionMapper());
        return pool.withTransaction(tx -> repository.insert(inst, tx));
    }

    /** Inserts a minimal {@code workflow_instances} row with an explicit {@code archived_at}. */
    private Future<Void> insertArchivedRow(UUID id, String definitionId, Instant archivedAt) {
        String sql = "INSERT INTO workflow_instances"
                + " (id, definition_id, definition_version, plan_hash, version, status,"
                + " current_step_id, state_json, completed_at, archived_at)"
                + " VALUES ($1, $2, 1, 'hash', 0, 'COMPLETED', 'done', '{}', $3, $4)";
        Instant completedAt = archivedAt.minusSeconds(60);
        return pool.preparedQuery(sql)
                .execute(Tuple.of(
                        id, definitionId, completedAt.atOffset(ZoneOffset.UTC), archivedAt.atOffset(ZoneOffset.UTC)))
                .<Void>mapEmpty();
    }

    // =========================================================================
    // list — subject ref filtering (FR-WF-123)
    // =========================================================================

    @Test
    @DisplayName("list by subject (type+id+version) returns exactly the matching instance")
    void listBySubjectExactMatch(VertxTestContext ctx) {
        WorkflowInstanceId art1v3 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId art1v4 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId art2v3 = new WorkflowInstanceId(UUID.randomUUID());

        insert(instanceWithSubject(art1v3, new WorkflowSubjectRef("Article", "art-1", "v3")))
                .compose(v -> insert(instanceWithSubject(art1v4, new WorkflowSubjectRef("Article", "art-1", "v4"))))
                .compose(v -> insert(instanceWithSubject(art2v3, new WorkflowSubjectRef("Article", "art-2", "v3"))))
                .compose(v -> service.list(
                        WorkflowInstanceQuery.bySubject(new WorkflowSubjectRef("Article", "art-1", "v3")),
                        PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(1, result.items().size(), "subject (Article, art-1, v3) must match exactly one row");
                    assertEquals(art1v3, result.items().get(0).id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("list by subject type only returns every instance with that type")
    void listBySubjectTypeReturnsAllOfType(VertxTestContext ctx) {
        WorkflowInstanceId art1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId art2 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId orderRow = new WorkflowInstanceId(UUID.randomUUID());

        insert(instanceWithSubject(art1, new WorkflowSubjectRef("Article", "art-1", "v3")))
                .compose(v -> insert(instanceWithSubject(art2, new WorkflowSubjectRef("Article", "art-2", "v1"))))
                .compose(v -> insert(instanceWithSubject(orderRow, new WorkflowSubjectRef("Order", "order-1", null))))
                .compose(v -> service.list(WorkflowInstanceQuery.bySubjectType("Article"), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(2, result.items().size(), "subjectType=Article must match the two Article instances");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // list — definition id and getById
    // =========================================================================

    @Test
    @DisplayName("list by definition id narrows correctly")
    void listByDefinitionIdNarrows(VertxTestContext ctx) {
        WorkflowInstanceId orderInst = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId otherDefInst = new WorkflowInstanceId(UUID.randomUUID());

        insert(instanceWithSubject(orderInst, new WorkflowSubjectRef("Order", "o1", null)))
                .compose(v -> {
                    Instant now = Instant.now();
                    WorkflowInstance other = new WorkflowInstance(
                            otherDefInst,
                            "other-definition",
                            1L,
                            "hash",
                            0L,
                            WorkflowStatus.RUNNING,
                            null,
                            null,
                            "start",
                            null,
                            null,
                            null,
                            "{}",
                            null,
                            null,
                            now,
                            now,
                            null);
                    return insert(other);
                })
                .compose(v ->
                        service.list(WorkflowInstanceQuery.byDefinitionId("order-fulfillment"), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(1, result.items().size(), "definition filter must narrow to one row");
                    assertEquals(orderInst, result.items().get(0).id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("getById returns the populated instance for a known id")
    void getByIdHit(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());

        insert(instanceWithSubject(id, new WorkflowSubjectRef("Article", "art-x", "v1")))
                .compose(v -> service.getById(id))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "known id must return Optional.of");
                    assertEquals(id, opt.get().id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("getById returns Optional.empty for an unknown id")
    void getByIdMiss(VertxTestContext ctx) {
        WorkflowInstanceId unknown = new WorkflowInstanceId(UUID.randomUUID());

        service.getById(unknown)
                .onSuccess(opt -> ctx.verify(() -> {
                    assertFalse(opt.isPresent(), "unknown id must return Optional.empty");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // includeArchived — cycle-4 retention behavior preserved
    // =========================================================================

    @Test
    @DisplayName("list excludes archived rows by default")
    void listExcludesArchivedByDefault(VertxTestContext ctx) {
        WorkflowInstanceId liveId = new WorkflowInstanceId(UUID.randomUUID());
        UUID archivedId = UUID.randomUUID();

        insert(instanceWithSubject(liveId, new WorkflowSubjectRef("Article", "art-live", null)))
                .compose(v -> insertArchivedRow(archivedId, "order-fulfillment", Instant.now()))
                .compose(v -> service.list(WorkflowInstanceQuery.none(), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(1, result.items().size(), "default query must exclude archived rows");
                    assertEquals(liveId, result.items().get(0).id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("list includes archived rows when includeArchived=true")
    void listIncludesArchivedWhenFlagSet(VertxTestContext ctx) {
        WorkflowInstanceId liveId = new WorkflowInstanceId(UUID.randomUUID());
        UUID archivedId = UUID.randomUUID();

        insert(instanceWithSubject(liveId, new WorkflowSubjectRef("Article", "art-live", null)))
                .compose(v -> insertArchivedRow(archivedId, "order-fulfillment", Instant.now()))
                .compose(
                        v -> service.list(WorkflowInstanceQuery.none().withIncludeArchived(true), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    PagedResult<WorkflowInstance> r = result;
                    assertEquals(2, r.items().size(), "withIncludeArchived(true) must include archived rows");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
