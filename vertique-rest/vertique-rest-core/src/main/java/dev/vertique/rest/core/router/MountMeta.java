// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Metadata describing a {@link RouterMount}. Used by {@link MountCustomizer}
 * to selectively apply customizations to specific mounts.
 *
 * @param mountId       stable identifier for this mount (FQCN by default, or e.g. {@code "jaxrs:/api/*"})
 * @param mountPath     the path prefix where the mount's sub-router is mounted
 * @param openapiPath   classpath location of the OpenAPI spec, or {@code null} for non-JAX-RS mounts
 * @param resourceTypes the JAX-RS resource classes in this mount, or empty for non-JAX-RS mounts
 */
public record MountMeta(
        String mountId, String mountPath, @Nullable String openapiPath, Set<Class<?>> resourceTypes) {}
