// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.util.Strings;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Resolves the effective request-body {@link ObjectMapper} for a single JAX-RS resource method
 * (FR-JSON-020).
 *
 * <p>The effective profile id is selected by precedence, highest first. A blank annotation value
 * ({@code ""} or whitespace) is treated as <em>absent</em> and falls through to the next tier
 * (harmonized blank-fall-through, mirroring rest-client/kafka):
 *
 * <ol>
 *   <li>{@code @JsonProfile} on the method when its value is non-blank (mirrors the method-then-class
 *       precedence the rest of the module uses for annotation lookup);</li>
 *   <li>{@code @JsonProfile} on the resource class when its value is non-blank;</li>
 *   <li>{@link JaxRsConfig#jsonProfile()} (config key {@code jaxrs.jsonProfile}) when non-blank;</li>
 *   <li>the global {@link JsonConfig#jsonProfile()} (config key {@code json.jsonProfile}) when non-blank;</li>
 *   <li>the reserved {@code vertx} id.</li>
 * </ol>
 *
 * <p>When the effective id is {@code vertx} the resolver returns {@code null}, signalling "no
 * override — the existing default request-body path stays". For any other id the resolver resolves
 * the {@link ObjectMapper} from the registry, which throws a
 * {@link dev.vertique.core.json.JsonProfileConfigurationException} at router-build time when the id
 * is unknown (the desired fail-fast, FR-JSON-008).
 *
 * <p>This is a stateless helper; it is not instantiated.
 */
final class RequestBodyProfileResolver {

    private RequestBodyProfileResolver() {}

    /**
     * Resolves the request-body mapper override for the given resource method.
     *
     * @param meta the resource-method metadata carrying the method and class annotations
     * @param config the JAX-RS routing config supplying the {@code jaxrs.jsonProfile} default
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} default
     * @param registry the profile registry used to resolve a non-{@code system} id to its mapper
     * @return the resolved {@link ObjectMapper} when a non-{@code system} profile applies, or
     *     {@code null} when the effective profile is {@code system} (today's default path)
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the effective non-{@code system}
     *     id is not registered
     */
    static @Nullable ObjectMapper resolveRequestBodyMapper(
            ResourceMethodMeta meta, JaxRsConfig config, JsonConfig jsonConfig, JsonMapperProfileRegistry registry) {
        String effectiveId = resolveEffectiveId(meta, config, jsonConfig);
        if (JsonProfileId.SYSTEM.value().equals(effectiveId)) {
            return null;
        }
        return registry.mapper(JsonProfileId.of(effectiveId));
    }

    /**
     * Resolves the effective profile id by precedence, treating a blank annotation value as
     * <em>absent</em> (harmonized blank-fall-through, mirroring rest-client/kafka): the non-blank
     * method {@code @JsonProfile} value, then the non-blank class {@code @JsonProfile} value, then the
     * non-blank {@code jaxrs.jsonProfile} default, then the non-blank global {@code json.jsonProfile}
     * default, then the reserved {@code system} id.
     *
     * <p>A blank ({@code ""} or whitespace) method-level {@code @JsonProfile} therefore falls through
     * to the class annotation rather than masking it, and a blank class-level {@code @JsonProfile}
     * falls through to the config tail — never producing a blank id that would crash
     * {@link JsonProfileId#of(String)}.
     *
     * @param meta the resource-method metadata
     * @param config the JAX-RS routing config supplying the {@code jaxrs.jsonProfile} default
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} default
     * @return the effective, never-null profile id string
     */
    private static String resolveEffectiveId(ResourceMethodMeta meta, JaxRsConfig config, JsonConfig jsonConfig) {
        // Precedence with blank-as-absent: method annotation, then class annotation, then the per-boundary
        // jaxrs.jsonProfile, then the global json.jsonProfile. A null/blank at every tier falls through to
        // the reserved system floor (resolved to a null mapper by the caller).
        String resolved = Strings.firstNonBlank(
                annotationValue(meta.methodAnnotations()),
                annotationValue(meta.classAnnotations()),
                config.jsonProfile(),
                jsonConfig.jsonProfile());
        return resolved != null ? resolved : JsonProfileId.SYSTEM.value();
    }

    /**
     * Returns the {@code value()} of the first {@link JsonProfile} annotation in the given list, or
     * {@code null} when no {@code @JsonProfile} is present. The returned value may itself be blank; the
     * caller treats a blank value as absent via {@link Strings#firstNonBlank}.
     *
     * @param annotations the annotations to scan (method-level or class-level)
     * @return the first {@code @JsonProfile} value, or {@code null} when none is present
     */
    private static @Nullable String annotationValue(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof JsonProfile jsonProfile) {
                return jsonProfile.value();
            }
        }
        return null;
    }
}
