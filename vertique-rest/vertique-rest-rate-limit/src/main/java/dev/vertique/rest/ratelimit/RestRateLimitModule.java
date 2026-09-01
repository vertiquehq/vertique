// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.security.OriginCaptureMiddleware;
import dev.vertique.rest.security.RequestOriginConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger wiring for the REST rate-limit adapter (contracts/rest-adapter.md, "Dagger wiring").
 *
 * <p>Includes {@link RateLimitCoreModule} and contributes the {@link RateLimitExceptionMapper}'s
 * two nested mappers unconditionally, plus the optional edge {@link RateLimitEdgeMiddleware} only
 * when {@code rateLimit.rest.edge.enabled} is {@code true}. Carries a compile dependency on {@code
 * vertique-rest-security} for {@link OriginCaptureMiddleware}/{@code RequestOrigin} only — this
 * module never contributes or requires {@code AuthModule}/{@code SecurityModule} itself;
 * co-installing {@code AuthModule} so {@link OriginCaptureMiddleware} is bound remains the
 * application's responsibility, enforced by the {@code IP}-dimension startup validation in {@link
 * RateLimitEdgeMiddleware}'s constructor.
 */
@Module(includes = RateLimitCoreModule.class)
public abstract class RestRateLimitModule {

    private RestRateLimitModule() {}

    /**
     * Declares the optional {@link RequestOriginConfig} binding this module probes to decide
     * whether an {@code IP}-dimension edge rule may be declared. Resolves present only when the
     * application co-installs {@code vertique-rest-security}'s {@code AuthModule} — the only
     * binding site for {@link RequestOriginConfig}, and therefore the same condition that gates
     * whether {@link OriginCaptureMiddleware} is bound (its {@code @Inject} constructor's
     * transitive dependency). Dagger's {@code @BindsOptionalOf} refuses to probe {@link
     * OriginCaptureMiddleware} directly: a type with its own {@code @Inject} constructor is always
     * considered present, so this module probes {@link RequestOriginConfig} instead — the plain
     * {@code @Provides}-only binding {@code AuthModule} is the sole source of.
     */
    @BindsOptionalOf
    abstract RequestOriginConfig optionalRequestOriginConfig();

    /**
     * Contributes the {@code 429} mapper unconditionally (contracts/rest-adapter.md, "HTTP
     * mapping" — always active regardless of the edge kill switch).
     *
     * @param mapper the injected mapper
     * @return the mapper contributed to the {@code Set<ExceptionMapper<?>>} multibinding
     */
    @Provides
    @IntoSet
    static ExceptionMapper<?> rateLimitExceededExceptionMapper(RateLimitExceptionMapper.Exceeded mapper) {
        return mapper;
    }

    /**
     * Contributes the {@code 503} mapper unconditionally (contracts/rest-adapter.md, "HTTP
     * mapping" — always active regardless of the edge kill switch).
     *
     * @param mapper the injected mapper
     * @return the mapper contributed to the {@code Set<ExceptionMapper<?>>} multibinding
     */
    @Provides
    @IntoSet
    static ExceptionMapper<?> rateLimitUnavailableExceptionMapper(RateLimitExceptionMapper.Unavailable mapper) {
        return mapper;
    }

    /**
     * Resolves {@link RateLimitEdgeConfig} from the {@code rateLimit.rest.edge} section of the
     * application configuration.
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the deserialized, validated edge config
     */
    @Provides
    @Singleton
    static RateLimitEdgeConfig rateLimitEdgeConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "rateLimit", "rest", "edge"), RateLimitEdgeConfig.class);
    }

    /**
     * Contributes the edge {@link Middleware} into the framework's {@code Set<Middleware>}
     * multibinding. When {@link RateLimitEdgeConfig#enabled()} is {@code false}, a no-op middleware
     * is contributed instead — the same "contribute a no-op when disabled" idiom {@code
     * RestCoreModule}'s CORS customizer uses — so the multibinding shape never depends on config.
     *
     * @param config the resolved edge config
     * @param rateLimiters the application-scoped rate-limit runtime
     * @param originCaptureBinding the optional {@link RequestOriginConfig} binding probe — present
     *     exactly when {@code AuthModule} (and therefore {@link OriginCaptureMiddleware}) is
     *     co-installed
     * @param exceptionMappers the application's full {@code Set<ExceptionMapper<?>>} multibinding
     *     (rest-core's {@code RestCoreModule}), including this module's own unconditionally
     *     contributed {@code 429}/{@code 503} mappers plus any application override — resolved by
     *     the edge middleware into its own {@link dev.vertique.rest.jaxrs.ExceptionMapperRegistry}
     *     so a non-permitting edge decision renders through the same mapper chain {@code execute()}
     *     uses (contracts/rest-adapter.md, "HTTP mapping")
     * @return the contributed middleware
     */
    @Provides
    @IntoSet
    static Middleware rateLimitEdgeMiddlewareContribution(
            RateLimitEdgeConfig config,
            RateLimiters rateLimiters,
            Optional<RequestOriginConfig> originCaptureBinding,
            Set<ExceptionMapper<?>> exceptionMappers) {
        if (!config.enabled()) {
            return NoOpMiddleware.INSTANCE;
        }
        return new RateLimitEdgeMiddleware(config, rateLimiters, originCaptureBinding.isPresent(), exceptionMappers);
    }

    /** Contributed instead of {@link RateLimitEdgeMiddleware} when the edge limiter is disabled. */
    private enum NoOpMiddleware implements Middleware {
        INSTANCE;

        @Override
        public int priority() {
            return RateLimitEdgeMiddleware.ORDER;
        }

        @Override
        public MiddlewareScope scope() {
            return MiddlewareScope.ROOT;
        }

        @Override
        public void handle(RoutingContext ctx) {
            ctx.next();
        }
    }
}
