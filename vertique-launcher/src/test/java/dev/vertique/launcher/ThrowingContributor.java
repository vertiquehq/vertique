// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import io.vertx.core.VertxBuilder;

/**
 * Test fixture contributor with {@link #priority()} {@code 30}. When
 * {@link TestContributorState#armThrowing} is {@code true}, throws
 * {@code IllegalStateException("boom")} from {@link #contribute}. Otherwise returns the builder
 * unchanged.
 *
 * <p>The public no-arg constructor is required for {@link java.util.ServiceLoader} discovery.
 */
public final class ThrowingContributor implements VertxBuilderContributor {

    /** Required by {@link java.util.ServiceLoader}. */
    public ThrowingContributor() {}

    /**
     * {@inheritDoc}
     *
     * @return {@code 30}
     */
    @Override
    public int priority() {
        return 30;
    }

    /**
     * Throws {@code IllegalStateException("boom")} when armed; otherwise returns the builder
     * unchanged.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return the same {@code builder}, unchanged (only reached when disarmed)
     * @throws IllegalStateException when {@link TestContributorState#armThrowing} is {@code true}
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
        if (TestContributorState.armThrowing) {
            throw new IllegalStateException("boom");
        }
        return builder;
    }
}
