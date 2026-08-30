// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.Router;

/**
 * Hook for customizing the main Vert.x {@link Router}. Implementations can add CORS
 * handlers, health check routes, static file serving, global error handlers, or any
 * other router-level handler.
 *
 * <p>Override {@link #mountPhase()} to control when the customizer runs relative to
 * {@link RouterMount} sub-routers:
 * <ul>
 *   <li>{@link MountPhase#BEFORE_MOUNTS} (default) — runs before sub-routers are mounted;
 *       use for CORS, health checks, global middleware.</li>
 *   <li>{@link MountPhase#AFTER_MOUNTS} — runs after all sub-routers are mounted;
 *       use for static file serving, SPA fallback, catch-all 404 handlers.</li>
 * </ul>
 *
 * <p>Within each mount-phase partition, ordering follows the {@link OrderedExtension}
 * contract: {@link #phase()} (system vs. application) ascending, then {@link #priority()}
 * ascending, then {@link #orderKey()} ascending.
 *
 * <p>Register via Dagger multibinding:
 * <pre>
 * {@literal @}Provides {@literal @}IntoSet
 * RouterCustomizer corsCustomizer() {
 *     return router -&gt; router.route().handler(CorsHandler.create());
 * }
 * </pre>
 */
@FunctionalInterface
public interface RouterCustomizer extends OrderedExtension {

    /**
     * Customizes the main router. Exceptions thrown by this callback propagate and are fatal to
     * the enclosing operation; processing does not continue.
     *
     * @param router the main HTTP router
     */
    void customize(Router router);

    /**
     * Controls when this customizer runs relative to {@link RouterMount} sub-router mounting.
     * This is distinct from {@link #phase()}, which governs system-vs-application ordering
     * within the {@link OrderedExtension} contract; {@code mountPhase()} partitions customizers
     * into a before-mounts group and an after-mounts group before that contract is applied within
     * each partition.
     *
     * @return the mount phase (default {@link MountPhase#BEFORE_MOUNTS})
     */
    default MountPhase mountPhase() {
        return MountPhase.BEFORE_MOUNTS;
    }

    /** Execution phase relative to sub-router mounting. */
    enum MountPhase {
        /** Runs before any {@link RouterMount} sub-routers are mounted. */
        BEFORE_MOUNTS,
        /** Runs after all {@link RouterMount} sub-routers are mounted. */
        AFTER_MOUNTS
    }
}
