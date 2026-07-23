// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;

/**
 * Hand-written {@link VertiqueComponentFactory} that builds {@link FailingShutdownAppComponent}
 * from a {@link VertiqueRuntime}. Used by the {@code afterAll}-teardown-path test in {@link
 * VertiqueAppExtensionTest} to verify that the owned Vert.x is closed even when application
 * shutdown fails.
 */
final class FailingShutdownAppComponentFactory implements VertiqueComponentFactory<FailingShutdownAppComponent> {

    @Override
    public FailingShutdownAppComponent build(VertiqueRuntime runtime) {
        return DaggerFailingShutdownAppComponent.builder()
                .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
                .build();
    }
}
