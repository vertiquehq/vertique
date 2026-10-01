// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The input-direction schema generators of one document's assembly, one per JSON mapper profile an
 * operation resolves.
 *
 * <p>A generator is built the first time an operation of the document names its profile, from the
 * profile the registry resolves for that id, and reused for every later operation naming the same
 * id. One instance is created per assembly and is not shared between assemblies. It is mutable and
 * not thread-safe; an assembly runs on one thread.
 */
final class InputGenerators {

    private final JsonMapperProfileRegistry profiles;
    private final Map<String, AnnotationJsonSchemaGenerator> generators = new HashMap<>();

    /**
     * Creates the generators of one assembly.
     *
     * @param profiles the registry the profiles are resolved through
     */
    InputGenerators(JsonMapperProfileRegistry profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Returns the input-direction generator of a profile, building it on first use.
     *
     * @param profileId the resolved profile id of an operation
     * @return the generator configured for the profile's input direction
     * @throws dev.vertique.core.json.JsonProfileConfigurationException when the id names no
     *     registered profile; nothing is cached then
     * @throws dev.vertique.json.schema.JsonSchemaGenerationException when the generator cannot be
     *     built for the profile; nothing is cached then
     */
    AnnotationJsonSchemaGenerator generator(String profileId) {
        AnnotationJsonSchemaGenerator generator = generators.get(profileId);
        if (generator == null) {
            generator = AnnotationJsonSchemaGenerator.forInputProfile(profiles.profile(JsonProfileId.of(profileId)));
            generators.put(profileId, generator);
        }
        return generator;
    }
}
