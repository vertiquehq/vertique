// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dagger.Module;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.DbModule;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.workflow.order.di.AppModule;
import dev.vertique.examples.workflow.order.di.StubServicesModule;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule;
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.job.delayed.dagger.DelayedJobModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.delayed.di.WorkflowDelayedModule;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryModule;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.tasks.di.WorkflowTasksModule;

/**
 * The modules of the example application without its definition modules, plus the document loader and the opt-in
 * definitions and executor configuration. A scenario node contributes its definitions and type names through
 * {@link ScenarioWiring}.
 */
@Module(
        includes = {
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
            WorkflowTasksModule.class,
            StubServicesModule.class,
            AppModule.class,
            WorkflowDefinitionModule.class,
            WorkflowDefinitionsConfigModule.class,
            WorkflowExecutorConfigModule.class,
            ScenarioWiring.class
        })
interface ScenarioInfrastructure {}
