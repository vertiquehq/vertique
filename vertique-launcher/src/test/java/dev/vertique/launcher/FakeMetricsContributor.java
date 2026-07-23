// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import io.vertx.core.VertxBuilder;
import io.vertx.core.metrics.MetricsOptions;

/**
 * Test fixture contributor with {@link #priority()} {@code 40}. When
 * {@link TestContributorState#armFakeMetrics} is {@code true}, enables metrics on
 * {@link BootstrapContext#vertxOptions()} and wires the {@link FakeVertxMetrics} factory into
 * the builder. Otherwise returns the builder unchanged.
 *
 * <p>The public no-arg constructor is required for {@link java.util.ServiceLoader} discovery.
 */
public final class FakeMetricsContributor implements VertxBuilderContributor {

    /** Required by {@link java.util.ServiceLoader}. */
    public FakeMetricsContributor() {}

    /**
     * {@inheritDoc}
     *
     * @return {@code 40}
     */
    @Override
    public int priority() {
        return 40;
    }

    /**
     * When armed, enables metrics on {@link BootstrapContext#vertxOptions()} and returns a
     * builder with {@link FakeVertxMetrics} registered as the metrics implementation. Otherwise
     * returns the builder unchanged.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return a builder (possibly updated with metrics) or the original builder when disarmed
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
        if (TestContributorState.armFakeMetrics) {
            context.vertxOptions().setMetricsOptions(new MetricsOptions().setEnabled(true));
            return builder.withMetrics(options -> new FakeVertxMetrics());
        }
        return builder;
    }
}
