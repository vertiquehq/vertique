// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.Router;

/**
 * Per-mount customization hook applied by {@link HttpVerticle} after
 * {@link RouterMount#createRouter(io.vertx.core.Vertx)} completes and before the
 * sub-router is mounted on the main router.
 *
 * <p><b>Ordering:</b> customizers participate in the {@link OrderedExtension} contract and are
 * sorted by phase, then {@link #priority()} ascending, then {@link #orderKey()} (defaults to the
 * fully-qualified class name). Application customizers use the default phase and priority ({@code 0})
 * and run before framework built-ins that declare higher positive values.
 *
 * <p>Use {@link #matches(MountMeta)} to selectively target specific mounts:
 * <pre>
 * {@literal @}Provides {@literal @}IntoSet
 * MountCustomizer apiCors() {
 *     return new MountCustomizer() {
 *         {@literal @}Override public boolean matches(MountMeta meta) {
 *             return meta.mountId().startsWith("jaxrs:");
 *         }
 *         {@literal @}Override public void customize(Router router, MountMeta meta) {
 *             router.route().handler(CorsHandler.create());
 *         }
 *     };
 * }
 * </pre>
 *
 * @see OrderedExtension
 */
public interface MountCustomizer extends OrderedExtension {

    /**
     * Returns {@code true} if this customizer should be applied to the given mount.
     *
     * @param meta metadata of the mount being customized
     * @return whether to apply (default {@code true} — matches all mounts)
     */
    default boolean matches(MountMeta meta) {
        return true;
    }

    /**
     * Customizes the mount's router after creation. The router has already been fully
     * configured by the {@link RouterMount} but has not yet been mounted as a sub-router.
     *
     * @param mountRouter the mount's router
     * @param meta        metadata of the mount
     */
    void customize(Router mountRouter, MountMeta meta);
}
