// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.ContributorRunner;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySources;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates the full shutdown sequence for a {@link VertiqueApplication} instance.
 *
 * <p>The sequence is:
 * <ol>
 *   <li>Contributor shutdown hooks — delegates to {@link ContributorRunner#runShutdownHooks()}
 *       for the successfully-contributed prefix (if a runner is present).</li>
 *   <li>Property source close — delegates to
 *       {@link ConfigPropertySources#closeAllReverse(List)} to close each source in
 *       <em>reverse declaration order</em>.</li>
 * </ol>
 *
 * <p>The entire sequence is idempotent: the second and subsequent calls to {@link #runOnce()} are
 * no-ops, guarded by an {@link AtomicBoolean}. {@link ContributorRunner} has its own idempotency
 * guard; the two are independent. The {@link VertiqueApplication} double-invocation path (when
 * {@code afterFailureToDeployVerticle} closes Vert.x, which then triggers
 * {@code afterVertxStopped}) is absorbed by this guard.
 *
 * <p>Failures in individual {@link ConfigPropertySource#close()} calls — including
 * {@link Error} subclasses — are caught, logged at error level (naming the source), and never
 * rethrown, ensuring the remaining sources are always closed.
 *
 * <p>A {@code null} runner is accepted; when {@code null}, the contributor-hooks step is skipped
 * and only the source-close step runs.
 */
final class BootstrapShutdown {

    private final ContributorRunner runner;
    private final List<ConfigPropertySource> sources;
    private final AtomicBoolean ran = new AtomicBoolean(false);

    // --- Constructor ---

    /**
     * Constructs the shutdown coordinator.
     *
     * @param runner  the contributor runner whose {@link ContributorRunner#runShutdownHooks()} to
     *                invoke first; {@code null} is accepted and causes the contributor step to be
     *                skipped
     * @param sources the property sources to close in reverse declaration order; must not be
     *                {@code null} (pass an empty list if there are no sources)
     */
    BootstrapShutdown(ContributorRunner runner, List<ConfigPropertySource> sources) {
        this.runner = runner;
        this.sources = List.copyOf(sources);
    }

    // --- Shutdown ---

    /**
     * Runs the shutdown sequence exactly once.
     *
     * <p>Subsequent calls are no-ops. The sequence is:
     * <ol>
     *   <li>Contributor hooks via {@link ContributorRunner#runShutdownHooks()} (if runner is
     *       non-null).</li>
     *   <li>Each {@link ConfigPropertySource#close()} in reverse declaration order. Failures are
     *       caught, logged, and never rethrown.</li>
     * </ol>
     */
    void runOnce() {
        if (!ran.compareAndSet(false, true)) {
            return;
        }

        // --- Step 1: contributor shutdown hooks ---
        if (runner != null) {
            runner.runShutdownHooks();
        }

        // --- Step 2: close property sources in reverse declaration order ---
        ConfigPropertySources.closeAllReverse(sources);
    }
}
