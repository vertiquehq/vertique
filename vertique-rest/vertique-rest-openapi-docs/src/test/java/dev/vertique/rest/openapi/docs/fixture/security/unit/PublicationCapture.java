// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

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
 * An {@link OperationPublicationSink} that wants detail for every mount serving a declared
 * application and retains the latest publication of each such application, descriptors included,
 * so a test can hand a real mount build's publication to the document assembler. It returns an
 * already succeeded future and never fails a mount. Thread-safe.
 */
public final class PublicationCapture implements OperationPublicationSink {

    private final Map<String, MountPublication> byApplication = new HashMap<>();

    /** Creates a capture with no retained publication. */
    public PublicationCapture() {}

    /**
     * Wants detail for every mount that serves a declared application.
     *
     * @param applicationName the mount's application name, or {@code null}
     * @return {@code true} exactly when {@code applicationName} is non-null
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return applicationName != null;
    }

    /**
     * Retains the publication of a mount that serves a declared application.
     *
     * @param publication the mount's publication
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
     * Returns the latest publication of an application.
     *
     * @param applicationName the application name
     * @return the publication, or {@code null} when none was received for it
     */
    public synchronized @Nullable MountPublication publication(String applicationName) {
        return byApplication.get(applicationName);
    }

    /** Binds one component-scoped {@link PublicationCapture} into {@code Set<OperationPublicationSink>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's capture.
         *
         * @return a new capture
         */
        @Provides
        @Singleton
        static PublicationCapture publicationCapture() {
            return new PublicationCapture();
        }

        /**
         * Contributes the capture as a publication sink.
         *
         * @param capture the component's capture
         * @return {@code capture}
         */
        @Provides
        @IntoSet
        static OperationPublicationSink asSink(PublicationCapture capture) {
            return capture;
        }
    }
}
