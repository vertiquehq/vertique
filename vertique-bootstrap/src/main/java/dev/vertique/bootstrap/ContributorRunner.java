// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.VertxBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers, orders, and drives the {@link VertxBuilderContributor} chain during application
 * bootstrap and shutdown.
 *
 * <p>Use {@link #discover()} to load contributors from the classpath via {@link ServiceLoader}.
 * Use the constructor {@link #ContributorRunner(List)} as a seam when the contributor list is known
 * ahead of time.
 *
 * <p><strong>Single-use contract:</strong> a {@code ContributorRunner} drives the contribution
 * chain <em>at most once</em>. {@link #contributeAll} guards against re-entry with an
 * {@link AtomicBoolean}: the first call runs the chain, and any subsequent call throws
 * {@link IllegalStateException} (the runner does not re-run contributors against a fresh builder).
 *
 * <p><strong>Contribution:</strong> {@link #contributeAll} threads each contributor's returned
 * {@link VertxBuilder} into the next contributor. On any failure (exception or {@code null}
 * return) a {@link ContributorFailureException} is thrown immediately and the contribution chain
 * is aborted. The count of contributors that completed successfully is tracked for use by
 * shutdown.
 *
 * <p><strong>Shutdown:</strong> {@link #runShutdownHooks()} is idempotent (exactly-once via
 * {@link AtomicBoolean#compareAndSet}). It iterates the <em>contributed prefix</em> — only
 * contributors that successfully completed — in <em>reverse</em> order and invokes
 * {@link VertxBuilderContributor#onShutdown()} on each. Failures are logged and never rethrown.
 */
public final class ContributorRunner {

    private static final Logger log = LoggerFactory.getLogger(ContributorRunner.class);

    private final List<VertxBuilderContributor> contributors;
    private final AtomicBoolean contributeAllRan = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRan = new AtomicBoolean(false);

    /** Number of contributors that completed {@link VertxBuilderContributor#contribute} successfully. */
    private volatile int contributedCount = 0;

    // --- Factory ---

    /**
     * Discovers all {@link VertxBuilderContributor} implementations on the classpath via
     * {@link ServiceLoader}, sorts them using {@link OrderedExtension#comparator()}, and returns
     * a runner over the immutable, sorted list.
     *
     * @return a new {@link ContributorRunner} over the discovered contributors
     */
    public static ContributorRunner discover() {
        List<VertxBuilderContributor> discovered = new ArrayList<>();
        ServiceLoader.load(VertxBuilderContributor.class).forEach(discovered::add);
        return new ContributorRunner(discovered);
    }

    // --- Constructor (seam) ---

    /**
     * Constructs a runner over the given contributors, sorting a defensive copy using
     * {@link OrderedExtension#comparator()}.
     *
     * @param contributors the contributor list to sort and run; must not be {@code null}
     */
    public ContributorRunner(List<VertxBuilderContributor> contributors) {
        List<VertxBuilderContributor> sorted = new ArrayList<>(contributors);
        sorted.sort(OrderedExtension.comparator());
        this.contributors = Collections.unmodifiableList(sorted);
    }

    // --- Contribution ---

    /**
     * Invokes each contributor in order, threading the returned {@link VertxBuilder} into the
     * next contributor's {@link VertxBuilderContributor#contribute} call.
     *
     * <p>This method is <em>single-use</em>: the first call runs the chain; any subsequent call
     * throws {@link IllegalStateException} without invoking any contributor.
     *
     * <p>On each contributor:
     * <ul>
     *   <li>If it throws, the exception is wrapped in a {@link ContributorFailureException} whose
     *       message contains the contributor's FQCN and whose cause is the original exception.</li>
     *   <li>If it returns {@code null}, a {@link ContributorFailureException} is thrown whose
     *       message contains the FQCN and states "returned null builder".</li>
     * </ul>
     *
     * <p>The internal contributed count is advanced to reflect how many contributors completed
     * successfully, so that {@link #runShutdownHooks()} can confine its reverse walk to the
     * correct prefix.
     *
     * @param initial the starting {@link VertxBuilder}; must not be {@code null}
     * @param ctx     the bootstrap context shared across all contributors; must not be {@code null}
     * @return the {@link VertxBuilder} returned by the last contributor in the chain
     * @throws IllegalStateException if {@code contributeAll} has already been called on this runner
     * @throws ContributorFailureException if any contributor throws or returns {@code null}
     */
    public VertxBuilder contributeAll(VertxBuilder initial, BootstrapContext ctx) {
        if (!contributeAllRan.compareAndSet(false, true)) {
            throw new IllegalStateException("contributeAll has already been invoked on this ContributorRunner");
        }
        VertxBuilder current = initial;
        for (VertxBuilderContributor contributor : contributors) {
            String fqcn = contributor.getClass().getName();
            VertxBuilder next;
            try {
                next = contributor.contribute(current, ctx);
            } catch (Exception e) {
                throw new ContributorFailureException(
                        "VertxBuilderContributor " + fqcn + " threw an exception during contribution", e);
            }
            if (next == null) {
                throw new ContributorFailureException(
                        "VertxBuilderContributor " + fqcn + " returned null builder — contribution aborted");
            }
            contributedCount++;
            current = next;
        }
        return current;
    }

    // --- Shutdown ---

    /**
     * Runs shutdown hooks for contributors that successfully completed their contribution, in
     * reverse contribution order.
     *
     * <p>This method is idempotent: the second and subsequent calls are no-ops. Failures in
     * individual hooks are caught, logged at error level (including the contributor class name),
     * and never rethrown.
     */
    public void runShutdownHooks() {
        if (!shutdownRan.compareAndSet(false, true)) {
            return;
        }
        int count = contributedCount;
        for (int i = count - 1; i >= 0; i--) {
            VertxBuilderContributor contributor = contributors.get(i);
            try {
                contributor.onShutdown();
            } catch (Throwable t) {
                log.error(
                        "VertxBuilderContributor {} threw during onShutdown — ignoring",
                        contributor.getClass().getName(),
                        t);
            }
        }
    }
}
