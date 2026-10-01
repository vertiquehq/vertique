// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.profile;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.TagsZx;
import java.util.List;
import java.util.Set;

/**
 * Contributes the application profile {@value #TAGS_PROFILE} to the component's {@code
 * Set<JsonMapperProfile>} multibinding (declared by {@code JsonRuntimeModule}, which {@code
 * RestModule} includes), from which {@code DefaultJsonMapperProfileRegistry} collects every
 * application profile.
 *
 * <p>The profile's mapper is a copy of the built-in {@code vertique} profile's mapper. It declares
 * one JSON Schema type override for {@link TagsZx}, in both directions, with the fragment {@link
 * #TAGS_FRAGMENT}: a developer-declared {@code propertyNames} that the input generator publishes
 * verbatim, adds no guard to, and never lists in a redaction manifest.
 *
 * <p>A resource selects the profile with {@code @JsonProfile("tags-zx")} on the resource method or
 * class (method first, then class, then the configured default); the request-body profile resolver
 * resolves it through the registry and the operation's publication carries it as its profile id.
 *
 * <p>Unit proofs use {@link #profile()} and {@link #registry()} directly. Every call returns the same
 * profile instance, so a source that caches generators per profile instance builds one.
 */
@Module
public final class TagsProfileModule {

    /** The id of the profile. */
    public static final String TAGS_PROFILE = "tags-zx";

    /** The override fragment declared for {@link TagsZx}, verbatim. */
    public static final String TAGS_FRAGMENT = "{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^[a-z]+$\"},"
            + "\"additionalProperties\":{\"type\":\"string\"}}";

    /** The {@code pattern} of the developer-declared {@code propertyNames}. */
    public static final String TAGS_NAME_PATTERN = "^[a-z]+$";

    private static final JsonMapperProfile PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of(TAGS_PROFILE),
            new DefaultJsonMapperProfileRegistry(Set.of())
                    .profile(JsonProfileId.of("vertique"))
                    .mapper()
                    .copy(),
            List.of(JsonSchemaTypeOverride.both(TagsZx.class, JsonSchemaFragment.parse(TAGS_FRAGMENT))));

    private TagsProfileModule() {}

    /**
     * Contributes the profile to the application profile set.
     *
     * @return the {@value #TAGS_PROFILE} profile
     */
    @Provides
    @IntoSet
    static JsonMapperProfile tagsProfile() {
        return PROFILE;
    }

    /**
     * Returns the {@value #TAGS_PROFILE} profile, the instance {@link #tagsProfile()} contributes.
     *
     * @return the profile
     */
    public static JsonMapperProfile profile() {
        return PROFILE;
    }

    /**
     * Returns a new registry holding the built-in profiles and the {@value #TAGS_PROFILE} profile.
     *
     * @return the registry
     */
    public static DefaultJsonMapperProfileRegistry registry() {
        return new DefaultJsonMapperProfileRegistry(Set.of(PROFILE));
    }
}
