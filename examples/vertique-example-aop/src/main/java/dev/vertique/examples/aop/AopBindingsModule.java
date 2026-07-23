// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.micrometer.Timed;
import dev.vertique.micrometer.TimedAspect;

/**
 * Abstract Dagger module wiring the {@code @Timed} aspect runtime for the example.
 *
 * <p>It binds {@link TimedAspect} as the {@code AspectProvider<Timed>} the generated
 * {@code Greeter$AopProxy} injects, and declares {@link MetricsConfig} as an optional binding so
 * {@code TimedAspect}'s {@code Optional<MetricsConfig>} constructor parameter resolves. The present
 * {@code @Provides MetricsConfig} in {@link AopRegistryModule} satisfies that optional.
 *
 * <p>This mirrors the framework's {@code MicrometerModule}, including the {@code @BindsOptionalOf}
 * that the F3 fix added there — without it Dagger cannot synthesize {@code Optional<MetricsConfig>}
 * from a plain {@code @Provides MetricsConfig}.
 */
@Module
public abstract class AopBindingsModule {

    private AopBindingsModule() {}

    /**
     * Binds {@link TimedAspect} as the {@link AspectProvider} for the {@link Timed @Timed} built-in,
     * resolving the {@code AspectProvider<Timed>} dependency the generated proxy injects.
     *
     * @param aspect the timing aspect provider; supplied by Dagger via its {@code @Inject} ctor
     * @return the aspect bound to the {@code AspectProvider<Timed>} interface type
     */
    @Binds
    abstract AspectProvider<Timed> bindTimedAspect(TimedAspect aspect);

    /**
     * Binds {@link DeferringAspect} as the {@link AspectProvider} for the custom {@link Deferring}
     * trigger, resolving the {@code AspectProvider<Deferring>} dependency the generated
     * {@code DeferringBean$AopProxy} injects. This custom aspect deliberately defers completion, so it
     * proves the FR-013-05 sync-defer guard at runtime.
     *
     * @param aspect the deferring aspect provider; supplied by Dagger via its {@code @Inject} ctor
     * @return the aspect bound to the {@code AspectProvider<Deferring>} interface type
     */
    @Binds
    abstract AspectProvider<Deferring> bindDeferringAspect(DeferringAspect aspect);

    /**
     * Declares {@link MetricsConfig} as an optional binding so {@code TimedAspect} can inject
     * {@code Optional<MetricsConfig>}. The present {@code @Provides MetricsConfig} in
     * {@link AopRegistryModule} satisfies it.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig optionalMetricsConfig();
}
