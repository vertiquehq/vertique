// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dev.vertique.aop.AspectProvider;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.security.events.SecurityEventObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module providing Micrometer metrics bindings.
 *
 * <p>Install alongside the application's {@code VertxModule} in the application's
 * {@code @Component}:
 *
 * <pre>{@code
 * @Component(modules = { VertxModule.class, MicrometerModule.class, ... })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p><b>Registry lifecycle note:</b> The {@link MeterRegistry} binding delegates to
 * {@link MeterRegistryHolder#registry()}, which holds an empty
 * {@link io.micrometer.core.instrument.composite.CompositeMeterRegistry} before bootstrap.
 * Recording to it before bootstrap is a no-op (NFR-TEL-003). The registry is populated with
 * real backends only after the application launches via {@code VertiqueApplication}, which
 * triggers {@link MicrometerMetricsContributor}.
 *
 * <p><b>Security observer contribution:</b> The {@link SecurityEventObserver} contribution
 * provided by this module flows into the rest-security module's {@code AuthModule} multibound
 * set when both modules are installed in the same Dagger component. This enables automatic
 * recording of authentication and authorization events as Micrometer metrics.
 *
 * @see MeterRegistryHolder
 * @see SecurityMetricsObserver
 * @see MetricsConfig
 */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerModule {

    // --- MeterRegistry ---

    /**
     * Provides the application-wide {@link MeterRegistry} from the global holder.
     *
     * <p>Before bootstrap this is an empty composite whose recording is a no-op (NFR-TEL-003).
     * After bootstrap it is the fully assembled composite contributed by all registered backend
     * providers.
     *
     * @return the composite meter registry; never {@code null}
     */
    @Provides
    @Singleton
    static MeterRegistry meterRegistry() {
        return MeterRegistryHolder.registry();
    }

    // --- MetricsConfig ---

    /**
     * Provides the deserialized {@link MetricsConfig} from the {@code metrics} section of the
     * application configuration.
     *
     * <p>Uses {@link JsonConfigPaths#navigateObject} for tolerant traversal — when the
     * {@code metrics} key is absent the empty object is deserialized with all defaults.
     *
     * @param config the application configuration
     * @param parser the injected config parser
     * @return the metrics configuration with defaults applied for any missing fields; never null
     */
    @Provides
    @Singleton
    static MetricsConfig metricsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "metrics"), MetricsConfig.class);
    }

    /**
     * Declares {@link MetricsConfig} as an optional binding so that {@link TimedAspect} can inject
     * {@code Optional<MetricsConfig>}.
     *
     * <p>Dagger cannot synthesize an {@code Optional<MetricsConfig>} from a plain
     * {@code @Provides MetricsConfig} alone — the {@code @BindsOptionalOf} declaration is what wires
     * the present {@code @Provides MetricsConfig} above into the {@code Optional} that
     * {@link TimedAspect#TimedAspect(MeterRegistry, java.util.Optional)} requires. This mirrors the
     * sibling {@code MicrometerRestModule}/{@code MicrometerServicesModule}/{@code
     * MicrometerPrometheusModule} pattern.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig optionalMetricsConfig();

    // --- @Timed AspectProvider ---

    /**
     * Binds {@link TimedAspect} as the {@link AspectProvider} for the {@link Timed @Timed} built-in.
     *
     * <p>The compile-time AOP processor injects {@code AspectProvider<Timed>} by its parameterized
     * interface type into each generated proxy that wraps a {@code @Timed} method; this binding
     * resolves that dependency to the {@link TimedAspect} timing interceptor factory.
     *
     * @param aspect the timing aspect provider; supplied by Dagger via its {@code @Inject} ctor
     * @return the aspect bound to the {@code AspectProvider<Timed>} interface type
     */
    @Binds
    abstract AspectProvider<Timed> bindTimedAspect(TimedAspect aspect);
}
