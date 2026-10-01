// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.CanonicalSchema;
import dev.vertique.json.schema.RedactionManifest;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Set;

/**
 * Request body schemas exactly as the real input-direction generator describes them, each with the
 * redaction manifest the generator bound to it.
 *
 * <p>The canonical schema source builds its body schema the same way: {@code forInputProfile} of the
 * operation's profile, then {@code describe}, with the manifest attached as the body's provenance.
 * The source additionally passes the application's Bean Validation {@code Validator} when one is
 * bound; these helpers pass none, so they match the source only in a component that binds no {@code
 * jakarta.validation.Validator}.
 *
 * <p>Profiles are resolved from a registry holding only the built-in profiles ({@code vertique} and
 * {@code vertique-strict}).
 */
public final class GeneratedBodies {

    /** The id of the default built-in profile. */
    public static final String DEFAULT_PROFILE = "vertique";

    private static final DefaultJsonMapperProfileRegistry REGISTRY = new DefaultJsonMapperProfileRegistry(Set.of());

    private GeneratedBodies() {}

    /**
     * Describes a body type under the default {@code vertique} profile.
     *
     * @param type the body type
     * @return the generated body
     */
    public static GeneratedBody describe(Type type) {
        return describe(type, DEFAULT_PROFILE);
    }

    /**
     * Describes a body type under a built-in profile.
     *
     * @param type      the body type
     * @param profileId the built-in profile id, for example {@code vertique-strict}
     * @return the generated body
     */
    public static GeneratedBody describe(Type type, String profileId) {
        return describe(type, profile(profileId));
    }

    /**
     * Describes a body type under the given profile.
     *
     * @param type    the body type
     * @param profile the profile whose input-direction generator describes the type
     * @return the generated body: a fresh schema object of the canonical document and its manifest
     */
    public static GeneratedBody describe(Type type, JsonMapperProfile profile) {
        CanonicalSchema canonical =
                AnnotationJsonSchemaGenerator.forInputProfile(profile).describe(type);
        return new GeneratedBody(new JsonObject(canonical.json()), canonical.redactionManifest());
    }

    /**
     * Resolves a built-in profile by id.
     *
     * @param profileId the profile id
     * @return the profile
     */
    public static JsonMapperProfile profile(String profileId) {
        return REGISTRY.profile(JsonProfileId.of(profileId));
    }

    /**
     * Reports whether the generator, under a built-in profile, names no reserved member of the type,
     * so redaction would remove nothing from its schema.
     *
     * @param type      the body type
     * @param profileId the built-in profile id
     * @return {@code true} exactly when the type's manifest lists no pointer
     */
    public static boolean manifestIsEmpty(Type type, String profileId) {
        return describe(type, profileId).manifestIsEmpty();
    }

    /**
     * One generated body schema and the manifest the generator bound to it.
     *
     * @param schema   a fresh schema object parsed from the canonical document
     * @param manifest the redaction manifest, attached as the body's provenance
     */
    public record GeneratedBody(JsonObject schema, RedactionManifest manifest) {

        /**
         * Checks that neither component is null.
         */
        public GeneratedBody {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(manifest, "manifest");
        }

        /**
         * Reports whether the manifest lists no pointer.
         *
         * @return {@code true} exactly when {@code manifest().pointers()} is empty
         */
        public boolean manifestIsEmpty() {
            return manifest.pointers().isEmpty();
        }
    }
}
