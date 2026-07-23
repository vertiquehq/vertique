// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * End-to-end example proving the {@code vertique-codegen-aop} annotation processor substitutes a
 * generated {@code Greeter$AopProxy} for the {@link dev.vertique.examples.aop.Greeter} bean via a
 * generated Dagger {@code @Binds}, and that the {@link dev.vertique.micrometer.Timed @Timed} built-in
 * records a Micrometer {@code Timer} on each intercepted call at runtime.
 *
 * <p>The {@link dev.vertique.examples.aop.GreeterComponent Dagger component} installs the generated
 * {@code GeneratedAopModule} (proxy substitution) alongside
 * {@link dev.vertique.examples.aop.AopRegistryModule} / {@link dev.vertique.examples.aop.AopBindingsModule}
 * (a deterministic {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry} and the
 * {@code AspectProvider<Timed>} binding) so a test can read recorded timers directly rather than via
 * the global {@code MeterRegistryHolder}.
 *
 * <p>It also proves PRD-CODEGEN-013 slice 2.3 runtime behavior: {@link dev.vertique.examples.aop.Greeter}
 * carries a synchronous-returning method intercepted by the built-in {@code @Timed} (the sync-unwrap
 * path, which never trips the FR-013-05 guard), while {@link dev.vertique.examples.aop.DeferringBean}
 * carries a sync method annotated with the custom {@link dev.vertique.examples.aop.Deferring @Deferring}
 * aspect whose {@link dev.vertique.examples.aop.DeferringAspect} deliberately defers completion — so
 * calling it through the generated proxy trips the FR-013-05 guard and throws
 * {@link java.lang.IllegalStateException}.
 */
package dev.vertique.examples.aop;
