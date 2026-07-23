// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Registry of the JSON mapper profiles discovered at startup, keyed by {@link JsonProfileId}.
 *
 * <p>Always contains the reserved {@link JsonProfileId#VERTX} profile (the zero-config default)
 * plus any application-contributed profiles. Implementations validate the discovered set at
 * construction time and expose read-only resolution; the registry is immutable once built.
 */
public interface JsonMapperProfileRegistry {

    /**
     * Resolves the {@link ObjectMapper} for the given profile id.
     *
     * @param id the profile id to resolve
     * @return the {@link ObjectMapper} backing the named profile
     * @throws JsonProfileConfigurationException if {@code id} is not a registered profile; the
     *     exception message lists the discovered ids
     */
    ObjectMapper mapper(JsonProfileId id);

    /**
     * Resolves the {@link JsonMapperProfile} for the given profile id.
     *
     * @param id the profile id to resolve
     * @return the named profile
     * @throws JsonProfileConfigurationException if {@code id} is not a registered profile; the
     *     exception message lists the discovered ids
     */
    JsonMapperProfile profile(JsonProfileId id);

    /**
     * Returns the ids of every registered profile, including the reserved {@code vertx} id.
     *
     * @return an unmodifiable set of the registered profile ids
     */
    Set<JsonProfileId> profileIds();

    /**
     * Validates a configured (operator-supplied, possibly {@code null}/blank) default profile id.
     *
     * <p>This is a no-op when {@code profileId} is {@code null} or blank — the caller will fall
     * through to the global {@code json.jsonProfile} default and ultimately the reserved
     * {@code vertx} profile. When {@code profileId} is non-blank, it is resolved through the
     * registry via {@link #profile(JsonProfileId)}; an unknown id throws
     * {@link JsonProfileConfigurationException}.
     *
     * <p>Used by the per-boundary {@code *DefaultProfileValidator} validators for startup fail-fast:
     * each validator calls this method from its {@code @Inject} constructor so that an
     * operator-supplied id that names no registered profile surfaces at the {@code VALIDATE} phase
     * rather than at first use.
     *
     * @param profileId the operator-supplied profile id from config; may be {@code null} or blank
     * @throws JsonProfileConfigurationException if {@code profileId} is non-blank and names no
     *     registered profile; the exception message lists the discovered ids
     */
    default void validateConfigured(@Nullable String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            profile(JsonProfileId.of(profileId)); // throws JsonProfileConfigurationException on an unknown id
        }
    }
}
