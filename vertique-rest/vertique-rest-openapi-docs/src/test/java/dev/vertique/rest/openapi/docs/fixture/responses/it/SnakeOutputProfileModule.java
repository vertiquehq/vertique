// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import java.util.Set;

/**
 * Contributes the test profile {@value #SNAKE_OUTPUT_PROFILE} to the component's {@code
 * Set<JsonMapperProfile>} multibinding (declared by {@code JsonRuntimeModule}, which {@code
 * RestModule} includes), from which {@code DefaultJsonMapperProfileRegistry} collects every
 * application profile.
 *
 * <p>The profile's mapper uses {@code PropertyNamingStrategies.SNAKE_CASE} with Vert.x JSON support
 * (which the registry requires of every profile mapper), so {@code displayName} is read and written
 * as {@code display_name}. A resource selects it with {@code @JsonProfile("snake-output")} on its
 * class or method; requests and responses of such an operation use it.
 *
 * <p>Unit proofs use {@link #profile()} and {@link #registry()} directly; every call returns the
 * same profile instance.
 */
@Module
public final class SnakeOutputProfileModule {

    /** The id of the profile. */
    public static final String SNAKE_OUTPUT_PROFILE = "snake-output";

    private static final JsonMapperProfile PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of(SNAKE_OUTPUT_PROFILE), mapper());

    private SnakeOutputProfileModule() {}

    /**
     * Contributes the profile to the application profile set.
     *
     * @return the {@value #SNAKE_OUTPUT_PROFILE} profile
     */
    @Provides
    @IntoSet
    static JsonMapperProfile snakeOutputProfile() {
        return PROFILE;
    }

    /**
     * Returns the {@value #SNAKE_OUTPUT_PROFILE} profile, the instance {@link
     * #snakeOutputProfile()} contributes.
     *
     * @return the profile
     */
    public static JsonMapperProfile profile() {
        return PROFILE;
    }

    /**
     * Returns a new registry holding the built-in profiles and the {@value #SNAKE_OUTPUT_PROFILE}
     * profile.
     *
     * @return the registry
     */
    public static DefaultJsonMapperProfileRegistry registry() {
        return new DefaultJsonMapperProfileRegistry(Set.of(PROFILE));
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
                .addModule(VertxJsonSupport.module())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }
}
