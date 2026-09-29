// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Test {@link OperationPublicationSink} that throws a configured {@link RuntimeException} from
 * {@link #mountBuilt} for one configured mount path and records every other mount's publication
 * (T006 TP-006, a sink exception fails the enclosing mount's {@code createRouter}).
 */
public final class ThrowingSink implements OperationPublicationSink {

    private final String rejectedMountPath;
    private final RuntimeException exception;
    private final List<MountPublication> received = new ArrayList<>();

    /**
     * Creates a sink that throws {@code exception} for {@code rejectedMountPath} and otherwise
     * records the publication and succeeds.
     *
     * @param rejectedMountPath the mount path whose {@link #mountBuilt} call throws
     * @param exception         the exception to throw for {@code rejectedMountPath}
     */
    public ThrowingSink(String rejectedMountPath, RuntimeException exception) {
        this.rejectedMountPath = rejectedMountPath;
        this.exception = exception;
    }

    @Override
    public boolean wantsDetail(String applicationName) {
        return false;
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        if (rejectedMountPath.equals(publication.mountPath())) {
            throw exception;
        }
        received.add(publication);
        return Future.succeededFuture();
    }

    /**
     * Returns every publication recorded for a mount other than the rejected one, in call order.
     *
     * @return an immutable copy of the recorded publications
     */
    public List<MountPublication> received() {
        return List.copyOf(received);
    }
}
