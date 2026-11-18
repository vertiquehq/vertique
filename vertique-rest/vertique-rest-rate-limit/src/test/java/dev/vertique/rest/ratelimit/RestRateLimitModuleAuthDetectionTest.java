// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.RequestOriginConfig;
import dev.vertique.rest.security.SecurityModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link RestRateLimitModule} detects {@code AuthModule} through the install signal that
 * module alone provides, not through the application-replaceable {@link RequestOriginConfig}.
 *
 * <p>An edge rule using the {@code IP} dimension is accepted exactly when {@code AuthModule} is
 * installed; binding a {@link RequestOriginConfig} by itself neither satisfies nor is required by
 * the check.
 */
class RestRateLimitModuleAuthDetectionTest {

    private static final String POLICY = "edge-ip";
    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    @DisplayName("an IP edge rule is accepted when AuthModule is installed with its default origin config")
    void acceptsIpRuleWhenAuthModuleInstalled() {
        Set<Middleware> middlewares = DaggerRestRateLimitModuleAuthDetectionTest_WithAuth.factory()
                .create(VERTX)
                .middlewares();

        assertThat(middlewares).anyMatch(RateLimitEdgeMiddleware.class::isInstance);
    }

    @Test
    @DisplayName(
            "an IP edge rule is accepted when AuthModule is installed and the application replaces the origin config")
    void acceptsIpRuleWhenAuthModuleInstalledWithReplacedOriginConfig() {
        Set<Middleware> middlewares = DaggerRestRateLimitModuleAuthDetectionTest_WithAuthAndOriginConfig.factory()
                .create(VERTX)
                .middlewares();

        assertThat(middlewares).anyMatch(RateLimitEdgeMiddleware.class::isInstance);
    }

    @Test
    @DisplayName("an IP edge rule is rejected when AuthModule is not installed")
    void rejectsIpRuleWithoutAuthModule() {
        WithoutAuth component =
                DaggerRestRateLimitModuleAuthDetectionTest_WithoutAuth.factory().create(VERTX);

        assertThatThrownBy(component::middlewares)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("AuthModule");
    }

    @Test
    @DisplayName("an application-bound origin config does not stand in for the missing AuthModule")
    void rejectsIpRuleWhenOnlyOriginConfigIsBound() {
        WithoutAuthButOriginConfig component =
                DaggerRestRateLimitModuleAuthDetectionTest_WithoutAuthButOriginConfig.factory()
                        .create(VERTX);

        assertThatThrownBy(component::middlewares)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("AuthModule");
    }

    @Module
    static final class EdgeFixtureModule {

        @Provides
        @VertxConfig
        static JsonObject vertxConfig() {
            JsonObject rule = new JsonObject().put("policy", POLICY).put("key", new JsonArray().add("IP"));
            JsonObject edge = new JsonObject().put("enabled", true).put("rules", new JsonArray().add(rule));
            return new JsonObject().put("rateLimit", new JsonObject().put("rest", new JsonObject().put("edge", edge)));
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }

        @Provides
        @IntoSet
        static RateLimitPolicy edgePolicy() {
            return RateLimitEdgeTestFixture.tokenBucketPolicy(POLICY, 5L, RateLimitFailureMode.CLOSED);
        }
    }

    @Module
    static final class OriginConfigModule {

        @Provides
        @Singleton
        static RequestOriginConfig requestOriginConfig() {
            return new RequestOriginConfig(Set.of("10.0.0.0/8"), 4, true, true);
        }
    }

    @Singleton
    @Component(
            modules = {
                RestRateLimitModule.class,
                AuthModule.class,
                SecurityModule.class,
                ContextRuntimeModule.class,
                EdgeFixtureModule.class
            })
    interface WithAuth {

        Set<Middleware> middlewares();

        @Component.Factory
        interface Factory {
            WithAuth create(@BindsInstance Vertx vertx);
        }
    }

    @Singleton
    @Component(
            modules = {
                RestRateLimitModule.class,
                AuthModule.class,
                SecurityModule.class,
                ContextRuntimeModule.class,
                EdgeFixtureModule.class,
                OriginConfigModule.class
            })
    interface WithAuthAndOriginConfig {

        Set<Middleware> middlewares();

        @Component.Factory
        interface Factory {
            WithAuthAndOriginConfig create(@BindsInstance Vertx vertx);
        }
    }

    @Singleton
    @Component(modules = {RestRateLimitModule.class, EdgeFixtureModule.class})
    interface WithoutAuth {

        Set<Middleware> middlewares();

        @Component.Factory
        interface Factory {
            WithoutAuth create(@BindsInstance Vertx vertx);
        }
    }

    @Singleton
    @Component(modules = {RestRateLimitModule.class, EdgeFixtureModule.class, OriginConfigModule.class})
    interface WithoutAuthButOriginConfig {

        Set<Middleware> middlewares();

        @Component.Factory
        interface Factory {
            WithoutAuthButOriginConfig create(@BindsInstance Vertx vertx);
        }
    }
}
