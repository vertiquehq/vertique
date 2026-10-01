// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@link OperationPublicationSink} that never wants detail and retains the latest publication of
 * each mount path, so a test can hand a recorded operation to the route matcher. It returns an
 * already succeeded future.
 */
public final class PublicationRetainingSink implements OperationPublicationSink {

    private final Map<String, MountPublication> byMountPath = new HashMap<>();

    /** Creates a sink with no retained publication. */
    public PublicationRetainingSink() {}

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

    /** Binds one component-scoped {@link PublicationRetainingSink} into {@code Set<OperationPublicationSink>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's retaining sink.
         *
         * @return a new sink
         */
        @Provides
        @Singleton
        static PublicationRetainingSink publicationRetainingSink() {
            return new PublicationRetainingSink();
        }

        /**
         * Contributes the retaining sink.
         *
         * @param sink the component's retaining sink
         * @return {@code sink}
         */
        @Provides
        @IntoSet
        static OperationPublicationSink asSink(PublicationRetainingSink sink) {
            return sink;
        }
    }
}
