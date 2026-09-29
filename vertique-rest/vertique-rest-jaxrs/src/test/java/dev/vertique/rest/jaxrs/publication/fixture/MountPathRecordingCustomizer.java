// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;
import java.util.ArrayList;
import java.util.List;

/**
 * T006 TP-006's counting {@link MountCustomizer} (in the spirit of TP-001's
 * {@link PublicationEventRecordingCustomizer}, kept standalone here since TP-006's compositions
 * never wire a {@link PublicationEventRecorder}-notifying sink or hook): records every mount path
 * {@code customize} was called for, in call order, so a test can prove a rejected or failed mount's
 * customizer never ran.
 */
public final class MountPathRecordingCustomizer implements MountCustomizer {

    private final List<String> customizedMountPaths = new ArrayList<>();

    @Override
    public synchronized void customize(Router mountRouter, MountMeta meta) {
        customizedMountPaths.add(meta.mountPath());
    }

    /**
     * Returns every mount path {@code customize} was called for, in call order.
     *
     * @return an immutable copy of the recorded mount paths
     */
    public synchronized List<String> customizedMountPaths() {
        return List.copyOf(customizedMountPaths);
    }
}
