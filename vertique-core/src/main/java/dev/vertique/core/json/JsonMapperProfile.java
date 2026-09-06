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
 * {@link JsonProfileId#SYSTEM} profile is the framework-supplied baseline; applications must not
 * contribute a profile with that id, with either of the other reserved ids ({@code vertique},
 * {@code vertique-strict}), or with the retired {@code vertx} id.
 */
public interface JsonMapperProfile {

    /**
     * Returns the stable identifier under which this profile is registered and selected.
     *
     * @return the non-null profile id
     */
    JsonProfileId id();

    /**
     * Returns the configured Jackson mapper this profile exposes.
     *
     * <p>Implementations return <strong>one stable instance per profile</strong>: every call returns
     * the same {@link ObjectMapper} reference, so callers may compare mappers by identity to decide
     * whether two boundaries share a configuration. Callers must not mutate the returned mapper —
     * Jackson forbids reconfiguring a mapper after first use, and the registry validates a profile
     * once, at construction: it cannot prevent post-boot reconfiguration, which this contract forbids.
     *
     * @return the non-null {@link ObjectMapper} backing this profile
     */
    ObjectMapper mapper();

    /**
     * Returns the JSON Schema overrides this profile declares for the schema generator — the wire
     * contract the profile's mapper actually produces or accepts for specific Java classes.
     *
     * <p>Implementations return the same stable, non-null, unmodifiable list on each call and must
     * defensively copy any caller-supplied collection. The empty default preserves every profile
     * implementation written before overrides existed.
     *
     * @return the non-null, unmodifiable list of declared overrides; empty by default
     */
    default List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
        return List.of();
    }
}
