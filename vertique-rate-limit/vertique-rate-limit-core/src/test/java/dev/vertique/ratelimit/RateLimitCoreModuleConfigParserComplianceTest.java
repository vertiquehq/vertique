// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 5: {@code RateLimitCoreModule} read {@code rateLimit.enabled}/
 * {@code rateLimit.defaultMode}/{@code rateLimit.local.maxTrackedKeys}/{@code cleanupIntervalMs}
 * via raw {@code JsonObject} scalar getters ({@code getBoolean}, {@code getString}, {@code
 * getLong}) instead of the canonical injected {@code dev.vertique.core.config.ConfigParser}
 * (docs/standards/config.md rule R10). Vert.x's {@code JsonObject#getLong}/{@code #getBoolean}
 * throw a raw {@link ClassCastException} when the underlying value is a coercible {@link String}
 * (e.g. {@code "maxTrackedKeys": "5"}), unlike {@code ConfigParser}'s dedicated, coercion-lenient
 * mapper.
 */
class RateLimitCoreModuleConfigParserComplianceTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    void shouldCoerceStringEncodedLocalAndRootScalarConfigThroughTheCanonicalParser() {
        JsonObject config = new JsonObject()
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put("enabled", "true")
                                .put("defaultMode", "LOCAL")
                                .put(
                                        "local",
                                        new JsonObject()
                                                .put("maxTrackedKeys", "5")
                                                .put("cleanupIntervalMs", "30000")));

        TestComponent component = DaggerRateLimitCoreModuleConfigParserComplianceTest_TestComponent.factory()
                .create(VERTX, new ConfigFixtureModule(config));

        // Constructing the graph must not throw (ClassCastException from a raw JsonObject#getLong/
        // #getBoolean against a String-encoded value) and must correctly coerce every field.
        RateLimiters rateLimiters = component.rateLimiters();
        assertThat(rateLimiters).isNotNull();
    }

    @Module
    static final class ConfigFixtureModule {
        private final JsonObject config;

        ConfigFixtureModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    @Singleton
    @Component(modules = {RateLimitCoreModule.class, ConfigFixtureModule.class})
    interface TestComponent {

        RateLimiters rateLimiters();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, ConfigFixtureModule configFixtureModule);
        }
    }
}
