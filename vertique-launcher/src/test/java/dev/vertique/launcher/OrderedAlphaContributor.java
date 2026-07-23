// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import io.vertx.core.VertxBuilder;

/**
 * Test fixture contributor with {@link #priority()} {@code 20}.
 *
 * <p>Always records {@code "alpha:contribute"} on contribution and {@code "alpha:shutdown"} on
 * shutdown (inherited unconditionally from {@link RecordingContributor}). In addition, always
 * captures {@link BootstrapContext#config()} into {@link TestContributorState#recordedConfig} on
 * each {@code contribute} invocation.
 *
 * <p>The public no-arg constructor is required for {@link java.util.ServiceLoader} discovery.
 */
public final class OrderedAlphaContributor extends RecordingContributor {

    /** Required by {@link java.util.ServiceLoader}. */
    public OrderedAlphaContributor() {
        super("alpha", 20);
    }

    /**
     * Calls super to record {@code "alpha:contribute"}, then captures
     * {@link BootstrapContext#config()} into {@link TestContributorState#recordedConfig}.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return the same {@code builder}, unchanged
     * @throws Exception if the super call throws
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) throws Exception {
        VertxBuilder result = super.contribute(builder, context);
        TestContributorState.recordedConfig.set(context.config());
        return result;
    }
}
