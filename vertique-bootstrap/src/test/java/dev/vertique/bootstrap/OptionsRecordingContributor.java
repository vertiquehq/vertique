// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;

/**
 * Test fixture contributor that records the live {@link VertxOptions} instance and its
 * {@link VertxOptions#getEventLoopPoolSize()} value on each contribution.
 *
 * <p>On every {@link #contribute} call:
 * <ul>
 *   <li>Stores the {@link BootstrapContext#vertxOptions()} reference into
 *       {@link TestContributorState#recordedVertxOptions} — used for identity checks
 *       (same-instance vs new-instance) in the {@code vertx.options} overlay tests.</li>
 *   <li>Stores the pool size ({@link VertxOptions#getEventLoopPoolSize()}) into
 *       {@link TestContributorState#recordedEventLoopPoolSize} — used to verify that
 *       overlay values took effect.</li>
 * </ul>
 *
 * <p>Has no side-effects on the builder or the {@link VertxOptions} — read-only recording only.
 *
 * <p>The public no-arg constructor is required for {@link java.util.ServiceLoader} discovery.
 */
public final class OptionsRecordingContributor implements VertxBuilderContributor {

    /** Required by {@link java.util.ServiceLoader}. */
    public OptionsRecordingContributor() {}

    /**
     * {@inheritDoc}
     *
     * @return {@code 5} — runs before all other recording contributors so the recorded
     *     value reflects the options as the first contributor sees them
     */
    @Override
    public int priority() {
        return 5;
    }

    /**
     * Records {@link BootstrapContext#vertxOptions()} reference and event-loop pool size into
     * {@link TestContributorState}, then returns the builder unchanged.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return the same {@code builder}, unchanged
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
        VertxOptions opts = context.vertxOptions();
        TestContributorState.recordedVertxOptions.set(opts);
        TestContributorState.recordedEventLoopPoolSize.set(opts.getEventLoopPoolSize());
        return builder;
    }
}
