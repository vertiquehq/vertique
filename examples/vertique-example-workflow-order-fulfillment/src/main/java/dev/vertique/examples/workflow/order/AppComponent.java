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
import dev.vertique.deploy.VerticleDeployment;
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
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.services.compose.WorkflowOutboxComposeValidator;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.tasks.TaskService;
import dev.vertique.workflow.tasks.compose.WorkflowTasksComposeValidator;
import dev.vertique.workflow.tasks.di.WorkflowTasksModule;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.util.Set;

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
 * the {@link WorkflowTasksComposeValidator} (contributed by {@link WorkflowTasksModule}) — so that
 * fail-fast startup contract still runs without any hand-written call; the
 * {@link WorkflowOutboxComposeValidator} (contributed by {@link WorkflowServicesModule}) runs as a
 * per-plan definition validator whenever the workflow registry builds a plan;
 * the {@code MIGRATE}-phase {@code FlywayMigrationStartupStep} contributed by {@link DbFlywayModule}
 * runs schema migrations; then the verticles deploy ({@code INFRA} management verticle, then
 * {@code SERVICES} stub services + signal contributor + workflow executor + outbox relay, the latter
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
 *   <li>{@link WorkflowPostgresqlModule} — portable {@code WorkflowEngine} (via included
 *       {@code WorkflowEngineModule}), PostgreSQL repositories, and recorder multibinding.</li>
 *   <li>{@link WorkflowExecutorConfigModule} — the parsed {@code workflow.executor} configuration of the
 *       workflow executor, which the engine deploys in the {@code SERVICES} phase.</li>
 *   <li>{@link WorkflowServicesModule} — outbox recorder + signal contributor + compose
 *       validator.</li>
 *   <li>{@link CronPersistenceModule} and {@link JobPostgresqlModule} — the cron scheduler and the
 *       {@link dev.vertique.job.JobRepository} binding that the inbox/outbox relay requires.</li>
 *   <li>{@code GeneratedWorkflowTypeCatalogModule} — the type catalog the annotation processor
 *       generates from the {@code @WorkflowContract} and {@code @WorkflowType} classes of this
 *       module; every component that registers or loads workflow definitions includes it.</li>
 *   <li>{@link OrderFulfillmentDefinitionModule} — linear order-fulfillment workflow definition
 *       (cycle 1 saga).</li>
 *   <li>{@link OrderFulfillmentFanOutDefinitionModule} — ALL_REQUIRED fan-out order-fulfillment
 *       workflow definition.</li>
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
 * </ul>
 *
 * <p>Typed clients such as {@link OrderFulfillmentWorkflow} and {@link OrderFulfillmentFanOutWorkflow}
 * are not provided by Dagger: obtain them from {@link #workflowClientFactory()} with
 * {@code create(OrderFulfillmentDefinition.class)}.
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
            WorkflowExecutorConfigModule.class,
            WorkflowServicesModule.class,
            CronPersistenceModule.class,
            JobPostgresqlModule.class,
            GeneratedWorkflowTypeCatalogModule.class,
            OrderFulfillmentDefinitionModule.class,
            OrderFulfillmentFanOutDefinitionModule.class,
            TimerDefinitionModule.class,
            WorkflowTasksModule.class,
            TaskReviewDefinitionModule.class,
            StubServicesModule.class,
            AppModule.class
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
     * {@code forkStepId} and {@code branchId}.
     *
     * @return transactional workflow operations backed by {@link SqlClient}
     */
    TransactionalWorkflowOperations<SqlClient> txWorkflowOperations();

    /**
     * Returns the probe that waits until the engine has run the work recorded for an instance.
     *
     * <p>Used by integration tests to await an instance after each operation, before asserting.
     *
     * @return execution probe
     */
    WorkflowExecutionProbe executionProbe();

    /**
     * Returns the verticle deployments of the application, among them the {@code workflow-executor} deployment.
     *
     * <p>Used by integration tests that build the graph without a deployer to deploy the workflow executor alone.
     *
     * @return the verticle deployments
     */
    Set<VerticleDeployment> verticleDeployments();

    /**
     * Returns the workflow client factory for creating typed proxies from a definition class.
     *
     * @return workflow client factory
     */
    WorkflowClientFactory workflowClientFactory();

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
}
