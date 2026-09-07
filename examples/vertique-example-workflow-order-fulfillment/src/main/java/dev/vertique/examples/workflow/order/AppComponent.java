// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.DbModule;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.workflow.order.di.AppModule;
import dev.vertique.examples.workflow.order.di.OrderFulfillmentDefinitionModule;
import dev.vertique.examples.workflow.order.di.OrderFulfillmentFanOutDefinitionModule;
import dev.vertique.examples.workflow.order.di.StubServicesModule;
import dev.vertique.examples.workflow.order.di.TaskReviewDefinitionModule;
import dev.vertique.examples.workflow.order.di.TimerDefinitionModule;
import dev.vertique.examples.workflow.order.service.StubFraudService;
import dev.vertique.examples.workflow.order.service.StubInventoryService;
import dev.vertique.examples.workflow.order.service.StubPaymentService;
import dev.vertique.examples.workflow.order.service.StubScenario;
import dev.vertique.examples.workflow.order.service.StubShippingService;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.job.delayed.dagger.DelayedJobModule;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.management.ManagementModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.delayed.compose.WorkflowDelayedComposeValidator;
import dev.vertique.workflow.delayed.di.WorkflowDelayedModule;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireExecutor;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryModule;
import dev.vertique.workflow.services.compose.WorkflowOutboxComposeValidator;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.tasks.TaskService;
import dev.vertique.workflow.tasks.compose.WorkflowTasksComposeValidator;
import dev.vertique.workflow.tasks.di.WorkflowTasksModule;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Root Dagger component for the order-fulfillment example application.
 *
 * <p>The component is annotated {@link VertiqueApp} and {@code extends}
 * {@link VertiqueApplicationComponent}, so the framework's {@code vertique-codegen-application}
 * annotation processor generates {@code AppComponentVertiqueComponentFactory} (plus its
 * {@code META-INF/services} registration) and the host-neutral lifecycle runner
 * ({@code VertiqueApplicationBootstrap}) drives startup and shutdown — there is no hand-written
 * {@code MainVerticle}. The inherited {@code startupSteps()}/{@code shutdownSteps()}/
 * {@code verticleDeploymentManager()} accessors expose the lifecycle inputs the runner consumes.
 *
 * <p>The runner reproduces the former {@code MainVerticle} choreography exactly through lifecycle
 * phases: this graph includes no JSON runtime module, so no {@code CONFIGURE}-phase step installs a
 * JSON profile as the process codec and JSON runs on Vert.x's raw semantics; the {@code VALIDATE}-phase
 * {@code ComposeValidationStep} forces construction of every {@code ComposeValidator} — including
 * the {@link WorkflowOutboxComposeValidator} (contributed by {@link WorkflowServicesModule}),
 * the {@link WorkflowDelayedComposeValidator} (contributed by {@link WorkflowDelayedModule}), and
 * the {@link WorkflowTasksComposeValidator} (contributed by {@link WorkflowTasksModule}), so the
 * three §3.11/cycle-2/cycle-3 fail-fast startup contracts still run without any hand-written call;
 * the {@code MIGRATE}-phase {@code FlywayMigrationStartupStep} contributed by {@link DbFlywayModule}
 * runs schema migrations; then the verticles deploy ({@code INFRA} management verticle, then
 * {@code SERVICES} stub services + signal contributor + timer pollers + outbox relay, the latter
 * brought up by the {@code ServiceDeploymentStartupStep} contributed by {@link DispatchModule}).
 *
 * <p>This is a <strong>non-HTTP</strong> example — it deploys no HTTP/EDGE verticle.
 *
 * <p>Wires the full graph required for the durable saga pattern:
 * <ul>
 *   <li>{@link VertxModule} — Vert.x instance and configuration.</li>
 *   <li>{@link DeployerModule} — verticle deployment multibinding and the
 *       {@code Set<ApplicationStartupStep>}/{@code Set<ApplicationShutdownStep>} multibindings the
 *       runner consumes.</li>
 *   <li>{@link CoreLifecycleStepsModule} — the framework's {@code VALIDATE} lifecycle step (the
 *       compose-validator harness).</li>
 *   <li>{@link ManagementModule} — health check endpoints on management port.</li>
 *   <li>{@link DbPostgresqlModule} — PostgreSQL connection pool.</li>
 *   <li>{@link DbFlywayModule} — Flyway migration runner and its {@code MIGRATE}-phase startup
 *       step.</li>
 *   <li>{@link DispatchModule} — event bus service dispatch infrastructure and the
 *       {@code SERVICES}-phase service-deploy startup step.</li>
 *   <li>{@link TransactionalMessagingPostgresqlModule} — outbox/inbox infrastructure and relay.</li>
 *   <li>{@link TransactionalMessagingServiceModule} — SERVICE {@code OutboxDestinationHandler}
 *       (required by the {@link WorkflowOutboxComposeValidator} startup contract).</li>
 *   <li>{@link WorkflowPostgresqlModule} — {@code PgWorkflowEngine}, repositories, and recorder
 *       multibinding.</li>
 *   <li>{@link WorkflowServicesModule} — outbox recorder + signal contributor + compose
 *       validator.</li>
 *   <li>{@link WorkflowDelayedModule} — timer recorder, fire-job executor, and recovery
 *       verticle; requires {@link DelayedJobModule} as a peer module in the component.</li>
 *   <li>{@link DelayedJobModule} — {@link dev.vertique.job.delayed.DelayedJobService},
 *       {@link dev.vertique.job.delayed.DelayedJobClientFactory}, poller deployments, and the
 *       {@link dev.vertique.job.JobRepository} binding (via included
 *       {@link dev.vertique.job.postgresql.JobPostgresqlModule}). Required by
 *       {@link WorkflowDelayedModule}.</li>
 *   <li>{@link OrderFulfillmentDefinitionModule} — linear order-fulfillment workflow definition
 *       (cycle 1 saga).</li>
 *   <li>{@link OrderFulfillmentFanOutDefinitionModule} — ALL_REQUIRED fan-out order-fulfillment
 *       workflow definition (PRD-WF-002 §A.5.1).</li>
 *   <li>{@link TimerDefinitionModule} — standalone-timer and signal-with-timeout definitions
 *       used by cycle-2 timer ITs.</li>
 *   <li>{@link WorkflowTasksModule} — {@link TaskService},
 *       {@link dev.vertique.workflow.tasks.TransactionalTaskService}{@code <SqlClient>}, and the
 *       {@link WorkflowTasksComposeValidator} startup contract. Requires
 *       {@link WorkflowPostgresqlModule} (already included) for the
 *       {@link dev.vertique.workflow.tasks.TaskStore} and
 *       {@link dev.vertique.workflow.ops.TransactionalTaskCallbacks} bindings.</li>
 *   <li>{@link TaskReviewDefinitionModule} — task-review workflow definition used by the
 *       cycle-3 human-task IT.</li>
 *   <li>{@link StubServicesModule} — stub inventory, payment, shipping, and fraud-screening
 *       service implementations for integration tests.</li>
 *   <li>{@link AppModule} — management verticle deployment.</li>
 *   <li>{@link GeneratedWorkflowClientsModule} — APT-generated module that provides
 *       {@link OrderFulfillmentWorkflow} and {@link OrderFulfillmentFanOutWorkflow} bindings via
 *       {@code WorkflowClientFactory.create(…)} (ADR-0025 explicit-inclusion model).</li>
 * </ul>
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            ManagementModule.class,
            DbModule.class,
            DbPostgresqlModule.class,
            DbFlywayModule.class,
            DispatchModule.class,
            TransactionalMessagingPostgresqlModule.class,
            TransactionalMessagingServiceModule.class,
            WorkflowPostgresqlModule.class,
            WorkflowBranchRecoveryModule.class,
            WorkflowServicesModule.class,
            WorkflowDelayedModule.class,
            DelayedJobModule.class,
            CronPersistenceModule.class,
            OrderFulfillmentDefinitionModule.class,
            OrderFulfillmentFanOutDefinitionModule.class,
            TimerDefinitionModule.class,
            WorkflowTasksModule.class,
            TaskReviewDefinitionModule.class,
            StubServicesModule.class,
            AppModule.class,
            GeneratedWorkflowClientsModule.class
        })
public interface AppComponent extends VertiqueApplicationComponent {

    /**
     * Returns the workflow operations facade.
     *
     * @return workflow operations
     */
    WorkflowOperations workflowOperations();

    /**
     * Returns the transactional workflow operations for branch-aware signal routing.
     *
     * <p>Used by integration-test relay drivers to deliver branch-targeted signals using the
     * {@link TransactionalWorkflowOperations#signal(dev.vertique.workflow.ops.WorkflowInstanceId,
     * String, Object, String, String, String, Object)} overload that accepts
     * {@code forkStepId} and {@code branchId} (PRD-WF-002 §D11).
     *
     * @return transactional workflow operations backed by {@link SqlClient}
     */
    TransactionalWorkflowOperations<SqlClient> txWorkflowOperations();

    /**
     * Returns the workflow client factory for creating typed proxies.
     *
     * @return workflow client factory
     */
    WorkflowClientFactory workflowClientFactory();

    /**
     * Returns the Dagger-provided {@link OrderFulfillmentWorkflow} typed proxy.
     *
     * <p>The binding is supplied by {@link GeneratedWorkflowClientsModule}, which delegates to
     * {@code WorkflowClientFactory.create(OrderFulfillmentWorkflow.class)}. At runtime the factory
     * selects the APT-generated {@code OrderFulfillmentWorkflow_WorkflowClientProxy}.
     *
     * @return the order-fulfillment workflow client proxy; never {@code null}
     */
    OrderFulfillmentWorkflow orderFulfillmentWorkflow();

    /**
     * Returns the Dagger-provided {@link OrderFulfillmentFanOutWorkflow} typed proxy.
     *
     * <p>The binding is supplied by {@link GeneratedWorkflowClientsModule}, which delegates to
     * {@code WorkflowClientFactory.create(OrderFulfillmentFanOutWorkflow.class)}. At runtime the
     * factory selects the APT-generated
     * {@code OrderFulfillmentFanOutWorkflow_WorkflowClientProxy}.
     *
     * @return the fan-out order-fulfillment workflow client proxy; never {@code null}
     */
    OrderFulfillmentFanOutWorkflow orderFulfillmentFanOutWorkflow();

    /**
     * Returns the developer-facing task service for completing and reassigning human tasks.
     *
     * @return the task service
     */
    TaskService taskService();

    /**
     * Returns the outbox service for writing outbox entries within a transaction.
     *
     * @return the outbox service
     */
    OutboxService outboxService();

    /**
     * Returns the inbox service for exactly-once processing.
     *
     * @return the inbox service
     */
    InboxService inboxService();

    /**
     * Returns the raw inbox/outbox repository for test relay driving.
     *
     * @return the PostgreSQL inbox/outbox repository
     */
    PgInboxOutboxRepository pgInboxOutboxRepository();

    /**
     * Returns the stub scenario toggle for controlling test behavior.
     *
     * @return the stub scenario instance
     */
    StubScenario stubScenario();

    /**
     * Returns the stub inventory service for test assertions.
     *
     * @return the stub inventory service
     */
    StubInventoryService stubInventoryService();

    /**
     * Returns the stub payment service for test assertions.
     *
     * @return the stub payment service
     */
    StubPaymentService stubPaymentService();

    /**
     * Returns the stub shipping service for test assertions.
     *
     * @return the stub shipping service
     */
    StubShippingService stubShippingService();

    /**
     * Returns the stub fraud-screening service for test assertions.
     *
     * @return the stub fraud-screening service
     */
    StubFraudService stubFraudService();

    /**
     * Returns the job repository for test-level job inspection and claiming.
     *
     * @return the job repository
     */
    JobRepository jobRepository();

    /**
     * Returns the PostgreSQL job repository for test-level claiming by queue.
     *
     * @return the PostgreSQL job repository
     */
    PgJobRepository pgJobRepository();

    /**
     * Returns the workflow timer fire executor for manual test-driving of timer jobs.
     *
     * @return the timer fire executor
     */
    WorkflowTimerFireExecutor workflowTimerFireExecutor();
}
