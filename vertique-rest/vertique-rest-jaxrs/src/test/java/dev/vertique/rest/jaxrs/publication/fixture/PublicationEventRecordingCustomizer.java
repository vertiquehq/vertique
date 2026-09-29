// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import io.vertx.ext.web.Router;

/**
 * T006 TP-001's {@link MountCustomizer}: forwards every {@code customize} call to a shared
 * {@link PublicationEventRecorder}, finalizing that mount's event group.
 */
public final class PublicationEventRecordingCustomizer implements MountCustomizer {

    private final PublicationEventRecorder recorder;

    /**
     * Creates a customizer that forwards every {@code customize} call to {@code recorder}.
     *
     * @param recorder the shared event recorder
     */
    public PublicationEventRecordingCustomizer(PublicationEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void customize(Router mountRouter, MountMeta meta) {
        recorder.customize(mountRouter, meta.mountPath());
    }
}
