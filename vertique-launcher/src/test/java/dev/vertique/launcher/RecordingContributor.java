// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import io.vertx.core.VertxBuilder;

/**
 * Abstract base class for test-fixture {@link VertxBuilderContributor} implementations that
 * unconditionally record their invocations into {@link TestContributorState}.
 *
 * <p>On every {@link #contribute} call, appends {@code "<label>:contribute"} to
 * {@link TestContributorState#invocations} and returns the builder unchanged. On every
 * {@link #onShutdown} call, appends {@code "<label>:shutdown"}. Subclasses may add
 * additional side-effects in their overrides by calling {@code super} first.
 *
 * <p>Recording is unconditional — there is no arm gate. Tests that need to inspect only a
 * subset of invocations should filter {@link TestContributorState#invocations} in their
 * assertions rather than relying on arm flags.
 *
 * @param label    the short name used as the prefix in invocation records (e.g. {@code "alpha"})
 * @param priority the value returned by {@link #priority()}
 */
abstract class RecordingContributor implements VertxBuilderContributor {

    private final String label;
    private final int priority;

    /**
     * Creates a recording contributor.
     *
     * @param label    short name used as the invocation-record prefix (e.g. {@code "alpha"})
     * @param priority the value returned by {@link #priority()}
     */
    RecordingContributor(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    /**
     * {@inheritDoc}
     *
     * @return the priority supplied at construction
     */
    @Override
    public int priority() {
        return priority;
    }

    /**
     * Records {@code "<label>:contribute"} into {@link TestContributorState#invocations} and
     * returns the builder unchanged.
     *
     * <p>Subclasses that override this method MUST call {@code super.contribute(builder, context)}
     * first and return its result (or a further-modified builder).
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return the same {@code builder}, unchanged
     * @throws Exception if the subclass override throws
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) throws Exception {
        TestContributorState.invocations.add(label + ":contribute");
        return builder;
    }

    /**
     * Records {@code "<label>:shutdown"} into {@link TestContributorState#invocations}.
     */
    @Override
    public void onShutdown() {
        TestContributorState.invocations.add(label + ":shutdown");
    }
}
