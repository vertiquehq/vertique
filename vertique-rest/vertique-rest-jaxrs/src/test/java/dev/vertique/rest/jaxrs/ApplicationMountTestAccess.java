// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.Application;
import java.util.List;

/**
 * Test-support accessor exposing {@link JaxRsRouterMount}'s package-private ordered-resources and
 * {@code applicationType()} accessors to the {@code dev.vertique.rest.jaxrs.application} test
 * package, which cannot reach a package-private member declared in this (different) test package.
 * Calls only {@link JaxRsRouterMount#orderedResources()} and {@link JaxRsRouterMount#applicationType()}
 * — no other access (G-05).
 */
public final class ApplicationMountTestAccess {

    private ApplicationMountTestAccess() {}

    /**
     * Returns the given mount's resources in their iteration order.
     *
     * @param mount the mount to read
     * @return the mount's resources, in their iteration order (for an application mount, ordered
     *     by fully qualified class name)
     */
    public static List<Object> orderedResources(JaxRsRouterMount mount) {
        return mount.orderedResources();
    }

    /**
     * Returns the given mount's declared application type (G-05), or {@code null} for a mount not
     * built from a declared application (for example, the zero-declaration default mount).
     *
     * @param mount the mount to read
     * @return the declared {@code Application} type, or {@code null}
     */
    @Nullable
    public static Class<? extends Application> applicationType(JaxRsRouterMount mount) {
        return mount.applicationType();
    }
}
