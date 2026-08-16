// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * SPI for a code-owned, named JSON mapper profile.
 *
 * <p>A profile pairs a stable {@link JsonProfileId} with a configured Jackson {@link ObjectMapper}.
 * Applications contribute profiles to bind framework JSON boundaries (request body parsing, REST
 * client serialization, Kafka serde) to a specific mapper configuration. The reserved
 * {@link JsonProfileId#VERTX} profile is the zero-config default and is supplied by the framework;
 * applications must not contribute a profile with that id.
 */
public interface JsonMapperProfile {

    /**
     * Returns the stable identifier under which this profile is registered and selected.
     *
     * @return the non-null profile id
     */
    JsonProfileId id();

    /**
     * Returns the configured Jackson mapper this profile exposes. Implementations return the same
     * mapper instance on each call; callers must not mutate it.
     *
     * @return the non-null {@link ObjectMapper} backing this profile
     */
    ObjectMapper mapper();

    /**
     * Returns the schema type overrides this profile declares.
     *
     * <p>Skeleton: behavior is added in the green step of this slice.
     *
     * @return the overrides
     */
    default List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
        return null;
    }
}
