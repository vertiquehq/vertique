// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

/**
 * Application- or bridge-supplied factory that builds a Dagger {@code @Component} from a neutral
 * {@link VertiqueRuntime}.
 *
 * <p>This is the seam by which the framework remains agnostic to <em>which</em> Dagger component a
 * given deployment assembles. The framework hands a {@link VertiqueRuntime} (only {@code Vertx} +
 * config) to a factory; the factory owns the knowledge of which modules to wire and returns the
 * built component {@code C}.
 *
 * <p>On the standalone path a factory typically constructs the framework's
 * {@link VertxModule} from the runtime — {@code new VertxModule(rt.vertx(), rt.config())} (the
 * config flows into the {@link VertxConfig}-qualified binding) — alongside the application's own
 * modules, then returns the generated {@code DaggerXxx} component. An embedding host (Spring,
 * Quarkus) supplies a factory that additionally wires <em>typed</em> Dagger adapter modules
 * {@code @Provides}-ing host beans — there is no host-bean service locator; a missing adapter is a
 * compile-time missing-binding error, not a runtime failure.
 *
 * @param <C> the Dagger component type this factory builds
 */
@FunctionalInterface
public interface VertiqueComponentFactory<C> {

    /**
     * Builds the Dagger component from the given neutral runtime inputs.
     *
     * @param runtime the neutral framework inputs (Vert.x instance + root configuration); never
     *                {@code null}
     * @return the built Dagger component
     */
    C build(VertiqueRuntime runtime);
}
