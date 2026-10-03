// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@link MountPublicationHook} that never wants detail and retains the latest publication of
 * each mount path, so a test can hand a recorded operation to the route matcher. It returns an
 * already succeeded future.
 */
public final class PublicationRetainingHook implements MountPublicationHook {

    private final Map<String, MountPublication> byMountPath = new HashMap<>();

    /** Creates a hook with no retained publication. */
    public PublicationRetainingHook() {}

    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return false;
    }

    @Override
    public synchronized Future<Void> mountBuilt(MountPublication publication) {
        byMountPath.put(publication.mountPath(), publication);
        return Future.succeededFuture();
    }

    /**
     * Returns the latest publication of a mount path.
     *
     * @param mountPath the mount path
     * @return the publication, or {@code null} when none was received for it
     */
    public synchronized @Nullable MountPublication publication(String mountPath) {
        return byMountPath.get(mountPath);
    }

    /** Binds one component-scoped {@link PublicationRetainingHook} into {@code Set<MountPublicationHook>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's retaining hook.
         *
         * @return a new hook
         */
        @Provides
        @Singleton
        static PublicationRetainingHook publicationRetainingHook() {
            return new PublicationRetainingHook();
        }

        /**
         * Contributes the retaining hook.
         *
         * @param hook the component's retaining hook
         * @return {@code hook}
         */
        @Provides
        @IntoSet
        static MountPublicationHook asHook(PublicationRetainingHook hook) {
            return hook;
        }
    }
}
