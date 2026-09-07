// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.AppComponent;
import dev.vertique.examples.workflow.order.DaggerAppComponent;
import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.command.ReleaseInventory;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import dev.vertique.examples.workflow.order.command.ScreenFraud;
import dev.vertique.examples.workflow.order.command.VoidAuthorization;
import dev.vertique.examples.workflow.order.signal.FraudScreened;
import dev.vertique.examples.workflow.order.signal.InventoryReserved;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared infrastructure for order-fulfillment saga integration tests.
 *
 * <p>Assembles the full Dagger component wired against a test Postgres container. Tests drive
 * the saga by calling {@link #driveRelay()} to process pending outbox entries synchronously
 * (without deploying Vert.x service verticles), then post signals directly via
 * {@link AppComponent#workflowOperations()}.
 *
 * <p>The relay handles both the linear saga service targets
 * ({@code inventory.reserve}, {@code payment.authorize}, {@code shipping.create-shipment}) and
 * the fan-out service targets ({@code fraud.screen}). For all dispatches the relay extracts the
 * workflow id from the {@code x-workflow-id} outbox header to route signals back. For fan-out
 * branch dispatches it also extracts {@code x-workflow-fork-step-id} and
 * {@code x-workflow-branch-id} to route via
 * {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations#signal} with branch identity.
 *
 * <p>Cycle-2 timer tests use {@link #waitForStatus(WorkflowInstanceId, WorkflowStatus, long)}
 * to poll until a workflow reaches the expected status (or a deadline is exceeded).
 */
public abstract class SagaTestBase {

    private static final Logger log = LoggerFactory.getLogger(SagaTestBase.class);

    /** Multi-module migrations: inbox-outbox tables + workflow tables. */
    static final String MIGRATIONS = "classpath:db/migration/inbox-outbox,classpath:db/migration/workflow";

    /**
     * Service target ids supported by the test relay driver.
     *
     * <p>Includes both linear-saga targets and fan-out targets. The relay only processes entries
     * whose destination is in this set; other entries are left pending.
     */
    static final Set<String> SERVICE_TARGETS = Set.of(
            "inventory.reserve",
            "inventory.release",
            "payment.authorize",
            "payment.void-authorization",
            "shipping.create-shipment",
            "fraud.screen");

    // Do NOT use withMigration here: Flyway.configure().locations(String) does NOT split on commas,
    // so a comma-joined location string is treated as a single (invalid) path and migrations are
    // silently skipped. Migrations are instead run explicitly in setUpComponent() using separate
    // FlywayMigrationRunner calls — one per module location.
    static final PostgresContainer db = new PostgresContainer().withDatabaseName("saga_it_test");

    private static final AtomicBoolean dbStarted = new AtomicBoolean(false);

    /** Fully-wired Dagger component. */
    static AppComponent component;

    /** Vert.x instance captured during {@link #setUpComponent(Vertx)} for re-entry on a duplicated context. */
    static Vertx vertxRef;

    /**
     * Builds the Dagger component wired to the test Postgres container.
     *
     * <p>Starts the Postgres container (idempotent if already started), then constructs the Dagger
     * component and runs Flyway migrations.
     *
     * @param vertx the Vert.x instance from the extension
     * @return a Future that completes once the component is ready and migrations have run
     */
    protected static Future<Void> setUpComponent(Vertx vertx) {
        vertxRef = vertx;
        // Start the container and run migrations once across all test classes.
        if (dbStarted.compareAndSet(false, true)) {
            db.start();
            // Run inbox-outbox and workflow migrations separately to avoid Flyway's duplicate-version
            // rejection: both modules have V1__*.sql, so they cannot share a single Flyway history
            // table. We use two separate Flyway runs with separate history table names.
            runMigration(
                    db.jdbcUrl(),
                    db.toPoolConfig().user(),
                    db.toPoolConfig().password(),
                    "classpath:db/migration/inbox-outbox",
                    "flyway_schema_history_io");
            runMigration(
                    db.jdbcUrl(),
                    db.toPoolConfig().user(),
                    db.toPoolConfig().password(),
                    "classpath:db/migration/workflow",
                    "flyway_schema_history_wf");
            runMigration(
                    db.jdbcUrl(),
                    db.toPoolConfig().user(),
                    db.toPoolConfig().password(),
                    "classpath:db/migration/job",
                    "flyway_schema_history_job");
        }
        var dbCfg = db.toPoolConfig();
        var config = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", dbCfg.host())
                                .put("port", dbCfg.port())
                                .put("database", dbCfg.database())
                                .put("user", dbCfg.user())
                                .put("password", dbCfg.password())
                                .put("maxPoolSize", 10))
                .put(
                        "flyway",
                        new JsonObject()
                                // Use DISABLED mode since the PostgresContainer already ran both
                                // migrations via .withMigration(MIGRATIONS) during container startup
                                // using FlywayContainerMigrationRunner. Running MIGRATE or VALIDATE
                                // via FlywayMigrationRunner would fail with "Found more than one
                                // migration with version 1" because inbox-outbox and workflow both
                                // have V1__*.sql. The tables already exist and are correct.
                                .put("mode", "DISABLED")
                                .put("jdbcUrl", db.jdbcUrl())
                                .put("user", dbCfg.user())
                                .put("password", dbCfg.password()))
                .put(
                        "inboxOutbox",
                        new JsonObject()
                                .put(
                                        "relay",
                                        new JsonObject()
                                                .put("pollingIntervalMs", 500)
                                                .put("batchSize", 10)
                                                .put("leaseTimeoutMs", 10000)
                                                .put("maxAttempts", 5)
                                                .put("backoffBaseDelayMs", 500)
                                                .put("backoffMaxDelayMs", 30000)
                                                .put("strategy", "POLLING")
                                                .put("instances", 1))
                                .put(
                                        "cleanup",
                                        new JsonObject()
                                                .put("publishedRetentionMs", 3600000)
                                                .put("deadLetterRetentionMs", 86400000)
                                                .put("inboxRetentionMs", 3600000)
                                                .put("cleanupIntervalMs", 300000)))
                .put(
                        "services",
                        new JsonObject()
                                .put("inventory", new JsonObject().put("instances", 1))
                                .put("payment", new JsonObject().put("instances", 1))
                                .put("shipping", new JsonObject().put("instances", 1))
                                .put("fraud", new JsonObject().put("instances", 1))
                                .put("workflow", new JsonObject().put("signals", new JsonObject().put("instances", 1))))
                .put("management", new JsonObject().put("port", 0).put("host", "127.0.0.1"));

        component = DaggerAppComponent.builder()
                .vertxModule(new VertxModule(vertx, config))
                .build();

        // The production AppComponent is now a @VertiqueApp VertiqueApplicationComponent: its
        // CONFIGURE/VALIDATE/MIGRATE choreography lives in framework lifecycle steps, not in
        // hand-written accessors. This harness deliberately does NOT deploy the application's
        // verticles (it drives the outbox relay synchronously via driveRelay()), and it runs its
        // own multi-history-table Flyway migrations above — the framework's single-history-table
        // FlywayMigrationRunner cannot express those, so flyway.mode=DISABLED keeps the MIGRATE
        // step a no-op. We therefore run only the non-verticle startup steps directly, in
        // lifecycle order, which reproduces the former choreography exactly:
        //   • CONFIGURE → nothing in this graph (no JSON runtime module, so no process-codec install)
        //   • VALIDATE  → ComposeValidationStep (forces construction of the three workflow
        //                 ComposeValidators: §3.11 outbox, cycle-2 timer, cycle-3 task)
        //   • MIGRATE   → FlywayMigrationStartupStep (no-op under flyway.mode=DISABLED)
        return runNonVerticleStartupSteps(component);
    }

    /**
     * Runs the component's pre-verticle startup steps (CONFIGURE, VALIDATE, MIGRATE phases) in
     * lifecycle order, skipping both the verticle-deployment phases and the post-start
     * {@code AFTER_START} phase the saga harness does not use.
     *
     * <p>The filter selects phases strictly before {@link dev.vertique.core.lifecycle.LifecyclePhase#BOOTSTRAP}
     * (the first verticle phase). A {@code !isVerticlePhase()} filter would be too loose: it also
     * admits {@code AFTER_START}, a non-verticle phase that runs <em>after</em> verticles — which
     * this harness deliberately does not deploy.
     *
     * @param component the fully-wired application component
     * @return a Future that completes once the pre-verticle startup steps have run
     */
    private static Future<Void> runNonVerticleStartupSteps(AppComponent component) {
        List<ApplicationStartupStep> steps = component.startupSteps().stream()
                .filter(step -> step.phase().ordinal() < dev.vertique.core.lifecycle.LifecyclePhase.BOOTSTRAP.ordinal())
                .sorted(LifecycleOrdered.comparator())
                .toList();
        Future<Void> chain = Future.succeededFuture();
        for (ApplicationStartupStep step : steps) {
            chain = chain.compose(v -> step.start());
        }
        return chain;
    }

    /** Clears all workflow, outbox, inbox, and job tables between tests. */
    protected static Future<Void> truncateTables() {
        return component
                .pgInboxOutboxRepository()
                .pool()
                .query("TRUNCATE TABLE job_executions, job_logs, job_server_heartbeats,"
                        + " workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances,"
                        + " outbox, inbox RESTART IDENTITY CASCADE")
                .execute()
                .mapEmpty();
    }

    /**
     * Polls the workflow status until it reaches {@code expected} or the deadline (in milliseconds)
     * elapses. Uses 100 ms poll intervals.
     *
     * @param id              the workflow instance to poll
     * @param expected        the target status to wait for
     * @param deadlineMs      maximum time to wait in milliseconds
     * @return a {@link Future} that completes when the status matches, or fails if the deadline
     *         elapses without a match
     */
    protected static Future<Void> waitForStatus(WorkflowInstanceId id, WorkflowStatus expected, long deadlineMs) {
        long start = System.currentTimeMillis();
        return pollStatus(id, expected, start, deadlineMs);
    }

    private static Future<Void> pollStatus(
            WorkflowInstanceId id, WorkflowStatus expected, long start, long deadlineMs) {
        return component.workflowOperations().query(id).compose(view -> {
            if (expected.equals(view.instance().status())) {
                return Future.succeededFuture();
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= deadlineMs) {
                return Future.failedFuture(new AssertionError("Timed out after " + elapsed + " ms waiting for status "
                        + expected + "; actual=" + view.instance().status()));
            }
            return Future.<Void>future(p -> {
                        // delay 100 ms then retry — uses Vert.x timer on calling context's event loop
                        component
                                .pgInboxOutboxRepository()
                                .pool()
                                .query("SELECT pg_sleep(0.1)")
                                .execute()
                                .onComplete(ar -> p.complete());
                    })
                    .compose(v -> pollStatus(id, expected, start, deadlineMs));
        });
    }

    /**
     * Starts a test order-fulfillment saga (linear definition).
     *
     * @param orderId unique order identifier for this test run
     * @return Future resolving to the workflow instance id
     */
    protected static Future<WorkflowInstanceId> startOrder(String orderId) {
        PlaceOrder cmd =
                new PlaceOrder(orderId, "cust-" + orderId, List.of(new PlaceOrder.OrderItem("SKU-1", 1, 500L)), 500L);
        return component
                .workflowOperations()
                .start(new StartCommand("order-fulfillment", cmd, cmd.idempotencyKey(), null, null));
    }

    /**
     * Starts a test fan-out order-fulfillment saga.
     *
     * @param orderId unique order identifier for this test run
     * @return Future resolving to the workflow instance id
     */
    protected static Future<WorkflowInstanceId> startFanOutOrder(String orderId) {
        PlaceOrder cmd =
                new PlaceOrder(orderId, "cust-" + orderId, List.of(new PlaceOrder.OrderItem("SKU-1", 1, 500L)), 500L);
        return component
                .workflowOperations()
                .start(new StartCommand("order-fulfillment-fanout", cmd, cmd.idempotencyKey(), null, null));
    }

    /**
     * Re-enters the given async action on a freshly-duplicated Vert.x context so the substrate's
     * holder write guard (FR-CTX-021 / FR-CTX-157b) accepts any bindFrom / holder.bind calls that
     * land inside the action's chain.
     *
     * <p>Production dispatch (event-bus, Kafka per-record, cron, service-method invoker) always
     * reaches the engine via a duplicated context; this helper provides the same isolation for ITs
     * that drive the engine directly from a JUnit thread (no Vert.x context). Fan-out branch
     * drives reach {@code DurableContextPropagator.bindFrom} per FR-CTX-157b and would otherwise
     * throw on the non-duplicated context.
     *
     * @param action a supplier of a Future to run on the duplicated context
     * @param <T>    the future's value type
     * @return a Future that completes with the action's result, anchored to the duplicated context
     */
    protected static <T> Future<T> runOnDuplicatedContext(java.util.function.Supplier<Future<T>> action) {
        io.vertx.core.internal.ContextInternal dup =
                ((io.vertx.core.internal.ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        io.vertx.core.Promise<T> promise = dup.promise();
        dup.runOnContext(unused -> action.get().onComplete(promise));
        return promise.future();
    }

    /**
     * Drives one relay cycle synchronously.
     *
     * <p>Claims up to 50 pending SERVICE outbox entries, dispatches each to the appropriate stub
     * service, and posts the corresponding signal back to the workflow engine (unless in failure
     * mode). Does NOT deploy any Vert.x verticles — all dispatch is in-process.
     *
     * <p>For all dispatches the workflow id is read from the {@code x-workflow-id} outbox header
     * so routing is not affected by the branch-state propagation limitation (PRD-WF-002
     * §NOT-IMPLEMENTED: branch payload factories see an empty state rather than the parent
     * instance state, making state-derived fields like orderId unavailable at dispatch time).
     * For fan-out branch dispatches, {@code x-workflow-fork-step-id} and
     * {@code x-workflow-branch-id} headers are additionally extracted and forwarded to the
     * branch-aware {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations#signal} overload.
     *
     * <p>Records are processed sequentially to avoid optimistic-concurrency conflicts when
     * multiple branch signals each attempt to update the parent workflow instance state
     * concurrently.
     *
     * @return a Future that completes after all pending entries are processed
     */
    protected static Future<Void> driveRelay() {
        var repo = component.pgInboxOutboxRepository();
        var capabilities =
                new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> SERVICE_TARGETS)));

        return repo.claimBatch(50, "test-relay", capabilities).compose(records -> {
            if (records.isEmpty()) {
                return Future.succeededFuture();
            }
            // Process records sequentially to avoid optimistic-concurrency conflicts when
            // multiple branch signals each try to update the parent workflow instance in
            // separate concurrent transactions.
            Future<Void> chain = Future.succeededFuture();
            for (var record : records) {
                chain = chain.compose(v -> dispatchRecord(record));
            }
            return chain;
        });
    }

    private static Future<Void> dispatchRecord(OutboxRecord record) {
        String dest = record.destination();
        String payload = Json.encode(record.payload());
        var repo = component.pgInboxOutboxRepository();
        var scenario = component.stubScenario();
        var inv = component.stubInventoryService();
        var pay = component.stubPaymentService();
        var ship = component.stubShippingService();
        var fraud = component.stubFraudService();

        // Extract branch identity from outbox headers (present when dispatched from a branch).
        String forkStepId = record.headers().get("x-workflow-fork-step-id");
        String branchId = record.headers().get("x-workflow-branch-id");

        // Always use the workflow id from the outbox header: this avoids the branch-state
        // propagation limitation (PRD-WF-002: branch payload factories see empty state so
        // orderId is null in the dispatch command). The x-workflow-id header is set by
        // OutboxSideEffectRecorder for every workflow service dispatch.
        WorkflowInstanceId instanceId = instanceIdFromHeader(record);

        Future<Void> serviceCall;
        if ("inventory.reserve".equals(dest)) {
            var cmd = Json.decodeValue(payload, ReserveInventory.class);
            serviceCall = inv.reserve(cmd).compose(v -> {
                if (!scenario.inventoryShouldFail()) {
                    String orderId = record.aggregateId();
                    var sig = new InventoryReserved(orderId, "res-" + orderId);
                    return signalWorkflow(instanceId, "inventory.reserved", sig, sig.dedupKey(), forkStepId, branchId);
                }
                return Future.succeededFuture();
            });
        } else if ("inventory.release".equals(dest)) {
            var cmd = Json.decodeValue(payload, ReleaseInventory.class);
            serviceCall = inv.release(cmd);
        } else if ("payment.authorize".equals(dest)) {
            var cmd = Json.decodeValue(payload, AuthorizePayment.class);
            serviceCall = pay.authorize(cmd).compose(v -> {
                if (!scenario.paymentShouldFail()) {
                    String orderId = record.aggregateId();
                    var sig = new PaymentCaptured(orderId, "chg-" + orderId);
                    return signalWorkflow(instanceId, "payment.captured", sig, sig.dedupKey(), forkStepId, branchId);
                }
                return Future.succeededFuture();
            });
        } else if ("payment.void-authorization".equals(dest)) {
            var cmd = Json.decodeValue(payload, VoidAuthorization.class);
            serviceCall = pay.voidAuthorization(cmd);
        } else if ("shipping.create-shipment".equals(dest)) {
            var cmd = Json.decodeValue(payload, CreateShipment.class);
            serviceCall = ship.createShipment(cmd).compose(v -> {
                String orderId = record.aggregateId();
                var sig = new ShipmentCreated(orderId, "trk-" + orderId);
                return signalWorkflow(instanceId, "shipment.created", sig, sig.dedupKey(), forkStepId, branchId);
            });
        } else if ("fraud.screen".equals(dest)) {
            var cmd = Json.decodeValue(payload, ScreenFraud.class);
            serviceCall = fraud.screen(cmd).compose(v -> {
                String orderId = record.aggregateId();
                var sig = new FraudScreened(orderId, "scr-" + orderId);
                return signalWorkflow(instanceId, "fraud.screened", sig, sig.dedupKey(), forkStepId, branchId);
            });
        } else {
            serviceCall = Future.failedFuture(new IllegalArgumentException("Unknown dest: " + dest));
        }

        return serviceCall
                .compose(
                        v -> repo.markPublished(record.id(), record.claimedBy()).<Void>mapEmpty())
                .recover(err -> repo.markRetry(
                                record.id(),
                                record.claimedBy(),
                                record.attempt() + 1,
                                java.time.Instant.now().plusSeconds(5),
                                err.getMessage(),
                                err.getClass().getName())
                        .<Void>mapEmpty());
    }

    /**
     * Extracts the workflow instance id from the {@code x-workflow-id} outbox header.
     *
     * <p>Using the header directly avoids the need to look up the instance via the
     * {@code workflow_dedup} table, and also avoids the branch-state propagation limitation
     * where orderId fields in dispatch commands are null.
     *
     * @param record the outbox record
     * @return the workflow instance id
     */
    private static WorkflowInstanceId instanceIdFromHeader(OutboxRecord record) {
        String workflowIdStr = record.headers().get("x-workflow-id");
        if (workflowIdStr == null) {
            // Fall back to aggregate id if the header is absent (should not happen for
            // workflow service dispatches but provides a safety net).
            return new WorkflowInstanceId(UUID.fromString(record.aggregateId()));
        }
        return new WorkflowInstanceId(UUID.fromString(workflowIdStr));
    }

    /**
     * Delivers a signal to the workflow engine, routing to a specific branch when branch identity
     * headers are present in the outbox record.
     *
     * <p>When {@code forkStepId} and {@code branchId} are both non-null (set by the outbox
     * recorder for branch dispatches), the branch-aware
     * {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations#signal} overload is used so
     * the engine resumes the correct branch token. When both are null the instance-level
     * {@link dev.vertique.workflow.ops.WorkflowOperations#signal} overload is used.
     *
     * @param id         workflow instance id
     * @param signalName signal name
     * @param payload    signal payload
     * @param dedupKey   dedup key for the signal
     * @param forkStepId fork step id from outbox headers; null for non-branch dispatches
     * @param branchId   branch id from outbox headers; null for non-branch dispatches
     * @return a Future that completes when the signal has been applied
     */
    protected static Future<Void> signalWorkflow(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String dedupKey,
            String forkStepId,
            String branchId) {
        if (forkStepId != null && branchId != null) {
            // Branch-targeted signal: open a transaction and use the branch-aware overload.
            return component
                    .pgInboxOutboxRepository()
                    .pool()
                    .withTransaction(tx -> component
                            .txWorkflowOperations()
                            .signal(id, signalName, payload, dedupKey, forkStepId, branchId, tx))
                    .onFailure(err -> log.error(
                            "Branch signal failed: signal={} fork={} branch={} cause={}",
                            signalName,
                            forkStepId,
                            branchId,
                            err.getMessage(),
                            err));
        }
        return component
                .workflowOperations()
                .signal(id, signalName, payload, dedupKey)
                .onFailure(err ->
                        log.error("Instance signal failed: signal={} cause={}", signalName, err.getMessage(), err));
    }

    /**
     * Resolves a workflow instance id from the dedup table using the order-id-derived idempotency
     * key.
     *
     * @param orderId the order identifier
     * @return a Future resolving to the workflow instance id
     */
    protected static Future<WorkflowInstanceId> findInstanceId(String orderId) {
        return component
                .pgInboxOutboxRepository()
                .pool()
                .preparedQuery("SELECT workflow_id FROM workflow_dedup WHERE kind='start' AND key=$1")
                .execute(Tuple.of("order-" + orderId))
                .compose(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return Future.failedFuture(
                                new IllegalStateException("No workflow found for order: " + orderId));
                    }
                    return Future.succeededFuture(
                            new WorkflowInstanceId(rows.iterator().next().getUUID("workflow_id")));
                });
    }

    /**
     * Runs a Flyway migration against the test container using a module-specific history table.
     *
     * <p>Each module has its own Flyway history table ({@code flyway_schema_history_io} for
     * inbox-outbox, {@code flyway_schema_history_wf} for workflow) to avoid version conflicts:
     * both modules start their version numbering at {@code V1}.
     *
     * @param jdbcUrl          JDBC URL for the test database
     * @param user             database user
     * @param password         database password
     * @param location         classpath location of the migrations
     * @param historyTableName name of the Flyway schema history table to use
     */
    private static void runMigration(
            String jdbcUrl, String user, String password, String location, String historyTableName) {
        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(location)
                .table(historyTableName)
                // baselineOnMigrate=true is required when the public schema already exists
                // (PostgreSQL creates it by default); without it Flyway rejects a fresh DB as
                // "non-empty schema without history table".
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
