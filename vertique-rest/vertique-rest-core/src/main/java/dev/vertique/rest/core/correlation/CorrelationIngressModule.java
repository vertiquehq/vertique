// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.rest.core.middleware.Middleware;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that wires the REST-side correlation ingress.
 *
 * <p>Declares:
 * <ul>
 *   <li>Empty multibinding sets for {@link ProtocolCorrelationSpec} and
 *       {@link ProtocolCorrelationContributor} so applications and preset modules (e.g. a future
 *       {@code vertique-rest-fapi}) can contribute via {@code @IntoSet} without redeclaring the
 *       multibinds.</li>
 *   <li>{@link CorrelationIngressConfig} resolved from the {@code "correlation.ingress"} section
 *       of the application config — same convention used by {@code JaxRsConfig},
 *       {@code CorsConfig}, and {@code HttpConfig} in {@code RestCoreModule}. Missing or empty
 *       sections fall back to {@link CorrelationIngressConfig#defaults()} via the record's
 *       {@code @JsonCreator}. Apps that need a fully programmatic override can replace this
 *       binding by removing {@link CorrelationIngressModule} from the {@code RestCoreModule}
 *       includes list (or by providing their own concrete subclass) — there is no
 *       {@code @BindsOptionalOf} indirection because the JSON section is itself the override
 *       channel.</li>
 *   <li>{@link CorrelationIngressMiddleware} into the framework's {@code Set<Middleware>}
 *       multibinding via {@code @IntoSet} — without this provider the graph compiles but ingress
 *       never runs, which is exactly the failure mode this explicit provider prevents.</li>
 * </ul>
 *
 * <p>Wired transitively into REST applications via {@code RestCoreModule.includes}.
 */
@Module
public abstract class CorrelationIngressModule {

    /** Declares the empty multibinding set for {@link ProtocolCorrelationSpec}. */
    @Multibinds
    abstract Set<ProtocolCorrelationSpec> protocolSpecs();

    /** Declares the empty multibinding set for {@link ProtocolCorrelationContributor}. */
    @Multibinds
    abstract Set<ProtocolCorrelationContributor> protocolContributors();

    /**
     * Resolves {@link CorrelationIngressConfig} from the {@code "correlation.ingress"} section of
     * the application config. Falls back to {@link CorrelationIngressConfig#defaults()} when the
     * section is absent (the {@code @JsonCreator} on the record fills missing fields).
     *
     * @param config the full application configuration injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the deserialised correlation ingress configuration
     */
    @Provides
    @Singleton
    static CorrelationIngressConfig correlationIngressConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "correlation", "ingress"), CorrelationIngressConfig.class);
    }

    /**
     * Contributes the correlation ingress middleware into the framework's middleware set.
     *
     * @param middleware the singleton ingress middleware
     * @return the middleware contribution
     */
    @Provides
    @IntoSet
    @Singleton
    static Middleware correlationIngressMiddleware(CorrelationIngressMiddleware middleware) {
        return middleware;
    }
}
