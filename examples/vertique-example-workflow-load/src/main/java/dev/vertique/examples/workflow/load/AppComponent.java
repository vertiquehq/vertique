// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load;

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
import dev.vertique.examples.workflow.load.node.EffectiveConfig;
import dev.vertique.examples.workflow.load.node.LoadNodeModule;
import dev.vertique.examples.workflow.load.workflow.GeneratedWorkflowTypeCatalogModule;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule;
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import jakarta.inject.Singleton;

/**
 * Root Dagger component of a load node.
 *
 * <p>The component is annotated {@link VertiqueApp}, so the framework's annotation processor generates the
 * component factory and the launcher starts it: a node is the launcher entry point
 * {@code dev.vertique.launcher.VertiqueApplication} with a configuration. There is no REST layer and no management
 * verticle.
 *
 * <p>A node runs the real product modules: the database pool, the workflow engine with its PostgreSQL adapter and
 * executor, the outbox relay, the intake sampler, the service dispatch infrastructure and the signal ingress that
 * delivers relayed signals. Flyway is installed so that the product configuration stays the same as in a deployment,
 * but a node must run with {@code flyway.mode} {@code DISABLED}: the schema is migrated once, before any node starts.
 *
 * <p>No observer is bound. The cron scheduler and job repository are present because the relay's startup validation
 * requires them.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            DbModule.class,
            DbPostgresqlModule.class,
            DbFlywayModule.class,
            DispatchModule.class,
            TransactionalMessagingPostgresqlModule.class,
            TransactionalMessagingServiceModule.class,
            WorkflowPostgresqlModule.class,
            WorkflowExecutorConfigModule.class,
            WorkflowRecordingConfigModule.class,
            WorkflowSchemaModule.class,
            JsonRuntimeModule.class,
            WorkflowServicesModule.class,
            CronPersistenceModule.class,
            JobPostgresqlModule.class,
            GeneratedWorkflowTypeCatalogModule.class,
            LoadNodeModule.class
        })
public interface AppComponent extends VertiqueApplicationComponent {

    /**
     * Returns the view of the configuration the node runs on.
     *
     * @return the effective configuration
     */
    EffectiveConfig effectiveConfig();
}
