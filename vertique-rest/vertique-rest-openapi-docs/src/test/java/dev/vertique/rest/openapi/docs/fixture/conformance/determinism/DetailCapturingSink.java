// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.determinism;

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
 * An {@link OperationPublicationSink} that wants detail for every mount and keeps the latest attached
 * publication of each declared application, operation descriptors included, so a test can assemble a
 * real mount build's publication as the documentation sink would. It returns an already succeeded
 * future and never fails a mount. Thread-safe.
 */
public final class DetailCapturingSink implements OperationPublicationSink {

    private final Map<String, MountPublication> byApplication = new HashMap<>();

    /** Creates a sink with no kept publication. */
    public DetailCapturingSink() {}

    /**
     * Wants detail for every mount.
     *
     * @param applicationName the mount's application name, or {@code null}
     * @return {@code true}
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return true;
    }

    /**
     * Keeps the publication of a mount that serves a declared application.
     *
     * @param publication the mount's publication, with detail
     * @return a succeeded future
     */
    @Override
    public synchronized Future<Void> mountBuilt(MountPublication publication) {
        String applicationName = publication.applicationName();
        if (applicationName != null) {
            byApplication.put(applicationName, publication);
        }
        return Future.succeededFuture();
    }

    /**
     * Returns the latest attached publication of an application.
     *
     * @param applicationName the application name
     * @return the publication, or {@code null} when none was received for it
     */
    public synchronized @Nullable MountPublication publication(String applicationName) {
        return byApplication.get(applicationName);
    }

    /** Binds one component-scoped {@link DetailCapturingSink} into {@code Set<OperationPublicationSink>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's sink.
         *
         * @return a new sink
         */
        @Provides
        @Singleton
        static DetailCapturingSink detailCapturingSink() {
            return new DetailCapturingSink();
        }

        /**
         * Contributes the sink beside every other sink.
         *
         * @param sink the component's sink
         * @return {@code sink}
         */
        @Provides
        @IntoSet
        static OperationPublicationSink asSink(DetailCapturingSink sink) {
            return sink;
        }
    }
}
