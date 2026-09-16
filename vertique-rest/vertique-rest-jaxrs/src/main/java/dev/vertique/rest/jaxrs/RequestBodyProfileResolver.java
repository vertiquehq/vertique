// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
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
 *   <li>{@link JsonConfig#effectiveProfile()} — the global {@code json.jsonProfile} when non-blank,
 *       else the reserved {@code vertique} floor.</li>
 * </ol>
 *
 * <p><strong>Every</strong> effective id, {@code system} included, is resolved to its
 * {@link ObjectMapper} through the registry, which throws a
 * {@link dev.vertique.core.json.JsonProfileConfigurationException} at router-build time when the id
 * is unknown (the desired fail-fast, FR-JSON-008).
 *
 * <p><strong>The {@code null} sentinel is an identity rule.</strong> The resolver returns
 * {@code null} — "no override: the Vert.x fast paths through the process JSON codec are already
 * correct for this route" — <em>iff</em> the resolved mapper is the same instance as
 * {@link VertiqueJson#mapper()}, the mapper the process codec runs on. It is never keyed on the
 * profile id: an explicit {@code system} selection collapses to {@code null} only while the
 * registry's {@code system} mapper is what is installed as the process codec. Under a
 * {@code json.systemProfile} that selects another profile the resolved {@code system} mapper is a
 * different instance and is returned, so the route binds and renders through the profile it asked
 * for rather than silently through the process codec's.
 *
 * <p><strong>Capture time.</strong> The comparison happens once per route, at router build (the
 * {@code EDGE} phase), which is after the {@code CONFIGURE} phase has installed the process mapper;
 * the built-in registry hands out one stable instance per profile, so the comparison is stable for
 * the router's lifetime. A custom registry that returns a fresh mapper per call never matches and
 * therefore never takes the fast path, and a same-id process-codec swap performed after a router was
 * built no longer matches the routes that router already decided.
 *
 * <p><strong>Outside a booted application</strong> — a test harness that builds a router without
 * running the {@code CONFIGURE} startup phase — nothing has been installed, the process codec still
 * runs Vert.x's raw mapper, and no registry mapper equals it: an explicit {@code system} selection
 * then resolves to the registry's {@code system} mapper instead of {@code null}. Such a harness that
 * needs the fast path installs {@code registry.mapper(SYSTEM)} through
 * {@link VertiqueJson#install(dev.vertique.core.json.JsonProfileId, ObjectMapper)} in setup.
 *
 * <p>This is a stateless helper; it is not instantiated.
 */
final class RequestBodyProfileResolver {

    private RequestBodyProfileResolver() {}

    /**
     * The outcome of one route's request-body profile resolution: the registry-resolved profile and
     * the process-codec identity verdict read once, at router build.
     *
     * <p>The profile is what the schema seam needs — a schema source synthesizes a body schema for the
     * very profile whose mapper parses that body. The verdict is what the request path needs, through
     * {@link #stashMapper()}: the nullable "no override" sentinel described in the class javadoc.
     * Splitting them means the identity read happens exactly once per route, here, and nowhere else.
     *
     * @param profile the registry-resolved profile for the route; never {@code null}
     * @param processCodec whether {@code profile.mapper()} is the same instance as
     *     {@link VertiqueJson#mapper()} — the mapper the process JSON codec runs on
     */
    record ResolvedRequestBodyProfile(JsonMapperProfile profile, boolean processCodec) {

        /**
         * Returns the nullable request-body mapper override every registrar consumer expects:
         * {@code null} when the route's mapper IS the process codec's (the Vert.x fast paths already
         * produce this profile's bytes), otherwise the profile's mapper.
         *
         * @return the profile's mapper, or {@code null} when it is the process codec's own mapper
         */
        @Nullable
        ObjectMapper stashMapper() {
            return processCodec ? null : profile.mapper();
        }
    }

    /**
     * Resolves the effective request-body profile for the given resource method and records whether
     * its mapper is the process codec's.
     *
     * @param meta the resource-method metadata carrying the method and class annotations
     * @param config the JAX-RS routing config supplying the {@code jaxrs.jsonProfile} default
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} default and the
     *     {@code vertique} floor
     * @param registry the profile registry every effective id is resolved through
     * @return the resolved profile and its process-codec verdict; never {@code null}
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the effective id is not
     *     registered
     */
    static ResolvedRequestBodyProfile resolveRequestBodyProfile(
            ResourceMethodMeta meta, JaxRsConfig config, JsonConfig jsonConfig, JsonMapperProfileRegistry registry) {
        JsonMapperProfile profile = registry.profile(JsonProfileId.of(resolveEffectiveId(meta, config, jsonConfig)));
        // Identity, not id equality: "no override" means "this route's mapper IS the process codec's
        // mapper", so the Vert.x fast paths already produce the profile's bytes. Read at use time —
        // router build runs in the EDGE phase, after CONFIGURE installed the process mapper.
        return new ResolvedRequestBodyProfile(profile, profile.mapper() == VertiqueJson.mapper());
    }

    /**
     * Resolves the effective profile id by precedence, treating a blank annotation value as
     * <em>absent</em> (harmonized blank-fall-through, mirroring rest-client/kafka): the non-blank
     * method {@code @JsonProfile} value, then the non-blank class {@code @JsonProfile} value, then the
     * non-blank {@code jaxrs.jsonProfile} default, then {@link JsonConfig#effectiveProfile()} — the
     * non-blank global {@code json.jsonProfile}, else the reserved {@code vertique} floor.
     *
     * <p>A blank ({@code ""} or whitespace) method-level {@code @JsonProfile} therefore falls through
     * to the class annotation rather than masking it, and a blank class-level {@code @JsonProfile}
     * falls through to the config tail — never producing a blank id that would crash
     * {@link JsonProfileId#of(String)}.
     *
     * @param meta the resource-method metadata
     * @param config the JAX-RS routing config supplying the {@code jaxrs.jsonProfile} default
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} default and the
     *     {@code vertique} floor
     * @return the effective, never-null profile id string
     */
    private static String resolveEffectiveId(ResourceMethodMeta meta, JaxRsConfig config, JsonConfig jsonConfig) {
        // Precedence with blank-as-absent: method annotation, then class annotation, then the per-boundary
        // jaxrs.jsonProfile. A null/blank at every tier falls through to the shared global tail,
        // json.jsonProfile floored at vertique — the same floor every managed edge ends at.
        String resolved = Strings.firstNonBlank(
                annotationValue(meta.methodAnnotations()),
                annotationValue(meta.classAnnotations()),
                config.jsonProfile());
        return resolved != null ? resolved : jsonConfig.effectiveProfile().value();
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
