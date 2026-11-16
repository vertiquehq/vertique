// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobNodeId;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;

/**
 * Dagger module for the job-core infrastructure.
 *
 * <p>Declares the {@link JobInterceptor} multibinding so that application modules can contribute
 * interceptors via {@code @Provides @IntoSet} methods, and binds the {@link JobNodeId} node identity
 * shared by the coordinator heartbeat and the execution claims of this node.
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

    /**
     * Provides this node's job-scheduling identity: the host name followed by a hyphen and an
     * 8-character random suffix, or {@code unknown} as the host name when it cannot be resolved.
     *
     * <p>Scoped once per component, so the coordinator heartbeat and every repository or scheduler
     * that stamps {@code locked_by} share the same value.
     *
     * @return the node identity
     */
    @Provides
    @Singleton
    @JobNodeId
    static String jobNodeId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
