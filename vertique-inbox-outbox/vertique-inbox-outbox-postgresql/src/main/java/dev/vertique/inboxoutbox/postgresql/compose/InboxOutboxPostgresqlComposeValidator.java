// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.compose;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.dagger.CronPersistenceMarker;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Startup validator that asserts the application's Dagger graph is correctly composed for the
 * cluster-singleton outbox/inbox maintenance jobs registered by
 * {@code TransactionalMessagingPostgresqlModule}.
 *
 * <h2>Validation contract</h2>
 *
 * <p>Uses the <em>constructible-as-validation</em> pattern: requesting {@link CronJobRegistrar},
 * {@link CronScheduler}, and {@link CronPersistenceMarker} in the constructor forces Dagger to
 * verify those bindings at code-generation time. Without them, an application that installs
 * {@code TransactionalMessagingPostgresqlModule} would silently ship without stale-lease
 * recovery or cleanup, or would start up and only fail at runtime in
 * {@code CronJobRegistrar.scan()}.
 *
 * <p>{@link CronPersistenceMarker} is the only binding that proves
 * {@link dev.vertique.job.cron.dagger.CronPersistenceModule} is installed (not the in-memory
 * {@link dev.vertique.job.cron.dagger.CronModule}). The in-memory module supplies
 * {@link CronJobRegistrar}/{@link CronScheduler} too, but with a {@code null}
 * {@link dev.vertique.job.JobRepository} internally — {@code SINGLE_INSTANCE} leader election
 * would only fail at runtime. The marker binding is unique to the persistence module, so the
 * in-memory cron flavor cannot satisfy this validator at Dagger codegen.
 *
 * <h2>How validation runs</h2>
 *
 * <p>This validator implements {@link ComposeValidator} so the framework materializes it in the
 * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing
 * its construction-time, constructible-as-validation check) without any app code referencing it
 * directly. It is contributed to the {@code Set<ComposeValidator>} multibinding by
 * {@code TransactionalMessagingPostgresqlModule.inboxOutboxPostgresqlComposeValidator(...)}.
 */
@Singleton
public final class InboxOutboxPostgresqlComposeValidator implements ComposeValidator {

    /**
     * Validates the application's Dagger graph for cluster-singleton outbox/inbox maintenance.
     *
     * @param cronJobRegistrar      the cron registrar; injected for its binding side-effect only
     * @param cronScheduler         the cron scheduler; injected for its binding side-effect only
     * @param cronPersistenceMarker the persistence-flavor marker; injected for its binding
     *                              side-effect only. Bound only by
     *                              {@link dev.vertique.job.cron.dagger.CronPersistenceModule},
     *                              so installing the in-memory
     *                              {@link dev.vertique.job.cron.dagger.CronModule} alone leaves
     *                              this binding unsatisfied and Dagger fails to compile.
     */
    @Inject
    public InboxOutboxPostgresqlComposeValidator(
            CronJobRegistrar cronJobRegistrar,
            CronScheduler cronScheduler,
            CronPersistenceMarker cronPersistenceMarker) {
        // All parameters are intentionally unused after construction. Their presence forces
        // Dagger to verify the cron infrastructure is on the component graph at compile time.
    }
}
