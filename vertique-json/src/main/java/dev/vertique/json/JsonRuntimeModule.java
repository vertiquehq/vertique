// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.ComposeValidator;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module wiring the JSON mapper profile runtime.
 *
 * <p>Consumer modules ({@code rest-jaxrs}, {@code rest-client}, {@code kafka-json}) install this via
 * {@code @Module(includes = JsonRuntimeModule.class)} (FR-JSON-007B). It declares the empty
 * application multibinding seed for {@link JsonMapperProfile} (so an app that contributes no
 * profiles still wires) and binds {@link JsonMapperProfileRegistry} to
 * {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>The built-in {@code vertx} profile is <strong>not</strong> contributed into the set here — it
 * is seeded inside {@link DefaultJsonMapperProfileRegistry} separately from application profiles
 * (FR-JSON-007A).
 */
@Module
public abstract class JsonRuntimeModule {

    /**
     * Declares the empty application multibinding seed for {@link JsonMapperProfile}. Applications
     * contribute profiles into this set via {@code @IntoSet}; the set may be empty.
     *
     * @return the empty set declaration for application-contributed profiles
     */
    @Multibinds
    abstract Set<JsonMapperProfile> jsonMapperProfiles();

    /**
     * Binds the {@link JsonMapperProfileRegistry} contract to its default implementation.
     *
     * @param impl the validating registry implementation
     * @return the registry bound to {@link DefaultJsonMapperProfileRegistry}
     */
    @Binds
    @Singleton
    abstract JsonMapperProfileRegistry registry(DefaultJsonMapperProfileRegistry impl);

    /**
     * Parses the {@code json} configuration section into a typed {@link JsonConfig} at the Dagger
     * provider boundary, via the injected canonical {@link ConfigParser} (config.md R10).
     *
     * @param config the root Vert.x configuration object
     * @param parser the canonical config parser
     * @return the parsed {@link JsonConfig} carrying the global default profile id (or its default)
     */
    @Provides
    @Singleton
    static JsonConfig jsonConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "json"), JsonConfig.class);
    }

    /**
     * Contributes the global-default {@link JsonDefaultProfileValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the {@code VALIDATE} startup phase forces its
     * construction, failing fast when {@code json.jsonProfile} names an unknown profile.
     *
     * @param impl the global default-profile validator
     * @return the validator contributed into the compose-validator set
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator jsonDefaultProfileValidator(JsonDefaultProfileValidator impl) {
        return impl;
    }
}
