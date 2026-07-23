// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dagger.Component;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertxModule;
import jakarta.inject.Singleton;

/**
 * A minimal Dagger {@code @Component} extending {@link VertiqueApplicationComponent}, assembled
 * from {@link VertxModule} and {@link FailingShutdownModule}. Used exclusively by the
 * {@code afterAll}-teardown-path test in {@link VertiqueAppExtensionTest} to drive a shutdown
 * failure and verify that the owned {@link io.vertx.core.Vertx} is still closed.
 */
@Singleton
@Component(modules = {VertxModule.class, FailingShutdownModule.class})
interface FailingShutdownAppComponent extends VertiqueApplicationComponent {}
