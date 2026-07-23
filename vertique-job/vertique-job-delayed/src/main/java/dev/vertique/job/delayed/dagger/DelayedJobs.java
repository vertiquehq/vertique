// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.dagger;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifier for the multibinding set of {@link dev.vertique.job.delayed.DelayedJobExecutor}
 * instances contributed via {@code @IntoSet} bindings.
 *
 * <p>Used by {@link dev.vertique.job.delayed.DelayedJobContractContributor} to receive the
 * full set of registered executors and build typed service contract entries from them.
 *
 * <p>Example usage in an application module:
 * <pre>{@code
 * @Provides @IntoSet @DelayedJobs
 * static Object deliverWebhookExecutor(DeliverWebhookJobImpl impl) {
 *     return impl;
 * }
 * }</pre>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayedJobs {}
