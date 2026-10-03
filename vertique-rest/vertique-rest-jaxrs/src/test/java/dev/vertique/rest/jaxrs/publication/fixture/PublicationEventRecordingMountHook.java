// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T006 TP-001's recording {@link MountPublicationHook}: never wants detail, always succeeds,
 * records every {@link MountPublication} it receives, and forwards each {@code mountBuilt} call to
 * a shared {@link PublicationEventRecorder} so the per-mount event order
 * ({@code afterRouterCreated} → {@code mountBuilt} → {@code customize}) can be verified.
 */
public final class PublicationEventRecordingMountHook implements MountPublicationHook {

    private final PublicationEventRecorder recorder;
    private final List<MountPublication> received = new ArrayList<>();
    private final Map<String, MountPublication> receivedByMountPath = new ConcurrentHashMap<>();

    /**
     * Creates a hook that forwards every {@code mountBuilt} call to {@code recorder}.
     *
     * @param recorder the shared event recorder
     */
    public PublicationEventRecordingMountHook(PublicationEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean wantsDetail(String applicationName) {
        return false;
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        received.add(publication);
        receivedByMountPath.put(publication.mountPath(), publication);
        recorder.mountBuilt(publication.mountPath());
        return Future.succeededFuture();
    }

    /**
     * Returns every {@link MountPublication} received, in call order.
     *
     * @return an immutable copy of the recorded publications
     */
    public List<MountPublication> received() {
        return List.copyOf(received);
    }

    /**
     * Returns the single recorded publication for the given mount path.
     *
     * @param mountPath the mount path to look up
     * @return the recorded publication for {@code mountPath}
     * @throws AssertionError if no publication was recorded for {@code mountPath}
     */
    public MountPublication onlyReceivedFor(String mountPath) {
        MountPublication publication = receivedByMountPath.get(mountPath);
        if (publication == null) {
            throw new AssertionError("no publication recorded for mount '" + mountPath + "'");
        }
        return publication;
    }
}
