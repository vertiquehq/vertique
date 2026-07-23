// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dagger.Component;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Singleton;

/**
 * Dagger component proving the {@code vertique-codegen-aop} processor's runtime substitution.
 *
 * <p>It installs the <em>generated</em> {@code GeneratedAopModule} — whose {@code @Binds}
 * substitutes the generated {@code Greeter$AopProxy} for {@link Greeter} — alongside
 * {@link AopRegistryModule} (the deterministic {@link SimpleMeterRegistry} + enabled
 * {@code MetricsConfig}) and {@link AopBindingsModule} (the {@code AspectProvider<Timed>} binding +
 * optional {@code MetricsConfig}).
 *
 * <p>Because {@code GeneratedAopModule} binds {@code Greeter} to {@code Greeter$AopProxy},
 * {@link #greeter()} returns the generated proxy instance, not a plain {@link Greeter} — that
 * substitution, observable at runtime, is the proof this example exists to demonstrate.
 *
 * <p>The caller supplies the {@link SimpleMeterRegistry} by constructing {@link AopRegistryModule}
 * with it (via the generated builder's {@code aopRegistryModule(...)} setter), so the test owns the
 * exact registry it later reads timers from.
 */
@Singleton
@Component(modules = {GeneratedAopModule.class, AopRegistryModule.class, AopBindingsModule.class})
public interface GreeterComponent {

    /**
     * Returns the {@link Greeter} — substituted by the generated {@code Greeter$AopProxy} via the
     * generated {@code @Binds} in {@code GeneratedAopModule}.
     *
     * @return the AOP-proxied greeter; never {@code null}
     */
    Greeter greeter();

    /**
     * Returns the {@link DeferringBean} — substituted by the generated {@code DeferringBean$AopProxy}
     * via the generated {@code @Binds} in {@code GeneratedAopModule}. Its sync method carries the
     * deliberately-deferring {@link Deferring @Deferring} aspect, so calling it through this proxy
     * trips the FR-013-05 sync-defer guard.
     *
     * @return the AOP-proxied deferring bean; never {@code null}
     */
    DeferringBean deferringBean();
}
