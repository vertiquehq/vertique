// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * Canonical, injectable facade for parsing {@link JsonObject} configuration sections into typed
 * records.
 *
 * <p>{@code ConfigParser} is the framework's one config-parsing seam. A boundary {@code @Provides}
 * provider injects a {@code ConfigParser} and parses each module's config section through it, so
 * config binds reliably regardless of how any other {@code ObjectMapper} in the process is
 * configured. The implementation owns a dedicated, isolated config mapper — coercion-lenient and
 * tolerant of unknown properties — that never shares state with Vert.x's
 * {@code DatabindCodec.mapper()} or any global/REST mapper.
 *
 * <p>The default implementation's mapper registers the same Jackson modules Vert.x's
 * {@code DatabindCodec.mapper()} installs (jdk8, jsr310, keyed-collection support, and Vert.x's
 * {@code VertxModule}) so config record fields typed as {@link JsonObject}/
 * {@link io.vertx.core.json.JsonArray} — and {@link java.time.Instant}/{@code byte[]} — round-trip
 * with their values intact rather than silently binding to an empty object.
 *
 * <p>An application may supply a custom config mapper through the {@code @ConfigMapper} override
 * seam; the framework re-layers its mandatory modules and lenient policy over the override so a
 * config parse never loses the framework's required behavior.
 */
public interface ConfigParser {

    /**
     * Parses a configuration section into a typed record. A {@code null} or empty section
     * deserializes as the type's default shape (honoring any {@code @JsonCreator} or defaulted
     * components).
     *
     * @param section the configuration section, or {@code null} for an empty section
     * @param type the target record type
     * @param <T> the target type
     * @return the parsed instance
     * @throws ConfigurationException if the section cannot be deserialized into {@code type}
     */
    <T> T parse(JsonObject section, Class<T> type);

    /**
     * Parses a section that <em>is</em> a keyed object {@code {key:{...}}} into a list, injecting
     * each entry's key into the named identity property of every element.
     *
     * @param section the keyed-object configuration section, or {@code null}/empty for an empty list
     * @param identityProp the element property that receives each entry's key
     * @param elementType the element type
     * @param <T> the element type
     * @return the parsed list, one element per entry, in the section's insertion order
     * @throws ConfigurationException if an entry key is blank, an entry value is not a JSON object,
     *     an explicit identity conflicts with its key, or an entry cannot be deserialized
     */
    <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType);

    /**
     * Parses a section that <em>is</em> a keyed object {@code {key:{...}}} into a list, injecting
     * each entry's key into the named identity property and additionally injecting every entry of
     * {@code fixedProps} (constant for the whole call) into each element's JSON before
     * deserialization.
     *
     * <p>The fixed properties are injected so an immutable record's validating compact constructor
     * sees every required field already populated.
     *
     * @param section the keyed-object configuration section, or {@code null}/empty for an empty list
     * @param identityProp the element property that receives each entry's key
     * @param elementType the element type
     * @param fixedProps constant properties to inject into every element before deserialization
     * @param <T> the element type
     * @return the parsed list, one element per entry, in the section's insertion order
     * @throws ConfigurationException if an entry key is blank, an entry value is not a JSON object,
     *     an explicit identity or fixed property conflicts with its injected value, or an entry
     *     cannot be deserialized
     */
    <T> List<T> parseKeyedObject(
            JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps);
}
