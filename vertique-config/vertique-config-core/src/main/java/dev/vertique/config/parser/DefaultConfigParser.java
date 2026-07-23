// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.json.keyed.KeyedCollectionDeserializer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default {@link ConfigParser} implementation, bound to exactly one {@link ObjectMapper}.
 *
 * <p>{@code DefaultConfigParser} holds the parse/keyed-object logic for the framework's config
 * seam. The {@code ConfigParsingModule} provider constructs it over the lenient default config
 * mapper (or over an application-supplied {@code @ConfigMapper} override, finalized for config). It
 * is also directly constructible in tests via the public {@code (ObjectMapper)} constructor.
 *
 * <p>All deserialization runs through the held mapper, so the parser's behavior is entirely
 * determined by that mapper's configuration. {@link ConfigurationException} wrapping and messages
 * preserve the framework's value-free, secret-safe error contract (SEC-1).
 */
public final class DefaultConfigParser implements ConfigParser {

    /**
     * The mapper every parse on this instance runs through; never {@code null} and never mutated.
     */
    private final ObjectMapper mapper;

    /**
     * Creates a config parser over the supplied mapper.
     *
     * @param mapper the mapper this parser binds to; must not be {@code null}
     * @throws NullPointerException if {@code mapper} is {@code null}
     */
    public DefaultConfigParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Parses a configuration section into a typed record using this parser's mapper. A {@code null}
     * or empty section deserializes as the type's default shape (honoring any {@code @JsonCreator}
     * or defaulted components).
     *
     * @param section the configuration section, or {@code null} for an empty section
     * @param type the target record type
     * @param <T> the target type
     * @return the parsed instance
     * @throws ConfigurationException if the section cannot be deserialized into {@code type}
     */
    @Override
    public <T> T parse(JsonObject section, Class<T> type) {
        JsonObject effective = section != null ? section : new JsonObject();
        try {
            return mapper.readValue(effective.encode(), type);
        } catch (IOException | IllegalArgumentException ex) {
            throw toConfigurationException(
                    ex,
                    "Failed to parse configuration section into " + type.getSimpleName() + " — see cause for details");
        }
    }

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
    @Override
    public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
        return parseKeyedObject(section, identityProp, elementType, Map.of());
    }

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
    @Override
    public <T> List<T> parseKeyedObject(
            JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
        if (section == null || section.isEmpty()) {
            return List.of();
        }
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(section.encode());
        } catch (IOException ex) {
            throw toConfigurationException(
                    ex, "Failed to read keyed-collection section as JSON — see cause for details");
        }
        List<T> result = new ArrayList<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            ObjectNode element = KeyedCollectionDeserializer.injectKey(entry.getKey(), value, identityProp, fixedProps);
            try {
                result.add(mapper.convertValue(element, elementType));
            } catch (IllegalArgumentException ex) {
                throw toConfigurationException(
                        ex,
                        "Failed to parse keyed-collection entry '" + entry.getKey() + "' into "
                                + elementType.getSimpleName() + " — see cause for details");
            }
        }
        return List.copyOf(result);
    }

    // --- Failure translation ---

    /**
     * Translates a deserialization failure into a {@link ConfigurationException}, preserving a
     * framework validation message when one is present in the cause chain.
     *
     * <p>Two cases are distinguished:
     *
     * <ul>
     *   <li><strong>Framework validation failure</strong> — when {@code failure}'s cause chain
     *       contains a {@link ConfigurationException} thrown by a config record's compact constructor
     *       or {@code @JsonCreator} (e.g. {@code kafka.consumers.orders.eventBusTimeoutMs must be > 0}),
     *       that exception is propagated as-is. Its message is value-free by the framework's own
     *       convention (it names the dotted config path/identity, never a config value), so surfacing
     *       it to the caller's {@code getMessage()} is safe and useful.</li>
     *   <li><strong>Raw type-mismatch failure</strong> — when no framework {@link ConfigurationException}
     *       is in the chain (a plain Jackson coercion/type error whose diagnostic may embed the
     *       offending config value), a new {@link ConfigurationException} carrying only the supplied
     *       value-free {@code genericMessage} is thrown, with the original failure kept as the cause.
     *       This is the SEC-1 non-leakage behavior.</li>
     * </ul>
     *
     * @param failure the caught deserialization failure
     * @param genericMessage the value-free fallback message used when no framework validation
     *     exception is present in the cause chain
     * @return a {@link ConfigurationException} to throw — either the propagated framework exception or
     *     a new one wrapping {@code failure} with {@code genericMessage}
     */
    private static ConfigurationException toConfigurationException(Throwable failure, String genericMessage) {
        ConfigurationException configCause = findConfigCause(failure);
        if (configCause != null) {
            return configCause;
        }
        return new ConfigurationException(genericMessage, failure);
    }

    /**
     * Walks the {@code getCause()} chain of {@code failure} looking for a framework
     * {@link ConfigurationException} (a validation failure raised by a config record's compact
     * constructor or {@code @JsonCreator}, whose message is value-free by convention).
     *
     * <p>The traversal is cycle-safe: an {@link IdentityHashMap}-backed visited set detects any
     * cause cycle (including longer cycles of the form {@code A → B → A}) and stops the walk
     * immediately, preventing an infinite loop. Identity comparison is correct here because
     * {@link Throwable} equality is identity anyway.
     *
     * @param failure the throwable whose cause chain to inspect; may be {@code null}
     * @return the first {@link ConfigurationException} found in the chain (including {@code failure}
     *     itself), or {@code null} if none is present
     */
    static ConfigurationException findConfigCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!visited.add(current)) {
                // Cycle detected — stop the walk without finding a ConfigurationException.
                break;
            }
            if (current instanceof ConfigurationException configException) {
                return configException;
            }
        }
        return null;
    }
}
