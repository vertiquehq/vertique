// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The hand-built JAX-RS mounts a row adds to its composition, bound into its component with
 * {@code @BindsInstance}. Each mount is created by {@link JaxRsRouterMount.Factory#create(String,
 * String, Set, int)} with no OpenAPI contract and exactly one resource instance, which is not also
 * contributed as a {@code @JaxRsResources} instance.
 *
 * @param mounts the mounts, in any order
 */
public record HandBuiltMounts(List<Mount> mounts) {

    /** The priority {@link JaxRsRouterMount.Factory} gives a mount by default. */
    public static final int DEFAULT_PRIORITY = 1000;

    /**
     * Copies the mount list.
     *
     * @param mounts the mounts
     */
    public HandBuiltMounts {
        mounts = List.copyOf(mounts);
    }

    /**
     * Returns the given mounts.
     *
     * @param mounts the mounts
     * @return the hand-built mounts
     */
    public static HandBuiltMounts of(Mount... mounts) {
        return new HandBuiltMounts(List.of(mounts));
    }

    /**
     * One hand-built JAX-RS mount.
     *
     * @param mountPath the mount path, such as {@code /apidocs/*}
     * @param resource  the mount's one resource instance
     * @param priority  the mount priority
     */
    public record Mount(String mountPath, Object resource, int priority) {

        /**
         * Returns a mount with the default priority.
         *
         * @param mountPath the mount path
         * @param resource  the mount's one resource instance
         * @return the mount
         */
        public static Mount at(String mountPath, Object resource) {
            return new Mount(mountPath, resource, DEFAULT_PRIORITY);
        }

        /**
         * Creates this mount through the component's factory.
         *
         * @param factory the component's JAX-RS mount factory
         * @return a new router mount
         */
        public RouterMount create(JaxRsRouterMount.Factory factory) {
            return factory.create(mountPath, null, Set.of(resource), priority);
        }
    }

    /** Contributes the bound {@link HandBuiltMounts} into {@code Set<RouterMount>}. */
    @Module
    public static final class Contribution {

        private Contribution() {}

        /**
         * Creates the row's hand-built mounts, new instances on every provision.
         *
         * @param factory the component's JAX-RS mount factory
         * @param handBuilt the bound mounts
         * @return the created mounts
         */
        @Provides
        @ElementsIntoSet
        static Set<RouterMount> handBuiltMounts(JaxRsRouterMount.Factory factory, HandBuiltMounts handBuilt) {
            Set<RouterMount> mounts = new LinkedHashSet<>();
            for (Mount mount : handBuilt.mounts()) {
                mounts.add(mount.create(factory));
            }
            return mounts;
        }
    }
}
