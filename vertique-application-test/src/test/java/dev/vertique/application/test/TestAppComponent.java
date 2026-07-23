// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dagger.Component;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertxModule;
import jakarta.inject.Singleton;

/**
 * A minimal Dagger {@code @Component} extending {@link VertiqueApplicationComponent}, assembled from
 * {@link VertxModule} (supplying {@code Vertx} + config) and {@link StubLifecycleModule} (empty
 * startup/verticle multibindings + one recording shutdown step). Built by {@link
 * TestAppComponentFactory} so the self-test can boot it through {@link VertiqueAppExtension}.
 */
@Singleton
@Component(modules = {VertxModule.class, StubLifecycleModule.class})
interface TestAppComponent extends VertiqueApplicationComponent {}
