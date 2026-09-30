// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Metadata describing a {@link RouterMount}. Used by {@link MountCustomizer}
 * to selectively apply customizations to specific mounts.
 *
 * <p>{@code applicationName} is a nullable component appended after {@code resourceTypes}. The
 * four-argument constructor remains and supplies {@code null} for it, so code that constructs a
 * {@code MountMeta} with four arguments keeps compiling. Record patterns that deconstruct
 * {@code MountMeta} with four components are not source-compatible with the five-component record.
 *
 * @param mountId       stable identifier for this mount (FQCN by default, or e.g. {@code "jaxrs:/api/*"})
 * @param mountPath     the path prefix where the mount's sub-router is mounted
 * @param openapiPath   classpath location of the OpenAPI spec, or {@code null} for non-JAX-RS mounts
 * @param resourceTypes the JAX-RS resource classes in this mount, or empty for non-JAX-RS mounts
 * @param applicationName the name of the application this mount serves, or {@code null} when the
 *                        mount does not belong to a named application
 */
public record MountMeta(
        String mountId,
        String mountPath,
        @Nullable String openapiPath,
        Set<Class<?>> resourceTypes,
        @Nullable String applicationName) {

    /**
     * Creates mount metadata that does not belong to a named application; {@link #applicationName()}
     * is {@code null}.
     *
     * @param mountId       stable identifier for this mount
     * @param mountPath     the path prefix where the mount's sub-router is mounted
     * @param openapiPath   classpath location of the OpenAPI spec, or {@code null}
     * @param resourceTypes the JAX-RS resource classes in this mount, or empty
     */
    public MountMeta(String mountId, String mountPath, @Nullable String openapiPath, Set<Class<?>> resourceTypes) {
        this(mountId, mountPath, openapiPath, resourceTypes, null);
    }
}
