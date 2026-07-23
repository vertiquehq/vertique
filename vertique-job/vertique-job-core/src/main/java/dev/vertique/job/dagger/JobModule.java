// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.dagger;

import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobInterceptor;
import java.util.Set;

/**
 * Dagger module for the job-core infrastructure.
 *
 * <p>Declares the {@link JobInterceptor} multibinding so that application modules can contribute
 * interceptors via {@code @Provides @IntoSet} methods.
 *
 * <p>MDC context propagation is handled automatically by populating the {@code mdcContext} field
 * in each dispatched {@link dev.vertique.core.eventbus.DispatchEnvelope}, which is restored by
 * {@link dev.vertique.services.dispatch.ServiceMethodInvoker} before the handler is invoked.
 *
 * <p>Include this module in your Dagger component when using any job scheduling feature:
 * <pre>{@code
 * @Component(modules = {JobModule.class, CronModule.class, ...})
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module
public abstract class JobModule {

    /** Declares the empty set binding for {@link JobInterceptor} contributions. */
    @Multibinds
    abstract Set<JobInterceptor> jobInterceptors();

    /** Declares the empty set binding for {@link JobExecutionStateTransitionListener} contributions. */
    @Multibinds
    abstract Set<JobExecutionStateTransitionListener> jobExecutionStateTransitionListeners();
}
