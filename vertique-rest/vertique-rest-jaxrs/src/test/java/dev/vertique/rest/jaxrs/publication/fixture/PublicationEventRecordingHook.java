// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import io.vertx.ext.web.Router;

/**
 * T006 TP-001's {@link RouterLifecycleHook}: forwards every {@code afterRouterCreated} call to a
 * shared {@link PublicationEventRecorder}, opening that mount's pending event group.
 */
public final class PublicationEventRecordingHook implements RouterLifecycleHook {

    private final PublicationEventRecorder recorder;

    /**
     * Creates a hook that forwards every {@code afterRouterCreated} call to {@code recorder}.
     *
     * @param recorder the shared event recorder
     */
    public PublicationEventRecordingHook(PublicationEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterRouterCreated(Router router) {
        recorder.afterRouterCreated(router);
    }
}
