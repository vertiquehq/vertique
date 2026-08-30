// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.VertxBuilder;

/**
 * SPI for customizing the {@link VertxBuilder} before the {@link io.vertx.core.Vertx} instance is
 * created during application bootstrap.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor} entries on the
 * classpath. Contributors are invoked in the order defined by
 * {@link OrderedExtension#comparator()}: ascending {@link #phase()}, then ascending
 * {@link #priority()}, then ascending {@link #orderKey()} as a stable tie-break.
 *
 * <p><strong>Contract for {@link #contribute}:</strong>
 * <ul>
 *   <li>The method MUST return a non-null {@link VertxBuilder}. Returning {@code null} is treated
 *       as a fatal error and causes an immediate startup abort with a descriptive message.</li>
 *   <li>The method MAY return the same {@code builder} it received, or a different/re-wrapped
 *       builder.</li>
 *   <li>The method MAY mutate {@code context.vertxOptions()} as a side effect.</li>
 *   <li>Throwing any exception is treated as a fatal error and causes an immediate startup abort
 *       naming this contributor's class.</li>
 * </ul>
 *
 * <p><strong>Contract for {@link #onShutdown}:</strong>
 * <ul>
 *   <li>Called exactly once, in the reverse of the contribution order, either after the
 *       {@link io.vertx.core.Vertx} instance stops or after a startup failure that occurred
 *       during the contribution phase.</li>
 *   <li>Only contributors that successfully completed their {@link #contribute} call receive an
 *       {@link #onShutdown} callback.</li>
 *   <li>Failures in {@link #onShutdown} are logged but never rethrown.</li>
 *   <li>The second and subsequent calls to the runner's shutdown method are no-ops.</li>
 * </ul>
 *
 * <p>This SPI is the deliberate ServiceLoader seam in an otherwise Dagger-first codebase because
 * it must run before the Dagger graph exists. See ADR-0095 for the recorded rationale.
 */
public interface VertxBuilderContributor extends OrderedExtension {

    /**
     * Customizes the {@link VertxBuilder} and/or {@link BootstrapContext#vertxOptions()} before
     * the {@link io.vertx.core.Vertx} instance is built.
     *
     * <p>The returned builder is threaded into the next contributor in the chain. All contributors
     * in the chain receive the builder returned by their predecessor.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param context the bootstrap context; never {@code null}
     * @return a non-null {@link VertxBuilder} (may be the same or a re-wrapped instance)
     * @throws Exception if the contribution fails fatally; startup will be aborted and the
     *     exception preserved as the cause of a {@link ContributorFailureException}
     */
    VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) throws Exception;

    /**
     * Called during application shutdown to release resources acquired during {@link #contribute}.
     *
     * <p><strong>Shutdown contract:</strong>
     * <ul>
     *   <li>Called <em>exactly once</em>, in the <em>reverse</em> of the contribution order, via
     *       the idempotency guard in {@link ContributorRunner}.</li>
     *   <li>Only contributors that successfully completed their {@link #contribute} call receive
     *       this callback (the <em>contributed prefix</em> only — a contributor that threw or
     *       returned {@code null} is excluded).</li>
     *   <li>Failures are caught, logged at error level, and never rethrown — a failing hook
     *       does not prevent subsequent hooks from running.</li>
     *   <li>Hooks run on all stop and startup-failure paths: normal Vert.x stop, Vert.x stop
     *       failure, deploy failure, Vert.x start failure, and contributor failure.</li>
     * </ul>
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * <p>The default implementation is a no-op.
     */
    default void onShutdown() {}
}
