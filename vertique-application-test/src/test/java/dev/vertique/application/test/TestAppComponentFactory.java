// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;

/**
 * Hand-written {@link VertiqueComponentFactory} that builds {@link TestAppComponent} from a {@link
 * VertiqueRuntime} by constructing {@link VertxModule} from the runtime's {@code Vertx} + config and
 * delegating to the Dagger-generated {@code DaggerTestAppComponent} builder.
 *
 * <p>This mirrors what the {@code vertique-codegen-application} processor generates from a {@code
 * @VertiqueApp}-annotated component; the self-test hand-writes it to avoid depending on the codegen
 * module.
 */
final class TestAppComponentFactory implements VertiqueComponentFactory<TestAppComponent> {

    @Override
    public TestAppComponent build(VertiqueRuntime runtime) {
        return DaggerTestAppComponent.builder()
                .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
                .build();
    }
}
