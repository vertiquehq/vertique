// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.correlation.TraceReferenceResolver;
import dev.vertique.security.events.SecurityEventObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module providing OpenTelemetry bindings for the Vertique framework.
 *
 * <p>Install alongside the application's {@code VertxModule} in the application's
 * {@code @Component}:
 *
 * <pre>{@code
 * @Component(modules = { VertxModule.class, OpenTelemetryModule.class, ... })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <h2>OpenTelemetry binding</h2>
 * <p>The {@link OpenTelemetry} binding resolves the global registered by
 * {@link OpenTelemetryBootstrapContributor} (contributor runs before Dagger component construction,
 * so the global is set by the time {@link GlobalOpenTelemetry#getOrNoop()} is called) or falls
 * back to {@link OpenTelemetry#noop()} when tracing is disabled or no global was provisioned.
 * When {@code tracing.enabled=false}, {@link OpenTelemetry#noop()} is returned directly without
 * consulting the global.
 *
 * <h2>Security span events</h2>
 * <p>The {@link SecurityEventObserver} contribution flows into the rest-security module's
 * {@code AuthModule} multibound set when both modules are installed in the same Dagger component.
 * Security lifecycle events are then recorded as span events on the current active span.
 *
 * @see OpenTelemetryBootstrapContributor
 * @see TracingConfig
 * @see SecuritySpanEventObserver
 */
@Module
public abstract class OpenTelemetryModule {

    // --- TracingConfig ---

    /**
     * Provides the deserialized {@link TracingConfig} from the {@code tracing} section of the
     * application configuration.
     *
     * <p>Uses {@link JsonConfigPaths#navigateObject} for tolerant traversal — when the
     * {@code tracing} key is absent the empty object is deserialized with all defaults.
     *
     * @param config the application configuration
     * @param parser the injected config parser
     * @return the tracing configuration with defaults applied for any missing fields; never null
     */
    @Provides
    @Singleton
    static TracingConfig tracingConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "tracing"), TracingConfig.class);
    }

    // --- OpenTelemetry ---

    /**
     * Provides the {@link OpenTelemetry} instance.
     *
     * <p>When {@code tracing.enabled=false}, returns {@link OpenTelemetry#noop()} directly.
     * When enabled, returns {@link GlobalOpenTelemetry#getOrNoop()} — which resolves the global
     * registered by {@link OpenTelemetryBootstrapContributor} (which ran before the component was
     * built) or {@link OpenTelemetry#noop()} if no global was registered (e.g. in tests or when
     * the bootstrap contributor was not wired).
     *
     * @param config the tracing configuration
     * @return the active {@link OpenTelemetry} instance; never null
     */
    @Provides
    @Singleton
    static OpenTelemetry openTelemetry(TracingConfig config) {
        if (!config.enabled()) {
            return OpenTelemetry.noop();
        }
        return GlobalOpenTelemetry.getOrNoop();
    }

    // --- Tracer ---

    /**
     * Provides the application-wide {@link Tracer} scoped to {@code "dev.vertique"}.
     *
     * @param openTelemetry the active OpenTelemetry instance
     * @return the tracer; never null
     */
    @Provides
    @Singleton
    static Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("dev.vertique");
    }

    // --- TraceReferenceResolver ---

    /**
     * Provides the {@link TraceReferenceResolver} binding for the correlation module.
     *
     * <p>Satisfies the {@code @BindsOptionalOf TraceReferenceResolver} declared in
     * {@code CorrelationContextModule} so the REST ingress middleware can enrich the
     * {@link dev.vertique.core.correlation.CorrelationContext} with trace ids.
     *
     * @param resolver the singleton resolver
     * @return the resolver cast to {@link TraceReferenceResolver}
     */
    @Provides
    @Singleton
    static TraceReferenceResolver traceReferenceResolver(OpenTelemetryTraceReferenceResolver resolver) {
        return resolver;
    }

    // --- SecurityEventObserver multibinding ---

    /**
     * Contributes {@link SecuritySpanEventObserver} into the {@link SecurityEventObserver}
     * multibinding set.
     *
     * <p>When this module is installed alongside the rest-security module's {@code AuthModule},
     * the contributed observer receives security lifecycle events and records them as span events
     * on the active OpenTelemetry span.
     *
     * @param observer the security span event observer; provided by Dagger via its {@code @Inject} ctor
     * @return the observer cast to {@link SecurityEventObserver} for the multibinding set
     */
    @Provides
    @IntoSet
    static SecurityEventObserver securitySpanEventObserver(SecuritySpanEventObserver observer) {
        return observer;
    }

    @Provides
    @IntoSet
    static CacheObserver cacheTracingObserver(CacheTracingObserver observer) {
        return observer;
    }
}
