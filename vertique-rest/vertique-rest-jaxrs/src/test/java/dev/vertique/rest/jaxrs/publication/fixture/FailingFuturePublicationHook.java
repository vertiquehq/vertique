// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Test {@link MountPublicationHook} that returns a configured failed {@link Future} from
 * {@link #mountBuilt} for one configured mount path and otherwise records the publication and
 * succeeds (T006 TP-006, a failed hook future fails the enclosing mount's {@code createRouter}).
 */
public final class FailingFuturePublicationHook implements MountPublicationHook {

    private final String failedMountPath;
    private final RuntimeException failure;
    private final List<MountPublication> received = new ArrayList<>();

    /**
     * Creates a hook that fails its returned future with {@code failure} for {@code failedMountPath}
     * and otherwise records the publication and succeeds.
     *
     * @param failedMountPath the mount path whose {@link #mountBuilt} call returns a failed future
     * @param failure         the failure the returned future carries for {@code failedMountPath}
     */
    public FailingFuturePublicationHook(String failedMountPath, RuntimeException failure) {
        this.failedMountPath = failedMountPath;
        this.failure = failure;
    }

    @Override
    public boolean wantsDetail(String applicationName) {
        return false;
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        if (failedMountPath.equals(publication.mountPath())) {
            return Future.failedFuture(failure);
        }
        received.add(publication);
        return Future.succeededFuture();
    }

    /**
     * Returns every publication recorded for a mount other than the failed one, in call order.
     *
     * @return an immutable copy of the recorded publications
     */
    public List<MountPublication> received() {
        return List.copyOf(received);
    }
}
