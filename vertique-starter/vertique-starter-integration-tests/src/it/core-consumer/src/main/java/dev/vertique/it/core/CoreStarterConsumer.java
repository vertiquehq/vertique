// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.core;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.starter.core.CoreApplicationModule;
import jakarta.inject.Singleton;

/**
 * Real {@code @VertiqueApp} application component naming only {@link CoreApplicationModule}.
 *
 * <p>This fixture is the load-bearing proof of the core starter's composition contract: the
 * component never names {@code VertxModule} directly, yet the generated
 * {@code CoreStarterConsumerVertiqueComponentFactory} calls {@code .vertxModule(…)} on Dagger's
 * builder. Compiling and building it therefore proves that a stateful framework module reaching the
 * graph <em>transitively</em> through a starter aggregate is accepted by Dagger.
 */
@VertiqueApp
@Singleton
@Component(modules = {CoreApplicationModule.class})
public interface CoreStarterConsumer extends VertiqueApplicationComponent {}
