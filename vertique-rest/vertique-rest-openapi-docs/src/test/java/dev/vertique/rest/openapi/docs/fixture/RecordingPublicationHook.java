// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@link MountPublicationHook} that never wants detail, counts its {@code mountBuilt} calls
 * per mount path, and returns an already succeeded future. It retains nothing from a publication
 * but its mount path.
 */
public final class RecordingPublicationHook implements MountPublicationHook {

    private final Map<String, Integer> callsByMountPath = new HashMap<>();

    /** Creates a hook with no recorded call. */
    public RecordingPublicationHook() {}

    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return false;
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        synchronized (this) {
            callsByMountPath.merge(publication.mountPath(), 1, Integer::sum);
            notifyAll();
        }
        return Future.succeededFuture();
    }

    /**
     * Returns the number of {@code mountBuilt} calls for a mount path.
     *
     * @param mountPath the mount path
     * @return the call count, {@code 0} when never called for it
     */
    public synchronized int calls(String mountPath) {
        return callsByMountPath.getOrDefault(mountPath, 0);
    }

    /**
     * Blocks the calling thread until the hook has counted at least {@code expected} calls for a
     * mount path, or the timeout elapses. Call it only from a thread that no Vert.x instance owns.
     *
     * @param mountPath the mount path
     * @param expected  the call count to wait for
     * @param timeout   the longest time to wait
     * @return {@code true} when the count was reached, {@code false} when the timeout elapsed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public synchronized boolean awaitCalls(String mountPath, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (callsByMountPath.getOrDefault(mountPath, 0) < expected) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return false;
            }
            wait(remainingMs);
        }
        return true;
    }

    /** Binds one component-scoped {@link RecordingPublicationHook} into {@code Set<MountPublicationHook>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's recording hook.
         *
         * @return a new hook
         */
        @Provides
        @Singleton
        static RecordingPublicationHook recordingPublicationHook() {
            return new RecordingPublicationHook();
        }

        /**
         * Contributes the recording hook beside every other hook.
         *
         * @param hook the component's recording hook
         * @return {@code hook}
         */
        @Provides
        @IntoSet
        static MountPublicationHook asHook(RecordingPublicationHook hook) {
            return hook;
        }
    }
}
