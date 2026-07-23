// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JSON mapper profile runtime for the Vertique framework.
 *
 * <p>This module provides the runtime implementation of the JSON mapper profile system whose
 * contracts are declared in {@code dev.vertique.core.json}. Key responsibilities:
 *
 * <ul>
 *   <li><strong>Registry implementation</strong> — {@code DefaultJsonMapperProfileRegistry}
 *       resolves named {@link dev.vertique.core.json.JsonMapperProfile} instances by
 *       {@link dev.vertique.core.json.JsonProfileId}, validates uniqueness at construction
 *       time, and runs a structural round-trip probe for each application-contributed profile.
 *   <li><strong>Built-in {@code vertx} and {@code vertique} profiles</strong> — both are seeded
 *       <strong>separately</strong> from the application set and are <strong>probe-exempt</strong>;
 *       applications may contribute neither id. {@code VertxJsonMapperProfile} delegates to
 *       {@code DatabindCodec.mapper()}, the shared
 *       {@link com.fasterxml.jackson.databind.ObjectMapper} Vert.x uses internally.
 *       {@code VertiqueJsonMapperProfile} owns an independent mapper carrying the framework's
 *       opinionated defaults (it is probe-exempt because its {@code NON_NULL} inclusion would make
 *       the null-bearing round-trip probe falsely fail).
 *   <li><strong>Opinionated Jackson defaults &amp; opt-in serdes</strong> — {@code JacksonDefaults}
 *       applies the broadly-safe {@code vertique} defaults to any
 *       {@link com.fasterxml.jackson.databind.ObjectMapper} (ISO-8601 {@code java.time} with Vert.x's
 *       {@code Instant} serializer kept authoritative, unknown-enum fallback, {@code BigDecimal} for
 *       floats, {@code NON_NULL} inclusion). The opt-in serdes {@code BigDecimalAsStringSerializer},
 *       {@code BigDecimalStrictStringDeserializer}, and {@code StrictStringDeserializer} are available
 *       for callers wanting string-form {@code BigDecimal} or strict-string binding; they are not
 *       applied by {@code JacksonDefaults}.
 *   <li><strong>Vert.x JSON support helper</strong> — {@code VertxJsonSupport} re-exports the
 *       Vert.x {@code VertxModule} so consumers can register it on a custom
 *       {@link com.fasterxml.jackson.databind.ObjectMapper} without a direct
 *       {@code vertx-core} dependency on the Jackson module class name.
 *   <li><strong>Profile factory</strong> — {@code JsonMapperProfiles} is the canonical factory
 *       for creating {@link dev.vertique.core.json.JsonMapperProfile} instances from an id and
 *       a pre-configured {@link com.fasterxml.jackson.databind.ObjectMapper}.
 *   <li><strong>Dagger wiring</strong> — {@code JsonRuntimeModule} declares the empty
 *       {@code @Multibinds Set<JsonMapperProfile>} and binds
 *       {@link dev.vertique.core.json.JsonMapperProfileRegistry} to
 *       {@code DefaultJsonMapperProfileRegistry}. Consumer modules install it via
 *       {@code @Module(includes = JsonRuntimeModule.class)}.
 * </ul>
 *
 * <p>The contracts ({@code JsonProfileId}, {@code JsonMapperProfile},
 * {@code JsonMapperProfileRegistry}, {@code JsonProfileConfigurationException},
 * {@code @JsonProfile}) remain in {@code vertique-core} so that boundary modules
 * ({@code rest-jaxrs}, {@code rest-client}, {@code kafka-json}) can reference the contracts
 * without a runtime dependency on this module.
 *
 * @see dev.vertique.core.json.JsonProfileId
 * @see dev.vertique.core.json.JsonMapperProfile
 * @see dev.vertique.core.json.JsonMapperProfileRegistry
 */
package dev.vertique.json;
